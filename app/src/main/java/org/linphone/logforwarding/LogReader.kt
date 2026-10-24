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
import org.linphone.core.tools.Log

/**
 * @param declaredTimezone the offset in force at the first byte read: the state entry's value, or
 * the device's current offset where none is persisted. A loaded cursor's own value wins over it.
 * @param softLimit bounds raw record bytes KEPT per read. -1 means unbounded.
 */
class LogReader(
    private val dir: File,
    private val prefix: String,
    private val extractor: Extractor,
    declaredTimezone: String,
    private val softLimit: Long,
    private val store: DocumentStore
) {
    companion object {
        private const val INITIAL_WINDOW = 256 * 1024
        private const val MAX_WINDOW = 32 * 1024 * 1024
        private const val MAX_CHASE_SPAN = 32L * 1024 * 1024
        private const val UNKNOWN_LOSS = -1L
    }

    private var cursor: Cursor? = null
    private var handle: LogHandle? = null
    private var pendingLoss = 0L

    /** The offset in force at the read position. */
    var timezoneInForce: String = declaredTimezone
        private set

    init {
        val state = store.loadState()
        val restored = state.cursor?.let { store.loadCursor(it) }
        if (restored != null) {
            cursor = restored
            if (restored.declaredTimezone.isNotEmpty()) {
                timezoneInForce = restored.declaredTimezone
            }
        }
    }

    /** Null where there is no log file to read from at all. */
    fun read(settings: ForwardSettings, replay: Replay?): Frame? =
        readFrame(settings, replay)

    /**
     * The chase. The span from the read position to EOF, when it holds a single header, comes back
     * as a one-record frame. Otherwise it defers to [read].
     */
    fun readLastElseRead(settings: ForwardSettings, replay: Replay?): Frame? {
        prepare()
        val open = handle ?: return readFrame(settings, replay)
        val current = cursor ?: return readFrame(settings, replay)
        val eof = open.size()
        current.observe(eof)
        val span = eof - current.offset
        if (span <= 0 || span > MAX_CHASE_SPAN) return readFrame(settings, replay)

        val window = readWindow(open, current.offset, span.toInt()) ?: return readFrame(settings, replay)
        val record = extractor.next(window, current.offset, true)
        if (record == null || record.endOffset < eof) return readFrame(settings, replay)

        record.declaredTimezone?.let { timezoneInForce = it }
        record.timezone = timezoneInForce
        if (record.orphanedBytes > 0) reportOrphan(record, current)

        val successor = observeSuccessor(current)
        val kept = forwards(record, settings, replayBound(replay, current))
        val status = if (successor != null) ReadStatus.FileComplete else ReadStatus.CaughtUp
        return Frame(
            status = status,
            records = if (kept) listOf(record) else emptyList(),
            cursor = current,
            successor = successor,
            startOffset = current.offset,
            endOffset = eof,
            declaredTimezone = timezoneInForce,
            payloadSize = if (kept) record.endOffset - record.startOffset else 0,
            consumedSize = eof - current.offset,
            filteredCount = if (kept) 0 else 1,
            lostBytes = takeLoss(),
            firstRecord = record
        ).also { current.offset = eof }
    }

    private fun readFrame(settings: ForwardSettings, replay: Replay?): Frame? {
        prepare()
        val open = handle ?: return null
        val current = cursor ?: return null

        val startOffset = current.offset
        val bound = replayBound(replay, current)
        val records = mutableListOf<Record>()
        var firstRecord: Record? = null
        var filtered = 0L
        var payload = 0L
        var offset = startOffset
        var windowSize = INITIAL_WINDOW
        var status = ReadStatus.CaughtUp

        val eof = open.size()
        current.observe(eof)
        val superseded = observeSuccessor(current) != null

        while (offset < eof) {
            if (softLimit >= 0 && payload >= softLimit) {
                status = ReadStatus.MoreAvailable
                break
            }
            val length = minOf(windowSize.toLong(), eof - offset).toInt()
            val window = readWindow(open, offset, length)
            if (window == null) {
                status = ReadStatus.CaughtUp
                break
            }
            val atFileEnd = window.end >= eof
            var position = offset
            while (softLimit < 0 || payload < softLimit) {
                val record = extractor.next(window, position, false) ?: break
                record.declaredTimezone?.let { timezoneInForce = it }
                record.timezone = timezoneInForce
                if (firstRecord == null) firstRecord = record
                if (record.orphanedBytes > 0) reportOrphan(record, current)
                if (forwards(record, settings, bound)) {
                    records.add(record)
                    payload += record.endOffset - record.startOffset
                } else {
                    filtered++
                }
                position = record.endOffset
            }
            if (position == offset) {
                if (atFileEnd) {
                    status = when {
                        extractor.next(window, offset, true) != null -> ReadStatus.HoldingPartial
                        superseded -> {
                            reportTrailingOrphan(current, offset, eof)
                            offset = eof
                            ReadStatus.FileComplete
                        }
                        else -> ReadStatus.CaughtUp
                    }
                    break
                }
                if (windowSize >= MAX_WINDOW) break
                windowSize = minOf(windowSize * 2, MAX_WINDOW)
                continue
            }
            offset = position
            windowSize = INITIAL_WINDOW
            if (atFileEnd) {
                status = when {
                    offset < eof -> ReadStatus.HoldingPartial
                    superseded -> ReadStatus.FileComplete
                    else -> ReadStatus.CaughtUp
                }
                break
            }
        }
        if (offset >= eof && status == ReadStatus.CaughtUp && superseded) {
            status = ReadStatus.FileComplete
        }

        current.offset = offset
        return Frame(
            status = status,
            records = records,
            cursor = current,
            successor = observeSuccessor(current),
            startOffset = startOffset,
            endOffset = offset,
            declaredTimezone = timezoneInForce,
            payloadSize = payload,
            consumedSize = offset - startOffset,
            filteredCount = filtered,
            lostBytes = takeLoss(),
            firstRecord = firstRecord
        )
    }

    /**
     * Resolves the one inode the reader still needs, moving on where the cursor is complete and
     * restarting where it is gone. Any loss it finds is held until a frame carries it.
     */
    private fun prepare() {
        repeat(2) {
            val current = cursor
            if (current == null) {
                restart(0)
                return
            }
            if (current.complete) {
                val successor = current.successor
                val opened = successor?.let { InodeUtils.openById(dir, prefix, it) }
                if (opened != null) {
                    adopt(opened)
                    return
                }
                Log.w("[${LogForwarding.FORWARDER_TAG}] Successor [$successor] of [${current.file}] was destroyed before it was read")
                restart(UNKNOWN_LOSS)
                return
            }
            val opened = openCurrent(current)
            if (opened != null) {
                current.observe(opened.size())
                if (!current.complete) return
                return@repeat
            }
            val unread = current.size - current.committedOffset
            Log.w("[${LogForwarding.FORWARDER_TAG}] File [${current.file}] is gone with [$unread] bytes never read")
            restart(if (unread > 0) unread else UNKNOWN_LOSS)
            return
        }
    }

    private fun openCurrent(current: Cursor): LogHandle? {
        val open = handle
        if (open != null && open.id == current.file) return open
        open?.close()
        val fresh = InodeUtils.openById(dir, prefix, current.file)
        handle = fresh
        return fresh
    }

    /** Restarts at the oldest available file, offset 0. */
    private fun restart(loss: Long) {
        addLoss(loss)
        handle?.close()
        handle = null
        cursor = null
        val path = InodeUtils.candidates(dir, prefix).firstOrNull { it.isFile } ?: return
        val opened = InodeUtils.open(path) ?: return
        adopt(opened)
    }

    /** A new cursor's document is written before the state entry points at it. */
    private fun adopt(opened: LogHandle) {
        if (handle !== opened) handle?.close()
        handle = opened
        val fresh = store.newCursor(opened.id)
        fresh.restore(0, 0, null, false, timezoneInForce, opened.size())
        store.saveCursor(fresh)
        val state = store.loadState()
        store.saveState(ForwarderState(opened.id, state.declaredTimezone ?: timezoneInForce, state.replay))
        cursor = fresh
        store.collectCursors(InodeUtils.candidates(dir, prefix).mapNotNull { InodeUtils.idOf(it) })
    }

    /** The successor is the next index up, which is also the next file in age order. */
    private fun observeSuccessor(current: Cursor): FileId? {
        val candidates = InodeUtils.candidates(dir, prefix)
        val index = candidates.indexOfFirst { InodeUtils.idOf(it) == current.file }
        if (index < 0 || index + 1 >= candidates.size) return current.successor
        return InodeUtils.idOf(candidates[index + 1]) ?: current.successor
    }

    /** Below this offset the replay decides; at or above it the settings do. */
    private fun replayBound(replay: Replay?, current: Cursor): Pair<Replay, Long>? {
        if (replay == null) return null
        if (InodeUtils.find(dir, prefix, replay.maxFile) == null) return null
        val bound = if (current.file == replay.maxFile) replay.maxOffset else Long.MAX_VALUE
        return Pair(replay, bound)
    }

    private fun forwards(record: Record, settings: ForwardSettings, bound: Pair<Replay, Long>?): Boolean {
        if (bound != null && record.startOffset < bound.second) {
            return bound.first.forwards(record, settings)
        }
        return settings.accepts(record)
    }

    private fun readWindow(open: LogHandle, offset: Long, length: Int): Window? {
        if (length <= 0) return null
        val buffer = ByteArray(length)
        val read = open.readAt(offset, buffer, length)
        if (read <= 0) return null
        return Window(open, offset, if (read == length) buffer else buffer.copyOf(read))
    }

    private fun reportTrailingOrphan(current: Cursor, from: Long, to: Long) {
        Log.w(
            "[${LogForwarding.FORWARDER_TAG}] discarded ${to - from} headerless bytes " +
                "at $from-$to closing ${current.file}, chased commit: ${current.chased}"
        )
    }

    private fun reportOrphan(record: Record, current: Cursor) {
        val from = record.startOffset - record.orphanedBytes
        Log.w(
            "[${LogForwarding.FORWARDER_TAG}] discarded ${record.orphanedBytes} headerless bytes " +
                "at $from-${record.startOffset} in ${current.file}, chased commit: ${current.chased}"
        )
    }

    private fun addLoss(loss: Long) {
        pendingLoss = when {
            loss == 0L -> pendingLoss
            pendingLoss == 0L -> loss
            loss == UNKNOWN_LOSS || pendingLoss == UNKNOWN_LOSS -> UNKNOWN_LOSS
            else -> pendingLoss + loss
        }
    }

    private fun takeLoss(): Long {
        val loss = pendingLoss
        pendingLoss = 0
        return loss
    }

    /** Where the reader has committed: the file and the offset a replay bound is taken from. */
    fun position(): Pair<FileId, Long>? = cursor?.let { Pair(it.file, it.committedOffset) }

    fun replayCovers(replay: Replay, file: FileId, offset: Long): Boolean =
        replay.covers(dir, prefix, file, offset)

    fun close() {
        handle?.close()
        handle = null
    }
}
