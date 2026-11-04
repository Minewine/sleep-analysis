package com.sleepanalysis.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatting helpers shared by report/home/history screens. */
object Formatting {

    private val dateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", Locale.getDefault())
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun dateTime(epochMs: Long): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun time(epochMs: Long): String =
        timeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** e.g. "7h 26m" or "42m" or "18s". */
    fun duration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${sec}s"
        }
    }

    fun durationMs(totalMs: Long): String = duration(totalMs / 1000)

    fun dbfs(value: Double): String = String.format(Locale.getDefault(), "%.1f dBFS", value)

    /** 24-hour clock time from minutes-of-day (hour*60+min), e.g. "22:30". */
    fun minutesOfDay(minutes: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60)
}

fun formatDateTime(epochMs: Long): String = Formatting.dateTime(epochMs)

fun formatDuration(totalMs: Long): String = Formatting.durationMs(totalMs)

fun formatMinutesOfDay(minutes: Int): String = Formatting.minutesOfDay(minutes)
