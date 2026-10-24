/*
 * Copyright (c) 2026 Accelerate Networks LLC
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.logforwarding

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.json.JSONObject

class RenderedFrame(
    /** The Resource block, carried into every post. */
    val resource: String,
    /** One rendered LogRecord each; the meta record is first. UTF-8 JSON. */
    val records: List<String>,
    /** Identifies this rendering; what the outbox names the frame's directory after. */
    val frameId: String
)

interface Formatter {
    fun format(frame: Frame): RenderedFrame

    /** Greedy; each string is one request body. */
    fun pack(rendered: RenderedFrame, maxPostBytes: Int): List<String>

    /**
     * Null while nothing in [ForwardSettings] reaches the formatter. Non-null is both "this change
     * needs a replay" and the per-record test it needs.
     */
    fun hasReEncoding(old: ForwardSettings, new: ForwardSettings): ((Record) -> Boolean)?
}

/** Constant for the process that produces a payload. */
class ForwarderIdentity(
    val serviceName: String,
    val deviceUuid: String,
    val version: String,
    val platform: String,
    val process: String,
    val subsystem: String,
    val tenant: String?,
    val extension: String?
)

class OtlpFormatter(
    private val identity: ForwarderIdentity,
    private val settings: ForwardSettings,
    private val replay: Replay?
) : Formatter {
    companion object {
        private const val BODY_PREFIX =
            "{\"resourceLogs\":[{\"resource\":"
        private const val BODY_MIDDLE =
            ",\"scopeLogs\":[{\"scope\":{},\"logRecords\":["
        private const val BODY_SUFFIX = "]}]}]}"
    }

    override fun format(frame: Frame): RenderedFrame {
        val bare = frame.records.map { render(it, null) }
        val frameId = frameId(frame, bare)
        val records = mutableListOf(meta(frame, frameId))
        frame.records.forEach { records.add(render(it, frameId)) }
        return RenderedFrame(resource(), records, frameId)
    }

    override fun pack(rendered: RenderedFrame, maxPostBytes: Int): List<String> {
        val head = BODY_PREFIX + rendered.resource + BODY_MIDDLE
        val overhead = utf8Size(head) + utf8Size(BODY_SUFFIX)
        val bodies = mutableListOf<String>()
        val current = StringBuilder()
        var size = overhead
        var count = 0
        for (record in rendered.records) {
            val cost = utf8Size(record) + if (count > 0) 1 else 0
            if (count > 0 && size + cost > maxPostBytes) {
                bodies.add(head + current + BODY_SUFFIX)
                current.setLength(0)
                size = overhead
                count = 0
            }
            if (count > 0) current.append(',')
            current.append(record)
            size += utf8Size(record) + if (count > 0) 1 else 0
            count++
        }
        if (count > 0) bodies.add(head + current + BODY_SUFFIX)
        return bodies
    }

    override fun hasReEncoding(old: ForwardSettings, new: ForwardSettings): ((Record) -> Boolean)? = null

    private fun resource(): String {
        val attributes = listOf(
            attribute("service.name", identity.serviceName),
            attribute("service.instance.id", identity.deviceUuid),
            attribute("service.version", identity.version),
            attribute("os.name", identity.platform)
        )
        return "{\"attributes\":[${attributes.joinToString(",")}]}"
    }

    private fun render(record: Record, frameId: String?): String {
        val attributes = mutableListOf(
            attribute("level", record.level.token),
            attribute("process", record.process),
            attribute("subsystem", record.subsystem)
        )
        record.tag?.let { attributes.add(attribute("tag", it)) }
        frameId?.let { attributes.add(0, attribute("frame_id", it)) }
        val body = StringBuilder(rfc3339(record.localTime, record.timezone))
        body.append(' ').append(record.text)
        if (record.binaryBytes > 0) {
            body.append("<Skipped ${record.binaryBytes} bytes of binary data>")
        }
        return logRecord(
            nanos = epochNanos(record.localTime, record.timezone),
            level = record.level,
            body = body.toString(),
            attributes = attributes
        )
    }

    private fun meta(frame: Frame, frameId: String): String {
        val anchor = frame.firstRecord
        val localTime = anchor?.localTime ?: now(frame.declaredTimezone)
        val timezone = anchor?.timezone ?: frame.declaredTimezone
        val level = if (frame.lostBytes != 0L) Level.Warning else Level.Message
        val lost = if (frame.lostBytes < 0) "unknown" else frame.lostBytes.toString()
        val body = "${rfc3339(localTime, timezone)} [${LogForwarding.FORWARDER_TAG}] " +
            "${frame.records.size} records, ${frame.startOffset}-${frame.endOffset}, $lost bytes lost"

        val attributes = mutableListOf(
            attribute("process", identity.process),
            attribute("subsystem", identity.subsystem),
            attribute("tag", LogForwarding.FORWARDER_TAG),
            attribute("level", level.token),
            attribute("frame_id", frameId),
            attribute("cursor_file", frame.cursor.file.value),
            attribute("cursor_from", frame.startOffset.toString()),
            attribute("cursor_to", frame.endOffset.toString()),
            attribute("lost_bytes", frame.lostBytes.toString())
        )
        identity.tenant?.let { attributes.add(attribute("tenant", it)) }
        identity.extension?.let { attributes.add(attribute("extension", it)) }

        return logRecord(epochNanos(localTime, timezone), level, body, attributes)
    }

    private fun logRecord(nanos: Long, level: Level, body: String, attributes: List<String>): String {
        return "{\"timeUnixNano\":\"$nanos\"," +
            "\"severityNumber\":${level.severityNumber}," +
            "\"severityText\":${JSONObject.quote(level.token)}," +
            "\"body\":{\"stringValue\":${JSONObject.quote(body)}}," +
            "\"attributes\":[${attributes.joinToString(",")}]}"
    }

    private fun attribute(key: String, value: String): String =
        "{\"key\":${JSONObject.quote(key)},\"value\":{\"stringValue\":${JSONObject.quote(value)}}}"

    /** Composite values contribute their own digest rather than their contents. */
    private fun frameId(frame: Frame, bare: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val head = buildString {
            append(frame.cursor.file.value).append('\n')
            append(frame.startOffset).append('\n')
            append(frame.endOffset).append('\n')
        }
        digest.update(head.toByteArray(Charsets.UTF_8))
        digest.update(settings.sha256())
        replay?.let { digest.update(it.sha256()) }
        val records = buildString { bare.forEach { append(it).append('\n') } }
        return digest.digest(records.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun rfc3339(localTime: String, timezone: String): String =
        isoLocal(localTime) + timezone

    private fun epochNanos(localTime: String, timezone: String): Long {
        return try {
            val instant = LocalDateTime.parse(isoLocal(localTime))
                .toInstant(ZoneOffset.of(timezone))
            instant.epochSecond * 1_000_000_000L + instant.nano
        } catch (e: Exception) {
            System.currentTimeMillis() * 1_000_000L
        }
    }

    private fun isoLocal(localTime: String): String {
        val space = localTime.indexOf(' ')
        if (space < 0) return localTime
        val date = localTime.substring(0, space)
        val time = localTime.substring(space + 1)
        val millis = time.lastIndexOf(':')
        if (millis < 0) return "${date}T$time"
        return "${date}T${time.substring(0, millis)}.${time.substring(millis + 1)}"
    }

    private fun now(timezone: String): String {
        val offset = try {
            ZoneOffset.of(timezone)
        } catch (e: Exception) {
            ZoneOffset.UTC
        }
        val local = LocalDateTime.now(offset)
        return "%04d-%02d-%02d %02d:%02d:%02d:%03d".format(
            local.year, local.monthValue, local.dayOfMonth,
            local.hour, local.minute, local.second, local.nano / 1_000_000
        )
    }

    private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size
}
