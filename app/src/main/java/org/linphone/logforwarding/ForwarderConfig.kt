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

import org.linphone.core.Config
import org.linphone.core.tools.Log

/** The `[log_forwarding]` section, read from a Config rather than from a Core. */
class ForwarderConfig(
    /** Blank is the off switch. */
    val url: String,
    val settings: ForwardSettings,
    /** Raw record bytes kept per read; -1 for unbounded. */
    val softLimit: Long,
    /** Rendered bytes per export, before gzip. */
    val maxPostBytes: Int,
    val foregroundInterval: Long,
    val backgroundInterval: Long,
    val maxBackoff: Long
) {
    companion object {
        private const val TAG = "[config]"

        private const val SECTION = "log_forwarding"
        private const val LEVEL_PREFIX = "level."
        private const val TAG_PREFIX = "tag."

        private const val DEFAULT_SOFT_LIMIT = 262144L
        private const val DEFAULT_MAX_POST_BYTES = 1048576
        private const val DEFAULT_FOREGROUND_INTERVAL = 10000L
        private const val DEFAULT_BACKGROUND_INTERVAL = 86400000L
        private const val DEFAULT_MAX_BACKOFF = 900000L

        fun from(config: Config): ForwarderConfig {
            val levels = mutableMapOf<String, Level>()
            val tags = mutableMapOf<String, Level>()
            config.getKeysNamesList(SECTION).forEach { key ->
                val level = Level.threshold(config.getString(SECTION, key, "")) ?: return@forEach
                when {
                    key.startsWith(LEVEL_PREFIX) ->
                        key.removePrefix(LEVEL_PREFIX).takeIf { it.isNotEmpty() }?.let { levels[it] = level }
                    key.startsWith(TAG_PREFIX) ->
                        ForwardSettings.normalizeTag(key.removePrefix(TAG_PREFIX))
                            .takeIf { it.isNotEmpty() }?.let { tags[it] = level }
                }
            }

            return ForwarderConfig(
                url = config.getString(SECTION, "url", "").orEmpty().trim(),
                settings = ForwardSettings(
                    default = Level.threshold(config.getString(SECTION, "default_level", "")) ?: Level.Warning,
                    levels = levels,
                    tags = tags
                ),
                softLimit = config.getLong(
                    "soft_limit", DEFAULT_SOFT_LIMIT, min = 1, specials = arrayOf(-1L)
                ),
                maxPostBytes = config.getInt("max_post_bytes", DEFAULT_MAX_POST_BYTES, min = 1),
                foregroundInterval = config.getLong(
                    "foreground_interval", DEFAULT_FOREGROUND_INTERVAL, min = 100
                ),
                backgroundInterval = config.getLong(
                    "background_interval", DEFAULT_BACKGROUND_INTERVAL, min = 1
                ),
                maxBackoff = config.getLong("max_backoff", DEFAULT_MAX_BACKOFF, min = 1)
            )
        }

        /**
         * `Config.getInt` yields 0 for a key present but blank or unparsable, so parse the string
         * instead. A value outside [min]..[max] and not in [specials] means [defaultValue].
         */
        private fun Config.getLong(
            key: String,
            defaultValue: Long,
            min: Long? = null,
            max: Long? = null,
            specials: Array<Long>? = null
        ): Long {
            val raw = getString(SECTION, key, "").orEmpty().trim()
            if (raw.isEmpty()) {
                if (key in getKeysNamesList(SECTION)) {
                    Log.w("$TAG \"$key\" is provisioned blank, using '$defaultValue'")
                }
                return defaultValue
            }
            val value = raw.toLongOrNull()
            if (value == null) {
                Log.w("$TAG \"$key\" '$raw' is not a number, using '$defaultValue'")
                return defaultValue
            }
            if (specials?.contains(value) == true) return value
            if ((min != null && value < min) || (max != null && value > max)) {
                Log.w("$TAG \"$key\" '$value' is out of range, using '$defaultValue'")
                return defaultValue
            }
            return value
        }

        /** Bounds to the Int range so the narrowing cannot overflow. */
        private fun Config.getInt(
            key: String,
            defaultValue: Int,
            min: Int? = null,
            max: Int? = null,
            specials: Array<Int>? = null
        ): Int = getLong(
            key,
            defaultValue.toLong(),
            min?.toLong() ?: Int.MIN_VALUE.toLong(),
            max?.toLong() ?: Int.MAX_VALUE.toLong(),
            specials?.map { it.toLong() }?.toTypedArray()
        ).toInt()
    }
}
