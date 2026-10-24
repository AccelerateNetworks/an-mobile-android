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

import java.io.File
import java.io.IOException
import java.time.Instant
import org.json.JSONObject
import org.linphone.core.tools.Log

/** What the forwarder must remember across runs. Every field is always present, null where it is not meaningful. */
class ForwarderState(
    val cursor: FileId?,
    val declaredTimezone: String?,
    val replay: Replay?
)

/** Written by the transport's outcomes rather than by the run, which is why it is its own document. */
class DispatchState(
    val notBefore: Instant?,
    val failures: Int,
    val outageStart: Instant?,
    val lastOutcome: String?
) {
    companion object {
        val EMPTY = DispatchState(null, 0, null, null)
    }
}

/**
 * Each document is written to a temporary name, fsynced, renamed into place and the parent fsynced,
 * so a crash leaves the previous document or the new one and never half of either.
 */
class DocumentStore(val root: File) {
    companion object {
        private const val STATE = "state.json"
        private const val DISPATCH = "dispatch.json"
        private const val CURSOR_PREFIX = "cursor-"
    }

    init {
        root.mkdirs()
    }

    fun loadState(): ForwarderState {
        val json = read(STATE) ?: return ForwarderState(null, null, null)
        return ForwarderState(
            cursor = json.stringOrNull("cursor")?.let { FileId(it) },
            declaredTimezone = json.stringOrNull("declaredTimezone"),
            replay = json.optJSONObject("replay")?.let { replay ->
                val maxFile = replay.stringOrNull("maxFile") ?: return@let null
                Replay(
                    previous = settingsFrom(replay.optJSONObject("previous")),
                    maxFile = FileId(maxFile),
                    maxOffset = replay.optLong("maxOffset", 0)
                )
            }
        )
    }

    fun saveState(state: ForwarderState) {
        val json = JSONObject()
        json.put("cursor", state.cursor?.value ?: JSONObject.NULL)
        json.put("declaredTimezone", state.declaredTimezone ?: JSONObject.NULL)
        json.put(
            "replay",
            state.replay?.let {
                JSONObject()
                    .put("previous", settingsTo(it.previous))
                    .put("maxFile", it.maxFile.value)
                    .put("maxOffset", it.maxOffset)
            } ?: JSONObject.NULL
        )
        write(STATE, json)
    }

    fun newCursor(file: FileId): Cursor = Cursor(file.asFileName(), file, this)

    fun loadCursor(file: FileId): Cursor? {
        val json = read(cursorName(file)) ?: return null
        val cursor = newCursor(file)
        cursor.restore(
            offset = json.optLong("offset", 0),
            committedOffset = json.optLong("committed", 0),
            successor = json.stringOrNull("successor")?.let { FileId(it) },
            chased = json.optBoolean("chased", false),
            declaredTimezone = json.stringOrNull("declaredTimezone").orEmpty(),
            size = json.optLong("size", 0)
        )
        return cursor
    }

    fun saveCursor(cursor: Cursor) {
        val json = JSONObject()
            .put("file", cursor.file.value)
            .put("offset", cursor.offset)
            .put("committed", cursor.committedOffset)
            .put("successor", cursor.successor?.value ?: JSONObject.NULL)
            .put("chased", cursor.chased)
            .put("declaredTimezone", cursor.declaredTimezone)
            .put("size", cursor.size)
        write(cursorName(cursor.file), json)
    }

    /** Collects cursor documents for files that no longer exist. */
    fun collectCursors(live: Collection<FileId>) {
        val keep = live.map { cursorName(it) }.toSet()
        root.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(CURSOR_PREFIX) && file.name !in keep) {
                file.delete()
            }
        }
    }

    fun loadDispatch(): DispatchState {
        val json = read(DISPATCH) ?: return DispatchState.EMPTY
        return DispatchState(
            notBefore = json.stringOrNull("notBefore")?.let(::instant),
            failures = json.optInt("failures", 0),
            outageStart = json.stringOrNull("outageStart")?.let(::instant),
            lastOutcome = json.stringOrNull("lastOutcome")
        )
    }

    fun saveDispatch(state: DispatchState) {
        val json = JSONObject()
            .put("notBefore", state.notBefore?.toString() ?: JSONObject.NULL)
            .put("failures", state.failures)
            .put("outageStart", state.outageStart?.toString() ?: JSONObject.NULL)
            .put("lastOutcome", state.lastOutcome ?: JSONObject.NULL)
        write(DISPATCH, json)
    }

    private fun cursorName(file: FileId) = "$CURSOR_PREFIX${file.asFileName()}.json"

    /** `optString` yields the string "null" for a JSON null, which every nullable field can be. */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

    private fun instant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (e: Exception) {
        null
    }

    private fun settingsFrom(json: JSONObject?): ForwardSettings {
        if (json == null) return ForwardSettings(Level.None, emptyMap(), emptyMap())
        return ForwardSettings(
            default = Level.threshold(json.optString("default")) ?: Level.None,
            levels = levelMap(json.optJSONObject("levels")),
            tags = levelMap(json.optJSONObject("tags"))
        )
    }

    private fun levelMap(json: JSONObject?): Map<String, Level> {
        if (json == null) return emptyMap()
        val map = mutableMapOf<String, Level>()
        json.keys().forEach { key ->
            Level.threshold(json.optString(key))?.let { map[key] = it }
        }
        return map
    }

    private fun settingsTo(settings: ForwardSettings): JSONObject {
        val levels = JSONObject()
        settings.levels.forEach { (key, value) -> levels.put(key, value.token.lowercase()) }
        val tags = JSONObject()
        settings.tags.forEach { (key, value) -> tags.put(key, value.token.lowercase()) }
        return JSONObject()
            .put("default", settings.default.token.lowercase())
            .put("levels", levels)
            .put("tags", tags)
    }

    private fun read(name: String): JSONObject? {
        val file = File(root, name)
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w("[${LogForwarding.FORWARDER_TAG}] Discarding unreadable document [$name]: $e")
            null
        }
    }

    private fun write(name: String, json: JSONObject) {
        try {
            AtomicFile.write(root, name, json.toString().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.e("[${LogForwarding.FORWARDER_TAG}] Failed to write document [$name]: $e")
        }
    }
}
