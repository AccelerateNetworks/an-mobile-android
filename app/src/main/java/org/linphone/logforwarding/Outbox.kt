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
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.tools.Log

/**
 * Layout, relative to [root]. The content hash is of the body as handed in, not of the gzip:
 *   temp-<frame-id>/<content-hash>.gz
 *   temp-split-<parent-hash>/<content-hash>.gz
 *   <frame-id>/<content-hash>.gz
 *   split-<parent-hash>/<content-hash>.gz
 */
class Outbox(private val root: File) {
    companion object {
        private const val TEMP = "temp-"
        private const val SPLIT = "split-"
        private const val BODY_SUFFIX = ".gz"
    }

    init {
        root.mkdirs()
    }

    /**
     * The frame's directory. Each body is gzipped into a temp- directory and fsynced, then that
     * directory is renamed to the frame id and the parent fsynced, so the whole set appears at once.
     */
    @Throws(IOException::class)
    fun write(frameId: String, bodies: List<String>): File = stage(frameId, bodies.map { it.toByteArray(Charsets.UTF_8) })

    /** Unordered; every directory without the temp- prefix. */
    fun pending(): List<File> =
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(TEMP) }.orEmpty()

    /** Deletes every temp- directory, at run start where no write can be in progress. */
    fun sweep() {
        root.listFiles()?.forEach {
            if (it.isDirectory && it.name.startsWith(TEMP)) AtomicFile.deleteRecursively(it)
        }
    }

    /** Deletes every directory, temp- or not. The policy that calls it is the forwarder's. */
    fun clear() {
        root.listFiles()?.forEach { AtomicFile.deleteRecursively(it) }
    }

    fun bodies(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(BODY_SUFFIX) }.orEmpty()

    /**
     * One body, on any terminal outcome; takes the directory with its last body. Idempotent, and
     * tolerant of the body or the directory already being gone.
     */
    fun drop(body: File, id: FileId?) {
        val parent = body.parentFile
        if (body.isFile && (id == null || InodeUtils.idOf(body) == id)) {
            body.delete()
        }
        if (parent != null && parent.isDirectory && bodies(parent).isEmpty()) {
            AtomicFile.deleteRecursively(parent)
            runCatching { AtomicFile.fsync(root) }
        }
    }

    /**
     * 413 only. Decompresses, halves logRecords, keeps Resource, and drops the original once the
     * new directory is in place. Null where a single-record body cannot split.
     */
    fun split(body: File): File? {
        val parentHash = body.name.removeSuffix(BODY_SUFFIX)
        val raw = try {
            GZIPInputStream(body.inputStream()).use { it.readBytes() }
        } catch (e: IOException) {
            Log.e("[${LogForwarding.FORWARDER_TAG}] Could not read [${body.absolutePath}] to split it: $e")
            return null
        }

        val halves = halve(String(raw, Charsets.UTF_8)) ?: run {
            Log.w("[${LogForwarding.FORWARDER_TAG}] Body [${body.name}] holds a single record and cannot be split, dropping it")
            drop(body, null)
            return null
        }

        return try {
            val dir = stage("$SPLIT$parentHash", halves.map { it.toByteArray(Charsets.UTF_8) })
            drop(body, null)
            dir
        } catch (e: IOException) {
            Log.e("[${LogForwarding.FORWARDER_TAG}] Could not stage the split of [${body.name}]: $e")
            null
        }
    }

    @Throws(IOException::class)
    private fun stage(name: String, bodies: List<ByteArray>): File {
        val temp = File(root, "$TEMP$name")
        AtomicFile.deleteRecursively(temp)
        temp.mkdirs()
        for (body in bodies) {
            val file = File(temp, contentHash(body) + BODY_SUFFIX)
            FileOutputStream(file).use { stream ->
                // finish() writes the trailer without closing the stream beneath it, so the
                // descriptor is still open to sync. Closing the gzip would close it first.
                GZIPOutputStream(stream).use { gzip ->
                    gzip.write(body)
                    gzip.finish()
                    stream.flush()
                    stream.fd.sync()
                }
            }
        }
        AtomicFile.fsync(temp)
        val target = File(root, name)
        AtomicFile.deleteRecursively(target)
        if (!temp.renameTo(target)) {
            AtomicFile.deleteRecursively(temp)
            throw IOException("Could not rename [${temp.absolutePath}] onto [${target.absolutePath}]")
        }
        AtomicFile.fsync(root)
        return target
    }

    /** Null where the body holds a single logRecord. */
    private fun halve(body: String): List<String>? {
        return try {
            val json = JSONObject(body)
            val resourceLogs = json.getJSONArray("resourceLogs").getJSONObject(0)
            val scopeLogs = resourceLogs.getJSONArray("scopeLogs").getJSONObject(0)
            val records = scopeLogs.getJSONArray("logRecords")
            if (records.length() < 2) return null
            val middle = records.length() / 2
            listOf(slice(json, 0, middle), slice(json, middle, records.length()))
        } catch (e: Exception) {
            Log.e("[${LogForwarding.FORWARDER_TAG}] Could not parse a body to split it: $e")
            null
        }
    }

    private fun slice(body: JSONObject, from: Int, to: Int): String {
        val json = JSONObject(body.toString())
        val scopeLogs = json.getJSONArray("resourceLogs").getJSONObject(0)
            .getJSONArray("scopeLogs").getJSONObject(0)
        val records = scopeLogs.getJSONArray("logRecords")
        val kept = JSONArray()
        for (index in from until to) kept.put(records.get(index))
        scopeLogs.put("logRecords", kept)
        return json.toString()
    }

    private fun contentHash(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).toHex()

    fun size(): Long {
        var total = 0L
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory) bodies(dir).forEach { total += it.length() }
        }
        return total
    }
}
