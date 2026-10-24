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

import java.security.MessageDigest
import java.util.Locale

/**
 * [tags] is keyed on the tag lowercased with non-alphanumerics removed, which is also the form
 * [Record.tag] carries.
 */
data class ForwardSettings(
    val default: Level,
    val levels: Map<String, Level>,
    val tags: Map<String, Level>
) {
    companion object {
        fun normalizeTag(tag: String): String {
            return tag.lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' }
        }
    }

    fun levelFor(subsystem: String): Level = levels[subsystem] ?: default

    fun levelForTag(tag: String?): Level? = tag?.let { tags[it] }

    fun thresholdFor(record: Record): Level =
        levelForTag(record.tag) ?: levelFor(record.subsystem)

    fun accepts(record: Record): Boolean = record.level.ordinal <= thresholdFor(record).ordinal

    /** Does [next] admit anything these settings did not? */
    fun requiresReplay(next: ForwardSettings): Boolean {
        if (next.default.ordinal > default.ordinal) return true

        for (subsystem in levels.keys + next.levels.keys) {
            if (next.levelFor(subsystem).ordinal > levelFor(subsystem).ordinal) return true
        }

        // A tag ruled on one side only falls through to that side's subsystem threshold, and the
        // record's subsystem is unknown here, so compare against the extreme it could reach.
        val oldWeakest = levels.values
            .fold(default) { a, b -> if (b.ordinal < a.ordinal) b else a }
        val newStrongest = next.levels.values
            .fold(next.default) { a, b -> if (b.ordinal > a.ordinal) b else a }

        for (tag in tags.keys + next.tags.keys) {
            val old = tags[tag]
            val new = next.tags[tag]
            val widened = when {
                old != null && new != null -> new.ordinal > old.ordinal
                new != null -> new.ordinal > oldWeakest.ordinal
                old != null -> newStrongest.ordinal > old.ordinal
                else -> false
            }
            if (widened) return true
        }
        return false
    }

    /** Terms in lexicographical order by key: map iteration order is not stable across runs. */
    fun sha256(): ByteArray {
        val terms = mutableListOf("default=${default.token}")
        levels.forEach { (subsystem, level) -> terms.add("level.$subsystem=${level.token}") }
        tags.forEach { (tag, level) -> terms.add("tag.$tag=${level.token}") }
        return MessageDigest.getInstance("SHA-256")
            .digest(terms.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
    }
}
