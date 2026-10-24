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
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Opaque: equality and rendering only. [value] is never parsed and never compared across platforms.
 */
@JvmInline
value class FileId(val value: String) {
    override fun toString(): String = value

    /** Stable, filesystem-safe form of [value], for naming a document after the file it tracks. */
    fun asFileName(): String = value.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
}

/** An open descriptor on a log file. Rotation cannot change what it is a view of. */
class LogHandle(val id: FileId, private val raf: RandomAccessFile) {
    fun size(): Long = raf.length()

    fun readAt(offset: Long, buffer: ByteArray, length: Int): Int {
        raf.seek(offset)
        var read = 0
        while (read < length) {
            val n = raf.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    fun close() {
        try {
            raf.close()
        } catch (e: IOException) {
        }
    }
}

/**
 * File APIs address by path; this design addresses by identity, because rotation renames files out
 * from under a path.
 */
object InodeUtils {
    private const val OPEN_ATTEMPTS = 4

    /** Null when the path does not exist. */
    fun idOf(path: File): FileId? {
        return try {
            val stat = Os.stat(path.absolutePath)
            render(stat.st_dev, stat.st_ino)
        } catch (e: ErrnoException) {
            null
        }
    }

    fun idOf(descriptor: FileDescriptor): FileId {
        val stat = Os.fstat(descriptor)
        return render(stat.st_dev, stat.st_ino)
    }

    /** The paths that can hold the collection, oldest first. */
    fun candidates(dir: File, prefix: String): List<File> =
        listOf(File(dir, "${prefix}1.log"), File(dir, "${prefix}2.log"))

    /** The path currently holding [id], or null if it is gone. */
    fun find(dir: File, prefix: String, id: FileId): File? =
        candidates(dir, prefix).firstOrNull { idOf(it) == id }

    /** Null once [id] is gone. */
    fun openById(dir: File, prefix: String, id: FileId): LogHandle? {
        repeat(OPEN_ATTEMPTS) {
            val path = find(dir, prefix, id) ?: return null
            val handle = open(path) ?: return@repeat
            if (handle.id == id) return handle
            handle.close()
        }
        return null
    }

    fun open(path: File): LogHandle? {
        return try {
            val raf = RandomAccessFile(path, "r")
            try {
                LogHandle(idOf(raf.fd), raf)
            } catch (e: ErrnoException) {
                raf.close()
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun render(device: Long, inode: Long): FileId = FileId("(dev=$device,ino=$inode)")
}
