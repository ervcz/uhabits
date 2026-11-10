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
) : CommandRunner.Listener, Preferences.Listener {
    @Synchronized
    override fun onCommandFinished(command: Command) {
        if (command is CreateRepetitionCommand) {
            return onRepetitionChanged(command.habit, command.date)
        }
        if (command is ChangeHabitColorCommand) return
        scheduleAll()
    }

    // Checking a habit ends its nag at once; unchecking it restarts the nag.
    private fun onRepetitionChanged(habit: Habit, date: LocalDate) {
        val id = habit.id ?: return
        if (nagDayOf(id) != null) {
            schedule(habit)
        } else if (widgetPreferences.getSnoozeTime(id) <= now()) {
            nag(habit, date)
        }
    }

    // Applies a new nag interval at once, leaving a manual snooze alone.
    @Synchronized
    override fun onNagIntervalChanged() {
        for (habit in habitList.getFiltered(HabitMatcher.WITH_ALARM)) {
            val id = habit.id ?: continue
            val day = nagDayOf(id)
                ?: if (widgetPreferences.getSnoozeTime(id) > now()) null else lastReminderDay(habit)
            if (day == null || !nag(habit, day)) schedule(habit)
        }
    }

    /**
     * Nag mode: shows the reminder for [date] again in a few minutes, unless
     * the habit is done or the day is over. Returns false if it will not.
     */
    @Synchronized
    open fun nag(habit: Habit, date: LocalDate): Boolean {
        val id = habit.id ?: return false
        val next = nextNagTime(habit, date) ?: return false
        widgetPreferences.setNagTime(id, next, date.daysSince2000)
        schedule(habit)
        return true
    }

    open fun nextNagTime(habit: Habit, date: LocalDate): Long? {
        if (!isNagDue(habit, date)) return null
        val next = now() + preferences.nagIntervalMinutes * DateUtils.MINUTE_LENGTH
        return next.takeIf { it < nagEnd(habit, date) }
    }

    // Nag mode is on, the reminder for [date] has gone off, the habit is due
    // and not done that day, and the day is not over.
    open fun isNagDue(habit: Habit, date: LocalDate): Boolean {
        if (preferences.nagIntervalMinutes <= 0 || habit.isArchived) return false
        val reminder = habit.reminder ?: return false
        // An "at most" target is met by doing nothing.
        if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_MOST) return false
        if (!reminder.days.toArray()[(date.dayOfWeek.daysSinceSunday + 1) % 7]) return false
        if (habit.isCompletedOn(date)) return false
        return now() in reminderTimeOn(habit, date) until nagEnd(habit, date)
    }

    // The day ends at midnight, or later with "midnight delay", but the next
    // reminder takes over if it comes first.
    private fun nagEnd(habit: Habit, date: LocalDate): Long {
        val next = date.plus(1)
        val dayEnd = next.unixTime + preferences.midnightDelayHours * DateUtils.HOUR_LENGTH
        return minOf(DateUtils.applyTimezone(dayEnd), reminderTimeOn(habit, next))
    }

    private fun reminderTimeOn(habit: Habit, date: LocalDate): Long {
        val r = habit.reminder!!
        val minutes = r.hour * 60L + r.minute
        return DateUtils.applyTimezone(date.unixTime + minutes * DateUtils.MINUTE_LENGTH)
    }

    private fun lastReminderDay(habit: Habit): LocalDate {
        val today = calendarDay(now())
        return if (reminderTimeOn(habit, today) <= now()) today else today.minus(1)
    }

    private fun nagDayOf(id: Long) = widgetPreferences.getNagDay(id)?.let { LocalDate(it) }

    private fun now() = DateUtils.applyTimezone(DateUtils.getLocalTime())

    private fun calendarDay(time: Long) =
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
        nagDayOf(habit.id!!)?.let { return scheduleNag(habit, it) }
        var reminderTime = DateUtils.getUpcomingTimeInMillis(
            habit.reminder!!.hour,
            habit.reminder!!.minute
        )
        var urgent = false
        val snoozeReminderTime = widgetPreferences.getSnoozeTime(habit.id!!)
        if (snoozeReminderTime != 0L) {
            val now = DateUtils.applyTimezone(DateUtils.getLocalTime())
            sys.log("ReminderScheduler", "Habit ${habit.id} has been snoozed until $snoozeReminderTime")
            if (snoozeReminderTime > now) {
                sys.log("ReminderScheduler", "Snooze time is in the future. Accepting.")
                reminderTime = snoozeReminderTime
                urgent = preferences.nagIntervalMinutes > 0
            } else {
                // Nag mode: a snooze missed while the phone was off resumes nagging.
                if (nag(habit, calendarDay(snoozeReminderTime))) return
                sys.log("ReminderScheduler", "Snooze time is in the past. Discarding.")
                widgetPreferences.removeSnoozeTime(habit.id!!)
            }
        }
        scheduleAtTime(habit, reminderTime, urgent)
    }

    // A pending repeat stays while its nag is due; a missed one resumes.
    private fun scheduleNag(habit: Habit, day: LocalDate) {
        val time = widgetPreferences.getSnoozeTime(habit.id!!)
        if (isNagDue(habit, day) && time > now()) return scheduleAtTime(habit, time, true, day)
        if (nag(habit, day)) return
        widgetPreferences.removeSnoozeTime(habit.id!!)
        schedule(habit)
    }

    /**
     * [urgent]: on time even in battery saving (Doze), for nag mode.
     * [date]: the day the reminder is about, if not that of [reminderTime].
     */
    @Synchronized
    open fun scheduleAtTime(
        habit: Habit,
        reminderTime: Long,
        urgent: Boolean = false,
        date: LocalDate? = null
    ) {
        sys.log("ReminderScheduler", "Scheduling alarm for habit=" + habit.id)
        if (!habit.hasReminder()) {
            sys.log("ReminderScheduler", "habit=" + habit.id + " has no reminder. Skipping.")
            return
        }
        if (habit.isArchived) {
            sys.log("ReminderScheduler", "habit=" + habit.id + " is archived. Skipping.")
            return
        }
        val timestamp = date?.unixTime
            ?: DateUtils.getStartOfDayWithOffset(DateUtils.removeTimezone(reminderTime), 0, 0)
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
        preferences.addListener(this)
    }

    @Synchronized
    open fun stopListening() {
        commandRunner.removeListener(this)
        preferences.removeListener(this)
    }

    @Synchronized
    open fun snoozeReminder(habit: Habit, minutes: Long) {
        snoozeUntil(habit, now() + minutes * DateUtils.MINUTE_LENGTH)
    }

    @Synchronized
    open fun snoozeUntil(habit: Habit, time: Long) {
        widgetPreferences.setSnoozeTime(habit.id!!, time)
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
