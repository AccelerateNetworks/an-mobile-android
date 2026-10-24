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

import java.util.Locale

/**
 * Declaration order is increasing verbosity: a threshold admits a record at or below it.
 * [None] is a threshold only and never the level of a record.
 */
enum class Level(val token: String, val severityNumber: Int) {
    None("NONE", 0),
    Fatal("FATAL", 21),
    Error("ERROR", 17),
    Warning("WARNING", 13),
    Message("MESSAGE", 9),
    Debug("DEBUG", 5);

    companion object {
        /** Any of the six, as a provisioned threshold. Null when [value] names none of them. */
        fun threshold(value: String?): Level? {
            val token = value?.trim()?.uppercase(Locale.ROOT).orEmpty()
            return entries.firstOrNull { it.token == token }
        }

        /** One of the five a record can carry. Null for anything else, [None] included. */
        fun record(token: String): Level? {
            return entries.firstOrNull { it != None && it.token == token }
        }
    }
}
