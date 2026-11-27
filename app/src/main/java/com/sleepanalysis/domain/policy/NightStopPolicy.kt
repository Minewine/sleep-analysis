package com.sleepanalysis.domain.policy

import java.time.Duration
import java.time.ZonedDateTime

/**
 * Pure decision for the manual-stop confirmation (Home): a stop tap BEFORE tonight's scheduled
 * end is likely premature — the user may be briefly awake, and stopping finalizes the session,
 * ending capture for the whole night (dogfood 2026-11-07→08: a 04:13 manual stop cut 2.5 h the
 * watch still recorded). After the scheduled end the night is considered over: stop directly.
 *
 * Only meaningful while a recording is active; scheduled stops
 * (`ScheduledStopReceiver`) bypass the dialog by construction. The caller computes
 * [nextEnd] (`ScheduleTimes.nextOccurrence`) — this policy stays schedule-math-free.
 */
object NightStopPolicy {

    /**
     * How far after wake time a stop still counts as "the same night" — a stop at 07:00 with a
     * 06:30 end asks nothing (the night is over), but the next end occurrence is tomorrow, so
     * the gap alone can't decide: it must also be SHORT (inside tonight). Beyond this horizon
     * the stop is treated as daytime and confirmed-free.
     */
    const val NIGHT_WINDOW_HOURS = 12L

    /**
     * True when the stop tap should ask first: the schedule is on and tonight's end is still
     * ahead within [NIGHT_WINDOW_HOURS]. A disabled schedule never confirms — it is the only
     * night-shape signal the policy has.
     */
    fun shouldConfirmStop(now: ZonedDateTime, scheduleEnabled: Boolean, nextEnd: ZonedDateTime?): Boolean {
        if (!scheduleEnabled || nextEnd == null) return false
        return Duration.between(now, nextEnd).toHours() < NIGHT_WINDOW_HOURS
    }
}
