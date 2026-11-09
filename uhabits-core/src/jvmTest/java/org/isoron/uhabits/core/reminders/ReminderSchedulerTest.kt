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
package org.isoron.uhabits.core.reminders

import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import org.isoron.platform.time.DateUtils
import org.isoron.platform.time.DateUtils.removeTimezone
import org.isoron.platform.time.DateUtils.setFixedLocalTime
import org.isoron.platform.time.DateUtils.setFixedTimeZone
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.setToday
import org.isoron.platform.time.unixTime
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.models.Reminder
import org.isoron.uhabits.core.models.WeekdayList
import org.isoron.uhabits.core.preferences.MemoryStorage
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.preferences.WidgetPreferences
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderSchedulerTest : BaseUnitTest() {
    private val habitId = 10L
    private lateinit var habit: Habit
    private lateinit var reminderScheduler: ReminderScheduler

    private val sys: ReminderScheduler.SystemScheduler = mock()
    private val widgetPreferences: WidgetPreferences = mock()
    private val preferences = Preferences(MemoryStorage())

    @Before
    @Throws(Exception::class)
    override fun setUp() {
        super.setUp()
        habit = fixtures.createEmptyHabit()
        habit.id = habitId
        reminderScheduler =
            ReminderScheduler(commandRunner, habitList, sys, widgetPreferences, preferences)
        setFixedTimeZone(TimeZone.getTimeZone("GMT-4"))
    }

    @After
    fun tearDown() {
        setFixedLocalTime(null)
        setFixedTimeZone(null)
    }

    @Test
    fun testScheduleAll() {
        val now = unixTime(2015, 1, 26, 13, 0)
        setFixedLocalTime(now)
        val h1 = fixtures.createEmptyHabit()
        val h2 = fixtures.createEmptyHabit()
        val h3 = fixtures.createEmptyHabit()
        h1.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        h2.reminder = Reminder(18, 30, WeekdayList.EVERY_DAY)
        h3.reminder = null
        habitList.add(h1)
        habitList.add(h2)
        habitList.add(h3)
        reminderScheduler.scheduleAll()
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 27, 12, 30),
                h1,
                any()
            )
        }
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 26, 22, 30),
                h2,
                any()
            )
        }
    }

    @Test
    fun testSchedule_atSpecificTime() {
        val atTime = unixTime(2015, 1, 30, 11, 30)
        val expectedCheckmarkTime = unixTime(2015, 1, 30, 0, 0)
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        scheduleAndVerify(atTime, expectedCheckmarkTime, atTime)
    }

    @Test
    fun testSchedule_withSnooze() {
        val now = removeTimezone(unixTime(2015, 1, 1, 15, 0))
        setFixedLocalTime(now)
        val snoozeTimeInFuture = unixTime(2015, 1, 1, 21, 0)
        val snoozeTimeInPast = unixTime(2015, 1, 1, 7, 0)
        val regularReminderTime = DateUtils.applyTimezone(unixTime(2015, 1, 2, 8, 30))
        val todayCheckmarkTime = unixTime(2015, 1, 1, 0, 0)
        val tomorrowCheckmarkTime = unixTime(2015, 1, 2, 0, 0)
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        every { widgetPreferences.getSnoozeTime(habitId) } returns snoozeTimeInFuture
        reminderScheduler.schedule(habit)
        verify { sys.scheduleShowReminder(snoozeTimeInFuture, habit, todayCheckmarkTime) }
        every { widgetPreferences.getSnoozeTime(habitId) } returns snoozeTimeInPast
        reminderScheduler.schedule(habit)
        verify {
            sys.scheduleShowReminder(regularReminderTime, habit, tomorrowCheckmarkTime)
        }
    }

    @Test
    fun testSchedule_laterToday() {
        val now = unixTime(2015, 1, 26, 6, 30)
        setFixedLocalTime(now)
        val expectedCheckmarkTime = unixTime(2015, 1, 26, 0, 0)
        val expectedReminderTime = unixTime(2015, 1, 26, 12, 30)
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        scheduleAndVerify(null, expectedCheckmarkTime, expectedReminderTime)
    }

    @Test
    fun testSchedule_tomorrow() {
        val now = unixTime(2015, 1, 26, 13, 0)
        setFixedLocalTime(now)
        val expectedCheckmarkTime = unixTime(2015, 1, 27, 0, 0)
        val expectedReminderTime = unixTime(2015, 1, 27, 12, 30)
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        scheduleAndVerify(null, expectedCheckmarkTime, expectedReminderTime)
    }

    @Test
    fun testSchedule_withoutReminder() {
        reminderScheduler.schedule(habit)
    }

    // Nag mode. Time zone GMT-4: a local 13:00 is 17:00 real time. Nag tests
    // use real WidgetPreferences so a removed snooze time stays removed.
    private val nagDay = LocalDate.fromUnixTime(unixTime(2015, 1, 26))
    private val nagPrefs = WidgetPreferences(MemoryStorage())

    private fun nagScheduler(minutes: Int): ReminderScheduler {
        setToday(nagDay)
        preferences.nagIntervalMinutes = minutes
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        return ReminderScheduler(commandRunner, habitList, sys, nagPrefs, preferences)
    }

    private fun mark(h: Habit, value: Int) {
        h.originalEntries.add(Entry(nagDay, value))
        h.recompute()
    }

    @Test
    fun testNag_offByDefault() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        assertEquals(0, preferences.nagIntervalMinutes)
        assertFalse(reminderScheduler.nag(habit, nagDay))
    }

    @Test
    fun testNag_repeatsWhileNotDone() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 2)
        assertTrue(scheduler.nag(habit, nagDay))
        val next = unixTime(2015, 1, 26, 17, 2)
        assertEquals(next, nagPrefs.getSnoozeTime(habitId))
        verify { sys.scheduleShowReminder(next, habit, unixTime(2015, 1, 26), true) }
    }

    @Test
    fun testNag_stopsWhenDone() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        mark(habit, Entry.YES_MANUAL)
        assertFalse(scheduler.nag(habit, nagDay))
    }

    @Test
    fun testNag_stopsWhenSkipped() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        mark(habit, Entry.SKIP)
        assertFalse(scheduler.nag(habit, nagDay))
    }

    @Test
    fun testNag_keepsGoingAfterNo() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        mark(habit, Entry.NO)
        assertTrue(scheduler.nag(habit, nagDay))
    }

    @Test
    fun testNag_stopsAtMidnight() {
        setFixedLocalTime(unixTime(2015, 1, 26, 23, 59))
        val scheduler = nagScheduler(minutes = 2)
        assertNull(scheduler.nextNagTime(habit, nagDay))
    }

    @Test
    fun testNag_notOnDaysWithoutReminder() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        habit.reminder = Reminder(8, 30, WeekdayList(0))
        assertNull(scheduler.nextNagTime(habit, nagDay))
    }

    @Test
    fun testNag_notForArchivedOrReminderless() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        habit.isArchived = true
        assertNull(scheduler.nextNagTime(habit, nagDay))
        habit.isArchived = false
        habit.reminder = null
        assertNull(scheduler.nextNagTime(habit, nagDay))
    }

    @Test
    fun testNag_notForAtMostTargets() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        val limit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST)
        limit.id = 11L
        limit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        assertNull(scheduler.nextNagTime(limit, nagDay))
    }

    @Test
    fun testNag_resumesAfterMissedRepeat() {
        // The phone was off when the 12:50 repeat was due.
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        nagPrefs.setSnoozeTime(habitId, unixTime(2015, 1, 26, 16, 50))
        scheduler.schedule(habit)
        val resumed = unixTime(2015, 1, 26, 17, 1)
        assertEquals(resumed, nagPrefs.getSnoozeTime(habitId))
        verify { sys.scheduleShowReminder(resumed, habit, unixTime(2015, 1, 26), true) }
    }

    @Test
    fun testNag_missedRepeatFromYesterdayIsDropped() {
        setFixedLocalTime(unixTime(2015, 1, 27, 9, 0))
        val scheduler = nagScheduler(minutes = 1)
        nagPrefs.setSnoozeTime(habitId, unixTime(2015, 1, 26, 22, 0))
        scheduler.schedule(habit)
        assertEquals(0L, nagPrefs.getSnoozeTime(habitId))
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 28, 12, 30), habit, unixTime(2015, 1, 28), false
            )
        }
    }

    @Test
    fun testNag_checkingTheHabitCancelsThePendingRepeat() {
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 0))
        val scheduler = nagScheduler(minutes = 1)
        assertTrue(scheduler.nag(habit, nagDay))
        val command = CreateRepetitionCommand(habitList, habit, nagDay, Entry.YES_MANUAL, "")
        command.run()
        scheduler.onCommandFinished(command)
        assertEquals(0L, nagPrefs.getSnoozeTime(habitId))
        // Replaced by tomorrow's ordinary reminder (same alarm slot per habit).
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 27, 12, 30), habit, unixTime(2015, 1, 27), false
            )
        }
    }

    private fun scheduleAndVerify(
        atTime: Long?,
        expectedCheckmarkTime: Long,
        expectedReminderTime: Long
    ) {
        if (atTime == null) {
            reminderScheduler.schedule(habit)
        } else {
            reminderScheduler.scheduleAtTime(
                habit,
                atTime
            )
        }
        verify {
            sys.scheduleShowReminder(
                expectedReminderTime,
                habit,
                expectedCheckmarkTime
            )
        }
    }
}
