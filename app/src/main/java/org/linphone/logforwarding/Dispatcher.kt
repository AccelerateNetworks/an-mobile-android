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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.linphone.core.tools.Log

/**
 * The platform transport. The outbox is our durability; this is the platform's in-flight queue, and
 * Android has none. A stored body is the request body and nothing else.
 */
interface Dispatcher {
    /** Outcomes the platform buffered while we were not running. No-op on Android. */
    fun drain()

    /** Bodies the platform still holds. Always empty on Android. */
    fun inFlight(): List<FileId>

    /** Hands over every body in the frame, applying outcomes against the outbox per Contract 4. */
    suspend fun dispatch(dir: File)
}

class HttpDispatcher(
    private val outbox: Outbox,
    private val destination: String,
    private val store: DocumentStore,
    private val maxBackoff: Long,
    private val hasNetwork: () -> Boolean,
    private val hasBudget: () -> Boolean
) : Dispatcher {
    companion object {
        private const val BASE_BACKOFF_MS = 10_000L
        private const val JITTER = 0.2
        private const val INACTIVITY_SECONDS = 60L
        private const val CALL_SECONDS = 120L

        private val JSON = "application/json".toMediaType()

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(INACTIVITY_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(INACTIVITY_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }

    private val mutex = Mutex()
    private var state: DispatchState = store.loadDispatch()

    override fun drain() {}

    override fun inFlight(): List<FileId> = emptyList()

    override suspend fun dispatch(dir: File) {
        for (body in outbox.bodies(dir)) {
            if (!hasNetwork()) return
            if (!hasBudget()) return
            if (!allowed()) return
            post(body)
        }
    }

    private suspend fun post(body: File) {
        val request = Request.Builder()
            .url(destination)
            .header("Content-Encoding", "gzip")
            .post(body.asRequestBody(JSON))
            .build()

        val outcome = try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    Outcome(response.code, response.header("Retry-After"))
                }
            }
        } catch (e: IOException) {
            Outcome(null, null, e.toString())
        }
        apply(body, outcome)
    }

    private suspend fun apply(body: File, outcome: Outcome) {
        val code = outcome.code
        when {
            code == null -> keep(outcome)
            code in 200..299 -> {
                outbox.drop(body, InodeUtils.idOf(body))
                succeed(code.toString())
            }
            code == 413 -> {
                Log.w("[${LogForwarding.FORWARDER_TAG}] Endpoint refused [${body.name}] as too large, which means max_post_bytes disagrees with its caps")
                val split = outbox.split(body)
                succeed(code.toString())
                split?.let { dispatch(it) }
            }
            code == 429 -> keep(outcome)
            code in 400..499 -> {
                Log.w("[${LogForwarding.FORWARDER_TAG}] Abandoning ${describe(body)}: endpoint answered [$code]")
                outbox.drop(body, InodeUtils.idOf(body))
                succeed(code.toString())
            }
            else -> keep(outcome)
        }
    }

    /** A backoff already in force is not updated by further outcomes. */
    private suspend fun keep(outcome: Outcome) = mutex.withLock {
        val now = Instant.now()
        val current = state
        if (current.notBefore != null && now.isBefore(current.notBefore)) return@withLock
        val failures = current.failures + 1
        val delay = outcome.retryAfterMillis() ?: backoffFor(failures)
        state = DispatchState(
            notBefore = now.plusMillis(delay),
            failures = failures,
            outageStart = current.outageStart ?: now,
            lastOutcome = outcome.describe()
        )
        store.saveDispatch(state)
    }

    private suspend fun succeed(code: String) = mutex.withLock {
        val now = Instant.now()
        val current = state
        if (current.notBefore != null && now.isBefore(current.notBefore)) return@withLock
        if (current.failures > 0 && backoffFor(current.failures) >= maxBackoff) {
            reportOutage(current, now)
        }
        state = DispatchState(null, 0, null, code)
        store.saveDispatch(state)
    }

    private fun reportOutage(outage: DispatchState, now: Instant) {
        val since = outage.outageStart?.let { Duration.between(it, now).seconds } ?: 0
        val pending = outbox.pending().sumOf { outbox.bodies(it).size }
        Log.w(
            "[${LogForwarding.FORWARDER_TAG}] delivery recovered after ${since}s, " +
                "${outage.failures} hand-overs failed, last outcome [${outage.lastOutcome}], " +
                "$pending bodies still pending"
        )
    }

    private suspend fun allowed(): Boolean = mutex.withLock {
        val notBefore = state.notBefore ?: return@withLock true
        !Instant.now().isBefore(notBefore)
    }

    private fun backoffFor(failures: Int): Long {
        val exponent = (failures - 1).coerceIn(0, 30)
        val base = (BASE_BACKOFF_MS shl exponent).coerceAtMost(maxBackoff)
        val jitter = (base * JITTER * (Random.nextDouble() * 2 - 1)).toLong()
        return (base + jitter).coerceAtMost(maxBackoff)
    }

    /** Cursor range and record count, for a body that is being abandoned. */
    private fun describe(body: File): String {
        return try {
            val raw = GZIPInputStream(body.inputStream()).use { it.readBytes() }
            val records = JSONObject(String(raw, Charsets.UTF_8))
                .getJSONArray("resourceLogs").getJSONObject(0)
                .getJSONArray("scopeLogs").getJSONObject(0)
                .getJSONArray("logRecords")
            val attributes = records.getJSONObject(0).optJSONArray("attributes")
            var file = "?"
            var from = "?"
            var to = "?"
            for (index in 0 until (attributes?.length() ?: 0)) {
                val entry = attributes!!.getJSONObject(index)
                val value = entry.getJSONObject("value").optString("stringValue")
                when (entry.optString("key")) {
                    "cursor_file" -> file = value
                    "cursor_from" -> from = value
                    "cursor_to" -> to = value
                }
            }
            "${records.length()} records over $file $from-$to"
        } catch (e: Exception) {
            "body [${body.name}]"
        }
    }

    private class Outcome(val code: Int?, val retryAfter: String?, val error: String? = null) {
        fun retryAfterMillis(): Long? = retryAfter?.trim()?.toLongOrNull()?.times(1000)

        fun describe(): String = code?.toString() ?: error.orEmpty()
    }
}
