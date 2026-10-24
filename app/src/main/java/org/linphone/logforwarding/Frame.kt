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

enum class ReadStatus { CaughtUp, HoldingPartial, MoreAvailable, FileComplete }

class Frame(
    val status: ReadStatus,
    /** Kept only. */
    val records: List<Record>,
    /** The cursor this frame was read from; a commit targets this one. */
    val cursor: Cursor,
    /** The next file's inode where the reader observed one by the end of this read. */
    val successor: FileId?,
    /** Absolute; renders as cursor_from. */
    val startOffset: Long,
    /** Absolute; renders as cursor_to, and what a commit advances to. */
    val endOffset: Long,
    /** The offset in force at [endOffset], after every extracted record is accounted for. */
    val declaredTimezone: String,
    /** Sum of kept record spans; what softLimit bounds. */
    val payloadSize: Long,
    /** Everything traversed, filtered and orphaned included. */
    val consumedSize: Long,
    val filteredCount: Long,
    /**
     * Destroyed by rotation before we read them. -1 where a file was destroyed and the count is
     * not knowable.
     */
    val lostBytes: Long,
    /** The first record this frame traversed, kept or filtered; what the meta record is stamped at. */
    val firstRecord: Record?
)
