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
package org.linphone.ui.main.calls.model

import androidx.annotation.UiThread
import org.linphone.core.tools.Log

class NumpadModel @UiThread constructor(
    private val onDigitClicked: (value: String) -> (Unit),
    private val onCallClicked: () -> (Unit)
) {
    companion object {
        private const val TAG = "[Numpad Model]"
    }

    @UiThread
    fun onDigitClicked(value: String) {
        Log.i("$TAG Clicked on digit [$value]")
        onDigitClicked.invoke(value)
    }

    fun onDigitLongClicked(value: String): Boolean {
        Log.i("$TAG Long clicked on digit [$value]")
        onDigitClicked.invoke(value)
        return true
    }

    @UiThread
    fun onCallClicked() {
        Log.i("$TAG Starting call")
        onCallClicked.invoke()
    }
}
