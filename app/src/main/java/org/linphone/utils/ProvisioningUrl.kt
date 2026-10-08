/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
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
package org.linphone.utils

import androidx.annotation.StringRes
import java.util.Locale
import org.linphone.R

/**
 * Remote provisioning URL grammar shared with linphone-ios.
 *
 * Diverges from upstream linphone-config:// workaround (7a6c3ff70), which
 * accepted http, schemeless and arbitrary-scheme URLs and never lowercased the
 * scheme. liblinphone compares the scheme case-sensitively, so it must be
 * lowercased here; the rest of the URL is left alone because provisioning
 * tokens are case-sensitive.
 */
object ProvisioningUrl {
    const val CONFIG_SCHEME_PREFIX = "linphone-config:"

    fun isConfigUri(uri: String): Boolean {
        return uri.trim().startsWith(CONFIG_SCHEME_PREFIX, ignoreCase = true)
    }

    fun normalize(uri: String): String {
        var url = uri.trim()
        if (url.startsWith(CONFIG_SCHEME_PREFIX, ignoreCase = true)) {
            url = url.substring(CONFIG_SCHEME_PREFIX.length)
        }
        if (url.startsWith("//")) {
            url = "https:$url"
        }
        val schemeEnd = url.indexOf("://")
        if (schemeEnd > 0) {
            url = url.substring(0, schemeEnd).lowercase(Locale.ROOT) + url.substring(schemeEnd)
        }
        return url
    }

    fun isValid(url: String): Boolean {
        return url.startsWith("https://") || url.startsWith("file://")
    }

    /** Returns the normalised URL, or null if it isn't an acceptable provisioning URL. */
    fun parse(uri: String): String? {
        val url = normalize(uri)
        return if (isValid(url)) url else null
    }

    /**
     * Maps the message liblinphone passes along with ConfiguringState.Failed to a user-facing string.
     * [uri] is the provisioning URI that failed, needed because a file:// target that can't be
     * loaded is also reported as "Bad URI".
     */
    @StringRes
    fun getProvisioningFailureMessage(message: String?, uri: String?): Int {
        return when (message?.trim()?.lowercase(Locale.ROOT)) {
            "bad uri" -> if (uri?.startsWith("file://") == true) {
                R.string.remote_provisioning_failed_file_unreadable_toast
            } else {
                R.string.remote_provisioning_failed_bad_uri_toast
            }
            "http error", "http io error" -> R.string.remote_provisioning_failed_network_toast
            "http timeout" -> R.string.remote_provisioning_failed_timeout_toast
            "http auth requested" -> R.string.remote_provisioning_failed_auth_toast
            "invalid request" -> R.string.remote_provisioning_failed_invalid_request_toast
            // Anything else (no message, or the XML parser's error text) can't be pinned on a single cause
            else -> R.string.remote_provisioning_config_failed_toast
        }
    }
}
