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

/**
 * Bound to ONE file. [successor] is a forward pointer, not a second position.
 */
class Cursor(
    val id: String,
    val file: FileId,
    private val store: DocumentStore
) {
    /** Read position. */
    var offset: Long = 0

    /** Last durably captured byte: delivered or sitting in the outbox. Not "acknowledged". */
    var committedOffset: Long = 0
        private set

    /** The next file's inode, once one is observed. */
    var successor: FileId? = null
        private set

    /**
     * The committed offset was reached by taking a record against a window end rather than against
     * a following header.
     */
    var chased: Boolean = false
        private set

    /** The offset in force at [committedOffset], written with it so the two cannot disagree. */
    var declaredTimezone: String = ""
        private set

    /** The tracked file's end as last observed. Readable after the file itself is gone. */
    var size: Long = 0
        private set

    /** A successor exists and the committed offset is at EOF. Only this permits the reader to move on. */
    val complete: Boolean
        get() = successor != null && committedOffset >= size

    fun observe(eof: Long) {
        if (eof > size) size = eof
    }

    /** One durable write; [complete] follows from it. */
    fun commit(offset: Long, successor: FileId?, chased: Boolean, declaredTimezone: String) {
        this.offset = offset
        this.committedOffset = offset
        this.successor = successor
        this.chased = chased
        this.declaredTimezone = declaredTimezone
        observe(offset)
        store.saveCursor(this)
    }

    fun restore(
        offset: Long,
        committedOffset: Long,
        successor: FileId?,
        chased: Boolean,
        declaredTimezone: String,
        size: Long
    ) {
        this.offset = offset
        this.committedOffset = committedOffset
        this.successor = successor
        this.chased = chased
        this.declaredTimezone = declaredTimezone
        this.size = size
    }
}
