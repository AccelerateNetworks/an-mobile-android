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

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Config
import org.linphone.core.tools.Log

/**
 * Owns the two things that start a run, the foreground loop and the WorkManager request, and the
 * in-process guard that keeps only one of them running at a time.
 */
object LogForwarding {
    /** The app layer's subsystem, replacing the SDK wrapper's `linphone-android` default. */
    const val LOG_DOMAIN = "linphone-app"

    /** Everything the forwarder writes to the log opens with it, and the meta record carries it. */
    const val FORWARDER_TAG = "forwarder"

    const val ANCHOR_MESSAGE = "tz anchor"
    private const val SERVICE_NAME = "an-mobile"
    private const val PLATFORM = "android"
    private const val LOG_PREFIX = "linphone"
    private const val DIRECTORY = "log-forwarding"
    private const val OUTBOX = "outbox"
    private const val UNIQUE_PERIODIC = "an-log-forwarding-periodic"
    private const val UNIQUE_ONE_TIME = "an-log-forwarding-once"

    /** WorkManager documents ten minutes: a hard limit through Android 11, a worst case after it. */
    private const val WORKER_BUDGET_MS = 10 * 60 * 1000L
    private const val CALL_TIMEOUT_MS = 2 * 60 * 1000L
    private const val MAX_INITIAL_DELAY_MS = 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runLock = Mutex()

    private var context: Context? = null
    private var running: LogForwarder? = null
    private var activeSettings: ForwardSettings? = null
    private var foregroundJob: Job? = null

