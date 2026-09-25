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
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.ConfiguringState
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

// AccelerateNetworks: remote provisioning from a typed-in URL, the manual counterpart of the QR scanner
class LoginWithUrlViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Login With URL ViewModel]"
    }

    val url = MutableLiveData<String>()

    val provisioningInProgress = MutableLiveData<Boolean>()

    val loginEnabled = MediatorLiveData<Boolean>()

    val remoteProvisioningSuccessfulEvent = MutableLiveData<Event<Boolean>>()

    // Only react to the Core restart we triggered, and don't leave the assistant on a failed
    // provisioning just because an account was already configured before
    private var provisioningRequested = false
    private var provisioningFailed = false

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onConfiguringStatus(core: Core, status: ConfiguringState, message: String?) {
            if (!provisioningRequested) return

            Log.i("$TAG Configuring state is [$status]")
            if (status == ConfiguringState.Failed) {
                Log.e("$TAG Failure applying remote provisioning: $message")
                provisioningFailed = true
                showRedToast(R.string.remote_provisioning_config_failed_toast, R.drawable.warning_circle)
            }
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (!provisioningRequested || state != GlobalState.On) return

            provisioningRequested = false
            provisioningInProgress.postValue(false)

            if (provisioningFailed) {
                Log.w("$TAG Core is back on but remote provisioning failed, staying in assistant")
            } else if (core.accountList.isEmpty()) {
                Log.w("$TAG Provisioning was successful but no account has been configured yet, staying in assistant")
            } else {
                Log.i("$TAG At least an account exists in Core, leaving assistant")
                remoteProvisioningSuccessfulEvent.postValue(Event(true))
            }
        }
    }

    init {
        provisioningInProgress.value = false
        loginEnabled.value = false
        loginEnabled.addSource(url) { updateLoginEnabled() }
        loginEnabled.addSource(provisioningInProgress) { updateLoginEnabled() }

        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)
        }
    }

    @UiThread
    override fun onCleared() {
        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
        }
        super.onCleared()
    }

    @UiThread
    fun login() {
        if (loginEnabled.value != true) return

        val input = url.value.orEmpty()
        val provisioningUrl = LinphoneUtils.getRemoteProvisioningUrlFromUri(input)
        if (provisioningUrl == null) {
            Log.e("$TAG [$input] doesn't seem to be a valid remote provisioning URL")
            showRedToast(R.string.assistant_login_with_url_invalid_toast, R.drawable.warning_circle)
            return
        }

        provisioningInProgress.value = true
        coreContext.postOnCoreThread { core ->
            Log.i(
                "$TAG Setting remote provisioning URL [$provisioningUrl], restarting the Core to apply configuration changes"
            )
            provisioningRequested = true
            provisioningFailed = false
            core.provisioningUri = provisioningUrl

            Log.i("$TAG Stopping Core")
            core.stop()
            Log.i("$TAG Core has been stopped, restarting it")
            core.start()
            Log.i("$TAG Core has been restarted")
        }
    }

    @UiThread
    private fun updateLoginEnabled() {
        loginEnabled.value = !url.value.isNullOrBlank() && provisioningInProgress.value != true
    }
}
