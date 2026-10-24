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

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.core.tools.Log

/**
 * Drives the loop and owns delivery policy. A forwarder a [supersede] replaces is invalid from that
 * moment: a run in progress stops where it is rather than draining.
 */
class LogForwarder(
    private val reader: LogReader,
    private val formatter: Formatter,
    private val outbox: Outbox,
    private val dispatcher: Dispatcher,
    private val maxPostBytes: Int,
    private val url: String,
    private val store: DocumentStore,
    private var settings: ForwardSettings,
    private var replay: Replay?,
    private val rebuild: (ForwardSettings, Replay?) -> LogForwarder
) {
    companion object {

        /** Waits out a flush in progress. Do not tune below about 100 ms. */
        private const val SETTLE_INTERVAL_MS = 250L
    }

    @Volatile
    private var invalidated = false

    /** Drains until CaughtUp or stopped. A blank url clears the outbox and returns. */
    suspend fun start() = coroutineScope {
        if (url.isBlank()) {
            outbox.clear()
            return@coroutineScope
        }
        dispatcher.drain()
        outbox.sweep()
        for (dir in outbox.pending()) {
            launch { dispatcher.dispatch(dir) }
        }

        while (isActive && !invalidated) {
            var frame = reader.read(settings, replay) ?: break
            if (!commitAndDispatch(frame, this)) break
            if (frame.status == ReadStatus.CaughtUp) break
            if (frame.status == ReadStatus.HoldingPartial) {
                if (frame.successor == null) delay(SETTLE_INTERVAL_MS)
                frame = reader.readLastElseRead(settings, replay) ?: break
                if (!commitAndDispatch(frame, this)) break
                if (frame.status == ReadStatus.CaughtUp) break
            }
        }
    }

    /**
     * A new forwarder with [Replay] set where [ForwardSettings.requiresReplay] or
     * [Formatter.hasReEncoding] says so. Otherwise this, with [next] replaced.
     */
    fun supersede(next: ForwardSettings): LogForwarder {
        val reEncoding = formatter.hasReEncoding(settings, next)
        if (reEncoding == null && !settings.requiresReplay(next)) {
            settings = next
            return this
        }
        invalidated = true
        val position = reader.position()
        val marker = position?.let { Replay(settings, it.first, it.second, reEncoding) }
        val state = store.loadState()
        store.saveState(ForwarderState(null, state.declaredTimezone, marker))
        reader.close()
        Log.i("[${LogForwarding.FORWARDER_TAG}] Superseding with a replay from the oldest available byte")
        return rebuild(next, marker)
    }

    fun close() = reader.close()

    /** Returns false where the span could not be made durable, so the run must not carry on past it. */
    private suspend fun commitAndDispatch(frame: Frame, scope: CoroutineScope): Boolean {
        if (frame.records.isNotEmpty() || frame.lostBytes != 0L) {
            val rendered = formatter.format(frame)
            val bodies = formatter.pack(rendered, maxPostBytes)
            val dir = try {
                outbox.write(rendered.frameId, bodies)
            } catch (e: IOException) {
                Log.e("[${LogForwarding.FORWARDER_TAG}] Could not make the bodies for frame [${rendered.frameId}] durable: $e")
                frame.cursor.offset = frame.cursor.committedOffset
                return false
            }
            commit(frame)
            scope.launch { dispatcher.dispatch(dir) }
        } else if (frame.consumedSize > 0) {
            commit(frame)
        }
        return true
    }

    private fun commit(frame: Frame) {
        frame.cursor.commit(
            frame.endOffset,
            frame.successor,
            frame.status == ReadStatus.CaughtUp,
            frame.declaredTimezone
        )
        val expired = replay?.let { !reader.replayCovers(it, frame.cursor.file, frame.endOffset) } == true
        val state = store.loadState()
        if (state.declaredTimezone != frame.declaredTimezone || expired) {
            store.saveState(
                ForwarderState(
                    state.cursor,
                    frame.declaredTimezone,
                    if (expired) null else state.replay
                )
            )
        }
        if (expired) {
            Log.i("[${LogForwarding.FORWARDER_TAG}] Replay reached its bound, resuming normal operation")
            replay = null
        }
    }
}
