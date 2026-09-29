package com.example.myapplication.feature.dailyrecord.model

import java.time.LocalDate

enum class CalendarDayStatus { ORDINARY, PLANNED, COMPLETED, PENDING }

data class CalendarDaySummary(
    val date: LocalDate,
    val total: Int,
    val completed: Int,
    val customCount: Int,
    val status: CalendarDayStatus,
)

/** Pure rule shared by calendar and tests. Future plans are never overdue. */
fun DailyRecord.calendarSummary(today: LocalDate): CalendarDaySummary {
    val count = totalCount
    val status = when {
        date > today -> if (custom.isNotEmpty()) CalendarDayStatus.PLANNED else CalendarDayStatus.ORDINARY
        count.total == 0 -> CalendarDayStatus.ORDINARY
        count.completed == count.total -> CalendarDayStatus.COMPLETED
        else -> CalendarDayStatus.PENDING
    }
    return CalendarDaySummary(date, count.total, count.completed, custom.size, status)
}

/** Read-only projection: no database writes when browsing a month or a future day. */
fun projectDailyRecord(
    date: LocalDate,
    stored: List<Task>,
    templates: List<FixedTaskTemplate>,
): DailyRecord {
    val existing = stored.mapNotNull { it.templateId }.toSet()
    val previews = templates.filter {
        it.effectiveFrom <= date && (it.effectiveUntil == null || date < it.effectiveUntil) && it.id !in existing
    }.map {
        Task("preview:$date:${it.id}", it.title, date, TaskType.FIXED, false, null, it.id, isPreview = true)
    }
    return DailyRecord(date, stored + previews)
}
