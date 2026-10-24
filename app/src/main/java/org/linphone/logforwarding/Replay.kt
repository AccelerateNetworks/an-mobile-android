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
import java.security.MessageDigest

/**
 * [maxFile] and [maxOffset] together name where the previous forwarder had committed; an offset
 * alone means nothing across a rotation. [isReEncoded] is a function and is not persisted.
 */
class Replay(
    val previous: ForwardSettings,
    val maxFile: FileId,
    val maxOffset: Long,
    val isReEncoded: ((Record) -> Boolean)? = null
) {
    fun forwards(record: Record, settings: ForwardSettings): Boolean {
        if (!settings.accepts(record)) return false
        return !previous.accepts(record) || isReEncoded?.invoke(record) == true
    }

    /** Live until the cursor reaches the bound, or until [maxFile] is gone. */
    fun covers(dir: File, prefix: String, cursorFile: FileId, offset: Long): Boolean {
        if (InodeUtils.find(dir, prefix, maxFile) == null) return false
        if (cursorFile == maxFile) return offset < maxOffset
        return true
    }

    fun sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(previous.sha256())
        return digest.digest("$maxFile\n$maxOffset".toByteArray(Charsets.UTF_8))
    }
}
