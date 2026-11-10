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
            onRepetitionChanged(command.habit, command.date)
            return
        }
        if (command is ChangeHabitColorCommand) return
        scheduleAll()
    }

    // Nag mode switched on or off, or its interval changed: apply it now
    // rather than at each habit's next reminder.
    @Synchronized
    override fun onNagIntervalChanged() {
        sys.log("ReminderScheduler", "Nag interval changed. Rescheduling.")
        for (habit in habitList.getFiltered(HabitMatcher.WITH_ALARM)) {
            if (!restartNag(habit)) schedule(habit)
        }
    }

    /**
     * Nag mode: when the reminder for [date] has gone off and the habit is
     * still not done, show it again every few minutes until it is done or the
     * day ends. The repeat is stored like a snooze, together with [date], so
     * it survives app restarts and reboots.
     *
     * Returns false when there is nothing to nag about.
     */
    @Synchronized
    open fun nag(habit: Habit, date: LocalDate): Boolean {
        val id = habit.id ?: return false
        val next = nextNagTime(habit, date) ?: return false
        sys.log("ReminderScheduler", "Nagging habit=$id about $date again at $next")
        widgetPreferences.setNagTime(id, next, date.daysSince2000)
        schedule(habit)
        return true
    }

    /**
     * When the habit should be shown again for [date], or null if it should
     * not be (see [isNagDue]) or the next repeat would fall after the end of
     * that day.
     */
    open fun nextNagTime(habit: Habit, date: LocalDate): Long? {
        if (!isNagDue(habit, date)) return null
        val next = now() + preferences.nagIntervalMinutes * DateUtils.MINUTE_LENGTH
        if (next >= nagEnd(habit, date)) return null
        return next
    }

    /**
     * Whether the habit should be nagging about [date] right now: nag mode is
     * on, the habit's reminder for that day has gone off, the habit is due
     * that day and not done (or skipped), and the day is not over.
     */
    open fun isNagDue(habit: Habit, date: LocalDate): Boolean {
        if (preferences.nagIntervalMinutes <= 0) return false
        if (habit.id == null || habit.isArchived) return false
        val reminder = habit.reminder ?: return false
        // An "at most" target is met by doing nothing, so there is nothing to
        // nag about; its reminder still shows once, as before.
        if (habit.isNumerical && habit.targetType == NumericalHabitType.AT_MOST) return false
        val weekday = (date.dayOfWeek.daysSinceSunday + 1) % 7
        if (!reminder.days.toArray()[weekday]) return false
        if (habit.isCompletedOn(date)) return false
        val now = now()
        return reminderTimeOn(habit, date) <= now && now < nagEnd(habit, date)
    }

    // The nag about [date] ends when that day ends, which is a few hours
    // after midnight when "midnight delay" is on, or at the next reminder,
    // whichever comes first. The next reminder then takes over.
    private fun nagEnd(habit: Habit, date: LocalDate): Long {
        val nextDay = date.plus(1)
        val dayEnd = DateUtils.applyTimezone(
            nextDay.unixTime + preferences.midnightDelayHours * DateUtils.HOUR_LENGTH
        )
        return minOf(dayEnd, reminderTimeOn(habit, nextDay))
    }

    private fun reminderTimeOn(habit: Habit, date: LocalDate): Long {
        val reminder = habit.reminder!!
        return DateUtils.applyTimezone(
            date.unixTime +
                reminder.hour * DateUtils.HOUR_LENGTH +
                reminder.minute * DateUtils.MINUTE_LENGTH
        )
    }

    // The day of the habit's most recent reminder.
    private fun lastReminderDay(habit: Habit): LocalDate? {
        if (habit.reminder == null) return null
        val now = now()
        val today = calendarDay(now)
        return if (reminderTimeOn(habit, today) <= now) today else today.minus(1)
    }

    private fun nagDayOf(id: Long): LocalDate? = widgetPreferences.getNagDay(id)?.let { LocalDate(it) }

    // Checking the habit (from the app, a widget, the notification or a
    // watch) ends the nag at once instead of letting one more alarm fire.
    // Unchecking it, or lowering a numerical value below the target, after
    // the nag has stopped starts it again, if that day's nag is still due
    // (which also means it is the latest reminder's day). A pending manual
    // snooze is left alone. Nag mode off: upstream behaviour.
    private fun onRepetitionChanged(habit: Habit, date: LocalDate) {
        if (preferences.nagIntervalMinutes <= 0) return
        val id = habit.id ?: return
        val nagDay = nagDayOf(id)
        if (nagDay != null) {
            if (isNagDue(habit, nagDay)) return
            sys.log("ReminderScheduler", "Habit $id is done. Stopping the nag.")
            widgetPreferences.removeSnoozeTime(id)
            schedule(habit)
        } else if (widgetPreferences.getSnoozeTime(id) <= now()) {
            nag(habit, date)
        }
    }

    // Applies the current nag interval to [habit]: restarts its nag, or
    // starts one when its last reminder went off unanswered. A pending manual
    // snooze is left alone. Returns false if the habit is not nagging.
    private fun restartNag(habit: Habit): Boolean {
        val id = habit.id ?: return false
        var date = nagDayOf(id)
        if (date == null) {
            if (widgetPreferences.getSnoozeTime(id) > now()) return false
            date = lastReminderDay(habit) ?: return false
        }
        return nag(habit, date)
    }

    private fun now(): Long = DateUtils.applyTimezone(DateUtils.getLocalTime())

    private fun calendarDay(time: Long): LocalDate =
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
        val id = habit.id!!
        var reminderTime = DateUtils.getUpcomingTimeInMillis(
            habit.reminder!!.hour,
            habit.reminder!!.minute
        )
        var urgent = false
        var date: LocalDate? = null
        val snoozeReminderTime = widgetPreferences.getSnoozeTime(id)
        if (snoozeReminderTime != 0L) {
            val now = now()
            val nagDay = nagDayOf(id)
            sys.log("ReminderScheduler", "Habit $id has been snoozed until $snoozeReminderTime (nag day $nagDay)")
            // A nag repeat ends when the habit is done, the reminder is
            // moved later or off that day, nag mode is switched off, or the
            // day is over. A repeat missed while the phone was off or the app
            // was killed resumes. A manual snooze works as upstream, except
            // that in nag mode a missed one resumes nagging too.
            val day = nagDay ?: calendarDay(snoozeReminderTime)
            if (nagDay != null && !isNagDue(habit, nagDay)) {
                sys.log("ReminderScheduler", "Nag no longer due. Discarding.")
                widgetPreferences.removeSnoozeTime(id)
            } else if (snoozeReminderTime > now) {
                sys.log("ReminderScheduler", "Snooze time is in the future. Accepting.")
                reminderTime = snoozeReminderTime
                urgent = preferences.nagIntervalMinutes > 0
                date = nagDay
            } else {
                val resumed = nextNagTime(habit, day)
                if (resumed != null) {
                    sys.log("ReminderScheduler", "Missed a nag. Resuming at $resumed.")
                    widgetPreferences.setNagTime(id, resumed, day.daysSince2000)
                    reminderTime = resumed
                    urgent = true
                    date = day
                } else {
                    sys.log("ReminderScheduler", "Snooze time is in the past. Discarding.")
                    widgetPreferences.removeSnoozeTime(id)
                }
            }
        }
        scheduleAtTime(habit, reminderTime, urgent, date)
    }

    /**
     * [urgent] asks the system to deliver the alarm on time even in battery
     * saving (Doze), where ordinary alarms may be held back for 10+ minutes.
     * Used in nag mode only. [date] is the day the reminder is about, by
     * default the day of [reminderTime]; a nag after midnight (with midnight
     * delay on) is still about the day before.
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

    /** Manual snooze to [time]. In nag mode, nagging resumes after it. */
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
