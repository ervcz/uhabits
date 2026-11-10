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
import org.isoron.uhabits.core.commands.EditHabitCommand
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
        // A mocked snooze is a manual one, never a nag repeat.
        every { widgetPreferences.getNagDay(any()) } returns null
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
    private val afterReminder = unixTime(2015, 1, 26, 13, 0) // local; reminder at 8:30

    private fun nagScheduler(minutes: Int, at: Long = afterReminder): ReminderScheduler {
        setFixedLocalTime(at)
        setToday(nagDay)
        preferences.nagIntervalMinutes = minutes
        habit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        habit.repeatUntilDone = true
        return ReminderScheduler(commandRunner, habitList, sys, nagPrefs, preferences)
    }

    private fun editHabit(scheduler: ReminderScheduler, change: Habit.() -> Unit) {
        val modified = fixtures.createEmptyHabit().apply { copyFrom(habit) }.apply(change)
        scheduler.onCommandFinished(EditHabitCommand(habitList, habitId, modified).also { it.run() })
    }

    private fun mark(h: Habit, value: Int) {
        h.originalEntries.add(Entry(nagDay, value))
        h.recompute()
    }

    @Test
    fun testNag_onlyForHabitsSetToRepeatUntilDone() {
        assertFalse(fixtures.createEmptyHabit().repeatUntilDone)
        val scheduler = nagScheduler(minutes = 1)
        habit.repeatUntilDone = false
        assertFalse(scheduler.nag(habit, nagDay))
    }

    @Test
    fun testNag_repeatsWhileNotDone() {
        val scheduler = nagScheduler(minutes = 2)
        assertTrue(scheduler.nag(habit, nagDay))
        val next = unixTime(2015, 1, 26, 17, 2)
        assertEquals(next, nagPrefs.getSnoozeTime(habitId))
        verify { sys.scheduleShowReminder(next, habit, unixTime(2015, 1, 26), true) }
    }

    @Test
    fun testNag_stopsWhenDoneOrSkipped() {
        val scheduler = nagScheduler(minutes = 1)
        mark(habit, Entry.YES_MANUAL)
        assertFalse(scheduler.nag(habit, nagDay))
        mark(habit, Entry.SKIP)
        assertFalse(scheduler.nag(habit, nagDay))
    }

    @Test
    fun testNag_onlyOnReminderDays() {
        val scheduler = nagScheduler(minutes = 1)
        // nagDay is Thursday 26 February 2015 (unixTime months start at 0):
        // index 5, Saturday first. Hardcoded, not computed like the code does.
        habit.reminder = Reminder(8, 30, WeekdayList(BooleanArray(7) { it == 5 }))
        assertEquals(unixTime(2015, 1, 26, 17, 1), scheduler.nextNagTime(habit, nagDay))
        habit.reminder = Reminder(8, 30, WeekdayList(BooleanArray(7) { it != 5 }))
        assertNull(scheduler.nextNagTime(habit, nagDay))
    }

    @Test
    fun testNag_notForArchivedOrReminderless() {
        val scheduler = nagScheduler(minutes = 1)
        habit.isArchived = true
        assertNull(scheduler.nextNagTime(habit, nagDay))
        habit.isArchived = false
        habit.reminder = null
        assertNull(scheduler.nextNagTime(habit, nagDay))
    }

    @Test
    fun testNag_notForAtMostTargets() {
        val scheduler = nagScheduler(minutes = 1)
        val limit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_MOST)
        limit.id = 11L
        limit.reminder = Reminder(8, 30, WeekdayList.EVERY_DAY)
        assertNull(scheduler.nextNagTime(limit, nagDay))
    }

    @Test
    fun testNag_resumesAfterMissedRepeat() {
        // Day ends at 3 AM. The phone was off when the 00:30 repeat was due:
        // it resumes, still about the 26th.
        val scheduler = nagScheduler(minutes = 1, at = unixTime(2015, 1, 27, 1, 0))
        preferences.isMidnightDelayEnabled = true
        nagPrefs.setNagTime(habitId, unixTime(2015, 1, 27, 4, 30), nagDay.daysSince2000)
        scheduler.schedule(habit)
        val resumed = unixTime(2015, 1, 27, 5, 1)
        assertEquals(resumed, nagPrefs.getSnoozeTime(habitId))
        verify { sys.scheduleShowReminder(resumed, habit, unixTime(2015, 1, 26), true) }
    }

    @Test
    fun testNag_missedManualSnoozeResumesIt() {
        // The phone was off when a snooze to 12:50 ran out.
        val scheduler = nagScheduler(minutes = 1)
        nagPrefs.setSnoozeTime(habitId, unixTime(2015, 1, 26, 16, 50))
        scheduler.schedule(habit)
        assertEquals(unixTime(2015, 1, 26, 17, 1), nagPrefs.getSnoozeTime(habitId))
    }

    @Test
    fun testNag_pendingRepeatIsDroppedOnceItsDayIsOver() {
        // A repeat at 00:01 about the 26th; then "day ends at 3 AM" is
        // switched off, so the 26th is over.
        val scheduler = nagScheduler(minutes = 2, at = unixTime(2015, 1, 26, 23, 59))
        preferences.isMidnightDelayEnabled = true
        assertTrue(scheduler.nag(habit, nagDay))
        setFixedLocalTime(unixTime(2015, 1, 27, 0, 0))
        preferences.isMidnightDelayEnabled = false
        scheduler.schedule(habit)
        assertEquals(0L, nagPrefs.getSnoozeTime(habitId))
    }

    private fun nagCommand(value: Int) =
        CreateRepetitionCommand(habitList, habit, nagDay, value, "").also { it.run() }

    private fun listeningNagScheduler(minutes: Int, at: Long = afterReminder): ReminderScheduler {
        val scheduler = nagScheduler(minutes, at)
        habitList.add(habit)
        scheduler.startListening()
        return scheduler
    }

    @Test
    fun testNag_checkingStopsItAndUncheckingRestartsIt() {
        val scheduler = nagScheduler(minutes = 1)
        assertTrue(scheduler.nag(habit, nagDay))
        scheduler.onCommandFinished(nagCommand(Entry.YES_MANUAL))
        assertEquals(0L, nagPrefs.getSnoozeTime(habitId))
        // Replaced by tomorrow's ordinary reminder (same alarm slot per habit).
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 27, 12, 30),
                habit,
                unixTime(2015, 1, 27),
                false
            )
        }
        scheduler.onCommandFinished(nagCommand(Entry.NO))
        assertEquals(unixTime(2015, 1, 26, 17, 1), nagPrefs.getSnoozeTime(habitId))
        assertEquals(nagDay.daysSince2000, nagPrefs.getNagDay(habitId))
    }

    @Test
    fun testNag_answeringNoKeepsThePendingRepeat() {
        val scheduler = nagScheduler(minutes = 2)
        assertTrue(scheduler.nag(habit, nagDay))
        setFixedLocalTime(unixTime(2015, 1, 26, 13, 1))
        scheduler.onCommandFinished(nagCommand(Entry.NO))
        assertEquals(unixTime(2015, 1, 26, 17, 2), nagPrefs.getSnoozeTime(habitId))
    }

    @Test
    fun testNag_uncheckingKeepsAManualSnooze() {
        val scheduler = nagScheduler(minutes = 1)
        scheduler.snoozeReminder(habit, 60)
        scheduler.onCommandFinished(nagCommand(Entry.NO))
        assertEquals(unixTime(2015, 1, 26, 18, 0), nagPrefs.getSnoozeTime(habitId))
    }

    @Test
    fun testNag_reminderMovedLaterStopsIt() {
        val scheduler = nagScheduler(minutes = 1)
        assertTrue(scheduler.nag(habit, nagDay))
        habit.reminder = Reminder(20, 0, WeekdayList.EVERY_DAY)
        scheduler.schedule(habit)
        assertEquals(0L, nagPrefs.getSnoozeTime(habitId))
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 27, 0, 0),
                habit,
                unixTime(2015, 1, 26),
                false
            )
        }
    }

    @Test
    fun testNag_midnightDelayKeepsItGoingAfterMidnight() {
        val scheduler = nagScheduler(minutes = 2, at = unixTime(2015, 1, 26, 23, 59))
        preferences.isMidnightDelayEnabled = true
        assertTrue(scheduler.nag(habit, nagDay))
        // 00:01 local, still about the 26th.
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 27, 4, 1),
                habit,
                unixTime(2015, 1, 26),
                true
            )
        }
        setFixedLocalTime(unixTime(2015, 1, 27, 2, 0))
        assertEquals(unixTime(2015, 1, 27, 6, 2), scheduler.nextNagTime(habit, nagDay))
        setFixedLocalTime(unixTime(2015, 1, 27, 2, 59))
        assertNull(scheduler.nextNagTime(habit, nagDay))
    }

    @Test
    fun testNag_endsAtTheNextReminder() {
        val scheduler = nagScheduler(minutes = 2, at = unixTime(2015, 1, 27, 0, 59))
        preferences.isMidnightDelayEnabled = true
        habit.reminder = Reminder(1, 0, WeekdayList.EVERY_DAY)
        assertNull(scheduler.nextNagTime(habit, nagDay))
    }

    @Test
    fun testNag_turningTheSwitchOffStopsIt() {
        val scheduler = nagScheduler(minutes = 2)
        habitList.add(habit)
        assertTrue(scheduler.nag(habit, nagDay))
        editHabit(scheduler) { repeatUntilDone = false }
        assertEquals(0L, nagPrefs.getSnoozeTime(habitId))
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 27, 12, 30),
                habit,
                unixTime(2015, 1, 27),
                false
            )
        }
    }

    @Test
    fun testNag_turningTheSwitchOnStartsItForTheLatestUnansweredReminder() {
        // Day ends at 3 AM: at 01:00 the 26th's reminder is the latest, and
        // the 26th is not over yet.
        val scheduler = nagScheduler(minutes = 5, at = unixTime(2015, 1, 27, 1, 0))
        habit.repeatUntilDone = false
        habitList.add(habit)
        preferences.isMidnightDelayEnabled = true
        editHabit(scheduler) { repeatUntilDone = true }
        assertEquals(unixTime(2015, 1, 27, 5, 5), nagPrefs.getSnoozeTime(habitId))
        assertEquals(nagDay.daysSince2000, nagPrefs.getNagDay(habitId))
    }

    @Test
    fun testNag_changingTheIntervalAppliesAtOnce() {
        val scheduler = listeningNagScheduler(minutes = 30)
        assertTrue(scheduler.nag(habit, nagDay))
        preferences.nagIntervalMinutes = 1
        assertEquals(unixTime(2015, 1, 26, 17, 1), nagPrefs.getSnoozeTime(habitId))
    }

    @Test
    fun testNag_snoozingDuringANagIsAManualSnooze() {
        // ...so changing the interval keeps it instead of restarting the nag.
        val scheduler = listeningNagScheduler(minutes = 30)
        assertTrue(scheduler.nag(habit, nagDay))
        scheduler.snoozeReminder(habit, 60)
        preferences.nagIntervalMinutes = 1
        assertEquals(unixTime(2015, 1, 26, 18, 0), nagPrefs.getSnoozeTime(habitId))
        assertNull(nagPrefs.getNagDay(habitId))
        verify {
            sys.scheduleShowReminder(
                unixTime(2015, 1, 26, 18, 0),
                habit,
                unixTime(2015, 1, 26),
                true
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
