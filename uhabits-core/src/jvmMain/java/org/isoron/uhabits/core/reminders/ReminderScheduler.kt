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

import me.tatarka.inject.annotations.Inject
import org.isoron.platform.time.DateUtils
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.AppScope
import org.isoron.uhabits.core.commands.ChangeHabitColorCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.HabitMatcher
import org.isoron.uhabits.core.models.NumericalHabitType
import org.isoron.uhabits.core.preferences.Preferences
import org.isoron.uhabits.core.preferences.WidgetPreferences

@AppScope
@Inject
open class ReminderScheduler(
    private val commandRunner: CommandRunner,
    private val habitList: HabitList,
    private val sys: SystemScheduler,
    private val widgetPreferences: WidgetPreferences,
    private val preferences: Preferences
) : CommandRunner.Listener {
    @Synchronized
    override fun onCommandFinished(command: Command) {
        if (command is CreateRepetitionCommand) {
            stopNaggingIfDone(command.habit)
            return
        }
        if (command is ChangeHabitColorCommand) return
        scheduleAll()
    }

    /**
     * Nag mode: when the reminder for [date] has been shown and the habit is
     * still not done, show it again every few minutes until it is done or the
     * day ends. The repeat rides on the snooze time, so it survives app
     * restarts and reboots like a manual snooze does.
     *
     * Returns false when there is nothing to nag about.
     */
    @Synchronized
    open fun nag(habit: Habit, date: LocalDate): Boolean {
        val next = nextNagTime(habit, date) ?: return false
        sys.log("ReminderScheduler", "Nagging habit=${habit.id} again at $next")
        widgetPreferences.setSnoozeTime(habit.id!!, next)
        schedule(habit)
        return true
    }

    /**
     * When the habit for [date] should be shown again, or null if nag mode is
     * off, the habit is done (or skipped) or no longer due that day, or the
     * next repeat would fall on another day.
     */
    open fun nextNagTime(habit: Habit, date: LocalDate): Long? {
        val minutes = preferences.nagIntervalMinutes
        if (minutes <= 0) return null
        if (habit.id == null || habit.isArchived) return null
        val reminder = habit.reminder ?: return null
        // An "at most" target is met by doing nothing, so there is nothing to
        // nag about; its reminder still shows once, as before.
        if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_MOST) return null
        val weekday = (date.dayOfWeek.daysSinceSunday + 1) % 7
        if (!reminder.days.toArray()[weekday]) return null
        if (habit.isCompletedOn(date)) return null
        val next = now() + minutes * 60_000L
        if (dayOf(next) != date) return null
        return next
    }

    // Checking the habit (from the app, a widget, the notification or a
    // watch) ends the nag at once instead of letting one more alarm fire.
    // Nag mode off: unchanged upstream behaviour.
    private fun stopNaggingIfDone(habit: Habit) {
        if (preferences.nagIntervalMinutes <= 0) return
        val id = habit.id ?: return
        val snoozed = widgetPreferences.getSnoozeTime(id)
        if (snoozed == 0L || nextNagTime(habit, dayOf(snoozed)) != null) return
        sys.log("ReminderScheduler", "Habit $id is done. Stopping the nag.")
        widgetPreferences.removeSnoozeTime(id)
        schedule(habit)
    }

    private fun now(): Long = DateUtils.applyTimezone(DateUtils.getLocalTime())

    private fun dayOf(time: Long): LocalDate =
        LocalDate.fromUnixTime(DateUtils.getStartOfDay(DateUtils.removeTimezone(time)))

    @Synchronized
    open fun schedule(habit: Habit) {
        if (habit.id == null) {
            sys.log("ReminderScheduler", "Habit has null id. Returning.")
            return
        }
        if (!habit.hasReminder()) {
            sys.log("ReminderScheduler", "habit=" + habit.id + " has no reminder. Skipping.")
            return
        }
        var reminderTime = DateUtils.getUpcomingTimeInMillis(
            habit.reminder!!.hour,
            habit.reminder!!.minute
        )
        var urgent = false
        val snoozeReminderTime = widgetPreferences.getSnoozeTime(habit.id!!)
        if (snoozeReminderTime != 0L) {
            val now = now()
            sys.log("ReminderScheduler", "Habit ${habit.id} has been snoozed until $snoozeReminderTime")
            if (snoozeReminderTime > now) {
                sys.log("ReminderScheduler", "Snooze time is in the future. Accepting.")
                reminderTime = snoozeReminderTime
                urgent = preferences.nagIntervalMinutes > 0
            } else {
                // Nag mode: a repeat missed while the phone was off or the
                // app was killed resumes instead of waiting for tomorrow.
                val resumed = nextNagTime(habit, dayOf(snoozeReminderTime))
                if (resumed != null) {
                    sys.log("ReminderScheduler", "Missed a nag. Resuming at $resumed.")
                    widgetPreferences.setSnoozeTime(habit.id!!, resumed)
                    reminderTime = resumed
                    urgent = true
                } else {
                    sys.log("ReminderScheduler", "Snooze time is in the past. Discarding.")
                    widgetPreferences.removeSnoozeTime(habit.id!!)
                }
            }
        }
        scheduleAtTime(habit, reminderTime, urgent)
    }

    /**
     * [urgent] asks the system to deliver the alarm on time even in battery
     * saving (Doze), where ordinary alarms may be held back for 10+ minutes.
     * Used for nag repeats only.
     */
    @Synchronized
    open fun scheduleAtTime(habit: Habit, reminderTime: Long, urgent: Boolean = false) {
        sys.log("ReminderScheduler", "Scheduling alarm for habit=" + habit.id)
        if (!habit.hasReminder()) {
            sys.log("ReminderScheduler", "habit=" + habit.id + " has no reminder. Skipping.")
            return
        }
        if (habit.isArchived) {
            sys.log("ReminderScheduler", "habit=" + habit.id + " is archived. Skipping.")
            return
        }
        val timestamp = DateUtils.getStartOfDayWithOffset(DateUtils.removeTimezone(reminderTime), 0, 0)
        sys.log("ReminderScheduler", "reminderTime=$reminderTime removeTimezone=${DateUtils.removeTimezone(reminderTime)} timestamp=$timestamp")
        sys.scheduleShowReminder(reminderTime, habit, timestamp, urgent)
    }

    @Synchronized
    open fun scheduleAll() {
        sys.log("ReminderScheduler", "Scheduling all alarms")
        val reminderHabits = habitList.getFiltered(HabitMatcher.WITH_ALARM)
        for (habit in reminderHabits) schedule(habit)
    }

    @Synchronized
    open fun hasHabitsWithReminders(): Boolean {
        return !habitList.getFiltered(HabitMatcher.WITH_ALARM).isEmpty
    }

    @Synchronized
    open fun startListening() {
        commandRunner.addListener(this)
    }

    @Synchronized
    open fun stopListening() {
        commandRunner.removeListener(this)
    }

    @Synchronized
    open fun snoozeReminder(habit: Habit, minutes: Long) {
        val now = DateUtils.applyTimezone(DateUtils.getLocalTime())
        val snoozedUntil = now + minutes * 60 * 1000
        widgetPreferences.setSnoozeTime(habit.id!!, snoozedUntil)
        schedule(habit)
    }

    interface SystemScheduler {
        fun scheduleShowReminder(
            reminderTime: Long,
            habit: Habit,
            timestamp: Long,
            urgent: Boolean = false
        ): SchedulerResult

        fun scheduleWidgetUpdate(updateTime: Long): SchedulerResult?
        fun log(componentName: String, msg: String)
    }

    enum class SchedulerResult {
        IGNORED, OK
    }
}