    private val timeZoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            writeAnchor()
        }
    }

    fun setup(application: Context) {
        context = application.applicationContext
        ContextCompat.registerReceiver(
            application.applicationContext,
            timeZoneReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        schedule()
    }

    /** Executes runs with a wait of foreground_interval between them, under no scheduler budget. */
    fun onForeground() {
        if (currentConfig()?.url.isNullOrBlank()) return
        foregroundJob?.cancel()
        writeAnchor()
        foregroundJob = scope.launch {
            while (isActive) {
                run(startedAt = null)
                delay(currentConfig()?.foregroundInterval ?: return@launch)
            }
        }
    }

    /** Leaving the foreground ends the loop; a periodic request cannot be asked to run early. */
    fun onBackground() {
        foregroundJob?.cancel()
        foregroundJob = null
        enqueueOneTime()
    }

    /** liblinphone re-reads the config in place, so this is what puts new settings into effect. */
    fun onProvisioningApplied() {
        scope.launch {
            val config = currentConfig() ?: return@launch
            val previous = activeSettings
            if (previous != null && previous != config.settings) {
                val target = running ?: build(config, previous, replayFromState(), null)
                target?.supersede(config.settings)?.close()
            }
            activeSettings = config.settings
            schedule()
        }
    }

    suspend fun runBackground() = run(startedAt = SystemClock.elapsedRealtime())

    private suspend fun run(startedAt: Long?) {
        if (!runLock.tryLock()) {
            Log.i("[$FORWARDER_TAG] A run is already in progress, skipping this one")
            return
        }
        try {
            if (startedAt != null) writeAnchor()
            val config = currentConfig() ?: return
            val instance = build(config, config.settings, replayFromState(), startedAt) ?: return
            activeSettings = config.settings
            running = instance
            try {
                instance.start()
            } finally {
                running = null
                instance.close()
            }
        } catch (e: Exception) {
            Log.e("[$FORWARDER_TAG] Run failed: $e")
        } finally {
            runLock.unlock()
        }
    }

    private fun build(
        config: ForwarderConfig,
        settings: ForwardSettings,
        replay: Replay?,
        startedAt: Long?
    ): LogForwarder? {
        val application = context ?: return null
        val store = store() ?: return null
        val outbox = Outbox(File(application.filesDir, "$DIRECTORY/$OUTBOX"))
        val reader = LogReader(
            dir = application.filesDir,
            prefix = LOG_PREFIX,
            extractor = LogRecordExtractor(),
            declaredTimezone = store.loadState().declaredTimezone ?: currentOffset(),
            softLimit = config.softLimit,
            store = store
        )
        val dispatcher = HttpDispatcher(
            outbox = outbox,
            destination = config.url,
            store = store,
            maxBackoff = config.maxBackoff,
            hasNetwork = { startedAt != null || hasNetwork() },
            hasBudget = {
                startedAt == null ||
                    SystemClock.elapsedRealtime() - startedAt < WORKER_BUDGET_MS - CALL_TIMEOUT_MS
            }
        )
        return LogForwarder(
            reader = reader,
            formatter = OtlpFormatter(identity(), settings, replay),
            outbox = outbox,
            dispatcher = dispatcher,
            maxPostBytes = config.maxPostBytes,
            url = config.url,
            store = store,
            settings = settings,
            replay = replay,
            rebuild = { next, marker ->
                build(config, next, marker, startedAt) ?: error("Log forwarding is not configured")
            }
        )
    }

    private fun schedule() {
        val application = context ?: return
        val config = currentConfig() ?: return
        if (config.url.isBlank()) {
            WorkManager.getInstance(application).cancelUniqueWork(UNIQUE_PERIODIC)
            return
        }
        val interval = config.backgroundInterval
            .coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
        val request = PeriodicWorkRequestBuilder<LogForwardingWorker>(interval, TimeUnit.MILLISECONDS)
            .setConstraints(constraints())
            .setInitialDelay(
                Random.nextLong(minOf(interval, MAX_INITIAL_DELAY_MS)),
                TimeUnit.MILLISECONDS
            )
            .build()
        WorkManager.getInstance(application).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun enqueueOneTime() {
        val application = context ?: return
        if (currentConfig()?.url.isNullOrBlank()) return
        val request = OneTimeWorkRequestBuilder<LogForwardingWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(application).enqueueUniqueWork(
            UNIQUE_ONE_TIME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Reaches the file whenever the process is alive, Core or no Core. */
    private fun writeAnchor() {
        Log.i("[$FORWARDER_TAG] $ANCHOR_MESSAGE ${currentOffset()}")
    }

    private fun currentOffset(): String = ZoneId.systemDefault().rules.getOffset(Instant.now()).id

    private fun store(): DocumentStore? {
        val application = context ?: return null
        return DocumentStore(File(application.filesDir, DIRECTORY))
    }

    private fun replayFromState(): Replay? = store()?.loadState()?.replay

    private fun currentConfig(): ForwarderConfig? {
        val config = linphoneConfig() ?: return null
        return try {
            ForwarderConfig.from(config)
        } catch (e: Exception) {
            Log.e("[$FORWARDER_TAG] Could not read the [log_forwarding] section: $e")
            null
        }
    }

    private fun linphoneConfig(): Config? = try {
        corePreferences.config
    } catch (e: Exception) {
        null
    }

    private fun identity(): ForwarderIdentity {
        val config = linphoneConfig()
        val address = defaultIdentity(config)
        return ForwarderIdentity(
            serviceName = SERVICE_NAME,
            deviceUuid = config?.getString("misc", "uuid", "").orEmpty(),
            version = context?.getString(R.string.linphone_app_version).orEmpty(),
            platform = PLATFORM,
            process = Application.getProcessName(),
            subsystem = LOG_DOMAIN,
            tenant = address?.second,
            extension = address?.first
        )
    }

    /** The default account's extension and tenant, read from linphonerc rather than from a Core. */
    private fun defaultIdentity(config: Config?): Pair<String, String>? {
        if (config == null) return null
        val index = config.getInt("sip", "default_proxy", 0)
        val raw = config.getString("proxy_$index", "reg_identity", "").orEmpty()
        val uri = raw.substringAfterLast('<').substringBefore('>')
        val user = uri.substringAfter(':', "").substringBefore('@')
        val domain = uri.substringAfter('@', "").substringBefore(';')
        if (user.isEmpty() || domain.isEmpty()) return null
        return Pair(user, domain)
    }

    private fun hasNetwork(): Boolean {
        val application = context ?: return false
        val manager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
