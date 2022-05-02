/*
 * Copyright (c) 2010-2021 Belledonne Communications SARL.
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
package org.linphone.contact

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.activities.main.viewmodels.MessageNotifierViewModel
import org.linphone.core.Address
import org.linphone.core.ChatRoomSecurityLevel
import org.linphone.core.Friend
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

interface ContactDataInterface {
    val contact: MutableLiveData<Friend>

    val displayName: MutableLiveData<String>

    val securityLevel: MutableLiveData<ChatRoomSecurityLevel>

    val showGroupChatAvatar: Boolean
        get() = false

    val thumbnailUri: Uri?
        get() = contact.value?.getThumbnailUri()

    val pictureUri: Uri?
        get() = contact.value?.getPictureUri()
}

open class GenericContactData(private val sipAddress: Address) : ContactDataInterface {
    final override val contact: MutableLiveData<Friend> = MutableLiveData<Friend>()
    final override val displayName: MutableLiveData<String> = MutableLiveData<String>()
    final override val securityLevel: MutableLiveData<ChatRoomSecurityLevel> = MutableLiveData<ChatRoomSecurityLevel>()

    val initials = MutableLiveData<String>()
    val displayInitials = MutableLiveData<Boolean>()

    private val contactsUpdatedListener = object : ContactsUpdatedListenerStub() {
        override fun onContactUpdated(friend: Friend) {
            contactLookup()
        }
    }

    init {
        securityLevel.value = ChatRoomSecurityLevel.ClearText
        coreContext.contactsManager.addListener(contactsUpdatedListener)
        contactLookup()
    }

    open fun destroy() {
        coreContext.contactsManager.removeListener(contactsUpdatedListener)
    }

    private fun contactLookup() {
        displayName.value = LinphoneUtils.getDisplayName(sipAddress)

        val c = coreContext.contactsManager.findContactByAddress(sipAddress)
        contact.value = c

        initials.value = if (c != null) {
            AppUtils.getInitials(c.name ?: "")
        } else {
            AppUtils.getInitials(displayName.value ?: "")
        }
        displayInitials.value = initials.value.orEmpty().isNotEmpty() && initials.value.orEmpty() != "+"
    }
}

abstract class GenericContactViewModel(private val sipAddress: Address) : MessageNotifierViewModel(), ContactDataInterface {
    final override val contact: MutableLiveData<Friend> = MutableLiveData<Friend>()
    final override val displayName: MutableLiveData<String> = MutableLiveData<String>()
    final override val securityLevel: MutableLiveData<ChatRoomSecurityLevel> = MutableLiveData<ChatRoomSecurityLevel>()

    private val contactsUpdatedListener = object : ContactsUpdatedListenerStub() {
        override fun onContactUpdated(friend: Friend) {
            contactLookup()
        }
    }

    init {
        securityLevel.value = ChatRoomSecurityLevel.ClearText
        coreContext.contactsManager.addListener(contactsUpdatedListener)
        contactLookup()
    }

    override fun onCleared() {
        coreContext.contactsManager.removeListener(contactsUpdatedListener)

        super.onCleared()
    }

    private fun contactLookup() {
        displayName.value = LinphoneUtils.getDisplayName(sipAddress)
        contact.value = coreContext.contactsManager.findContactByAddress(sipAddress)
    }
}
