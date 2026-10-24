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

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AtomicFile {
    /** Write, fsync, rename into place, fsync the parent. */
    @Throws(IOException::class)
    fun write(dir: File, name: String, content: ByteArray) {
        dir.mkdirs()
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(content)
            stream.flush()
            stream.fd.sync()
        }
        val target = File(dir, name)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not rename [${temp.absolutePath}] onto [${target.absolutePath}]")
        }
        fsync(dir)
    }

    @Throws(IOException::class)
    fun fsync(dir: File) {
        try {
            val descriptor = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        } catch (e: ErrnoException) {
            throw IOException("Could not fsync [${dir.absolutePath}]", e)
        }
    }

    fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
