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

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Latin-1 never leaves this class: the window is matched one byte to one character, and each
 * matched record is decoded as UTF-8 for the [Record] it returns.
 */
class LogRecordExtractor : Extractor {
    companion object {
        private const val TIMESTAMP =
            "[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}:[0-9]{3}"
        private const val PROCESS = "[^\\x0D\\x0A\\x2F\\x5D]*"
        private const val SUBSYSTEM = "[^\\x0D\\x0A\\x5D]*"
        private const val LEVELS = "FATAL|ERROR|WARNING|MESSAGE|DEBUG"
        private const val TAG = "[^\\x0D\\x0A\\x5D]*"

        private const val NEXT_HEADER =
            "$TIMESTAMP \\[$PROCESS/$SUBSYSTEM\\] (?:$LEVELS) "

        private val PATTERN: Pattern = Pattern.compile(
            "(($TIMESTAMP) \\[($PROCESS)/($SUBSYSTEM)\\] ($LEVELS) ((?:\\[($TAG)\\])?.*?))(?=($NEXT_HEADER)|\\z)",
            Pattern.DOTALL
        )

        private const val GROUP_RECORD = 1
        private const val GROUP_TIMESTAMP = 2
        private const val GROUP_PROCESS = 3
        private const val GROUP_SUBSYSTEM = 4
        private const val GROUP_LEVEL = 5
        private const val GROUP_BODY = 6
        private const val GROUP_TAG = 7
        private const val GROUP_LOOKAHEAD = 8

        private const val LINE_FEED = 0x0A.toByte()

        private val ANCHOR = Regex(
            "\\[${LogForwarding.FORWARDER_TAG}\\] ${LogForwarding.ANCHOR_MESSAGE} (Z|[+-][0-9]{2}:[0-9]{2})"
        )
    }

    private var cachedWindow: Window? = null
    private var cachedMatcher: Matcher? = null

    override fun next(window: Window, from: Long, acceptWindowEnd: Boolean): Record? {
        val matcher = matcherFor(window)
        val searchFrom = (from - window.start).toInt()
        if (searchFrom < 0 || searchFrom > window.bytes.size) return null
        if (!matcher.find(searchFrom)) return null
        if (matcher.group(GROUP_LOOKAHEAD) == null && !acceptWindowEnd) return null

        val recordStart = matcher.start(GROUP_RECORD)
        val recordEnd = matcher.end(GROUP_RECORD)
        val bodyStart = matcher.start(GROUP_BODY)
        val textEnd = if (recordEnd > bodyStart && window.bytes[recordEnd - 1] == LINE_FEED) {
            recordEnd - 1
        } else {
            recordEnd
        }

        val level = Level.record(matcher.group(GROUP_LEVEL).orEmpty()) ?: return null
        val decoded = decodeUtf8Prefix(window.bytes, bodyStart, textEnd)
        val rawTag = matcher.group(GROUP_TAG)?.let {
            utf8(window.bytes, matcher.start(GROUP_TAG), matcher.end(GROUP_TAG))
        }
        val tag = rawTag?.let { ForwardSettings.normalizeTag(it) }?.takeIf { it.isNotEmpty() }

        return Record(
            localTime = utf8(window.bytes, matcher.start(GROUP_TIMESTAMP), matcher.end(GROUP_TIMESTAMP)),
            process = utf8(window.bytes, matcher.start(GROUP_PROCESS), matcher.end(GROUP_PROCESS)),
            subsystem = utf8(window.bytes, matcher.start(GROUP_SUBSYSTEM), matcher.end(GROUP_SUBSYSTEM)),
            tag = tag,
            level = level,
            declaredTimezone = declaredTimezone(tag, decoded.first),
            text = decoded.first,
            binaryBytes = decoded.second.toLong(),
            orphanedBytes = (recordStart - searchFrom).toLong(),
            startOffset = window.start + recordStart,
            endOffset = window.start + recordEnd
        )
    }

    private fun matcherFor(window: Window): Matcher {
        val cached = cachedMatcher
        if (cachedWindow === window && cached != null) return cached
        val matcher = PATTERN.matcher(window.latin1)
        cachedWindow = window
        cachedMatcher = matcher
        return matcher
    }

    private fun declaredTimezone(tag: String?, text: String): String? {
        if (tag != LogForwarding.FORWARDER_TAG) return null
        return ANCHOR.matchEntire(text)?.groupValues?.get(1)
    }

    private fun utf8(bytes: ByteArray, from: Int, to: Int): String =
        String(bytes, from, to - from, Charsets.UTF_8)

    /** The decodable prefix, and the number of trailing bytes that could not be decoded. */
    private fun decodeUtf8Prefix(bytes: ByteArray, from: Int, to: Int): Pair<String, Int> {
        if (to <= from) return Pair("", 0)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes, from, to - from)
        val output = CharBuffer.allocate(to - from)
        val result = decoder.decode(input, output, true)
        if (result.isError) {
            output.flip()
            return Pair(output.toString(), to - input.position())
        }
        decoder.flush(output)
        output.flip()
        return Pair(output.toString(), 0)
    }
}
