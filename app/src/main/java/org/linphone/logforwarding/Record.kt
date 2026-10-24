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
 * Every length and position here is a byte count, never a character count.
 */
class Record(
    /** Verbatim, "2026-09-28 14:30:12:345"; no zone applied. */
    val localTime: String,
    val process: String,
    val subsystem: String,
    /** Lowercased with non-alphanumerics removed. Promoted, never removed from [text]. */
    val tag: String?,
    val level: Level,
    /** Non-null when this record is a tz anchor: the offset it declares. */
    val declaredTimezone: String?,
    /** UTF-8, header stripped, ending at the first undecodable byte. */
    val text: String,
    /** Bytes dropped from the end because they could not be decoded; 0 normally. */
    val binaryBytes: Long,
    /** Bytes immediately below [startOffset] that carried no header; 0 normally. */
    val orphanedBytes: Long,
    val startOffset: Long,
    /** Absolute; what a commit advances to. */
    val endOffset: Long
) {
    /** The offset in force here, stamped by the reader. Not a byte offset. */
    var timezone: String = ""
}

/** A contiguous positional read; the reader owns it and sizes it. */
class Window(
    val handle: LogHandle,
    val start: Long,
    val bytes: ByteArray
) {
    val end: Long = start + bytes.size

    /** One byte to one character, so a match offset in it is a byte offset. */
    val latin1: String by lazy { String(bytes, Charsets.ISO_8859_1) }
}

/** Compiled in; owns the record's shape. */
interface Extractor {
    /**
     * The next whole record at or after [from], extracted and decoded. A record is bounded by the
     * following header; where the match ran to the window's end instead, [acceptWindowEnd] decides
     * whether that bounds it. Null otherwise.
     */
    fun next(window: Window, from: Long, acceptWindowEnd: Boolean): Record?
}
