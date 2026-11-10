/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.ui

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationTrayTest : BaseUnitTest() {
    private val yesterday = LocalDate(2015, 1, 25)
    private val today = LocalDate(2015, 1, 26)
    private val calls = mutableListOf<String>()
    private val preferences = Preferences(MemoryStorage())

    private val systemTray = object : NotificationTray.SystemTray {
        override fun removeNotification(notificationId: Int) {
            calls.add("remove")
        }
        override fun showNotification(
            habit: Habit,
            notificationId: Int,
            date: LocalDate,
            reminderTime: Long
        ) {
            calls.add("show $date")
        }
        override fun log(msg: String) {}
    }

    private fun showReminder(doneOn: LocalDate? = null, repeatUntilDone: Boolean = false) {
        val habit = fixtures.createEmptyHabit()
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        habit.repeatUntilDone = repeatUntilDone
        if (doneOn != null) {
            habit.originalEntries.add(Entry(doneOn, Entry.YES_MANUAL))
            habit.recompute()
        }
        NotificationTray(taskRunner, commandRunner, preferences, systemTray)
            .show(habit, today, 0)
    }

    @Test
    fun testShow_checksTheReminderDayNotAStaleToday() {
        setToday(yesterday) // the process outlived midnight
        showReminder(doneOn = yesterday)
        // Shown, and without nag mode updated in place (no removal first).
        assertEquals(listOf("show $today"), calls)
    }

    @Test
    fun testShow_skipsWhenTheReminderDayIsDone() {
        setToday(yesterday)
        showReminder(doneOn = today)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun testShow_nagModePostsAFreshNotification() {
        // Removing it first makes the phone and a paired watch alert again,
        // instead of silently updating the notification already there.
        showReminder(repeatUntilDone = true)
        assertEquals(listOf("remove", "show $today"), calls)
    }
}
