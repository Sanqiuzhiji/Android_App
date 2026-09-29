package com.example.myapplication

import com.example.myapplication.feature.dailyrecord.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CalendarRulesTest {
    @Test fun monthGridShowsOnlyItsOwnDatesAndPadsWeekdaysWithBlanks() {
        for (month in listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 2), YearMonth.of(2024, 2), YearMonth.of(2026, 8))) {
            val rows = monthGrid(month)
            assertTrue(rows.all { it.size == 7 })
            val dates = rows.flatten().filterNotNull()
            assertEquals((1..month.lengthOfMonth()).map { month.atDay(it) }, dates)
            assertEquals(month.atDay(1).dayOfWeek.value - 1, rows.flatten().indexOf(month.atDay(1)))
            assertTrue(dates.all { YearMonth.from(it) == month })
        }
    }
    private val today = LocalDate.of(2026, 9, 27)
    private fun task(id: String, completed: Boolean, type: TaskType = TaskType.FIXED, date: LocalDate = today) =
        Task(id, id, date, type, completed, if (completed) 1L else null)

    @Test fun statusPrecedenceDistinguishesPlansFromPendingWork() {
        assertEquals(CalendarDayStatus.ORDINARY, DailyRecord(today, emptyList()).calendarSummary(today).status)
        assertEquals(CalendarDayStatus.PENDING, DailyRecord(today, listOf(task("fixed", false))).calendarSummary(today).status)
        assertEquals(CalendarDayStatus.COMPLETED, DailyRecord(today, listOf(task("fixed", true))).calendarSummary(today).status)
        assertEquals(CalendarDayStatus.PENDING, DailyRecord(today, listOf(task("fixed", true), task("custom", false, TaskType.CUSTOM))).calendarSummary(today).status)
        val future = today.plusDays(1)
        assertEquals(CalendarDayStatus.ORDINARY, DailyRecord(future, listOf(task("fixed", false, date = future))).calendarSummary(today).status)
        // Even an early-completed appointment remains a planned future day.
        assertEquals(CalendarDayStatus.PLANNED, DailyRecord(future, listOf(task("custom", true, TaskType.CUSTOM, future))).calendarSummary(today).status)
        val past = today.minusDays(1)
        assertEquals(CalendarDayStatus.COMPLETED, DailyRecord(past, listOf(task("past", true, date = past))).calendarSummary(today).status)
    }

    @Test fun completingAndUncompletingMovesBetweenSectionsWithoutLosingTask() {
        val original = task("a", false)
        val day = DailyRecord(today, listOf(task("b", true), original))
        assertEquals(listOf("a"), day.pending.map { it.id })
        assertEquals(listOf("b"), day.finished.map { it.id })
        val completed = day.copy(tasks = day.tasks.map { if (it.id == "a") it.copy(completed = true, completedTime = 10L) else it })
        assertTrue(completed.pending.isEmpty())
        assertEquals(2, completed.finished.size)
        val undone = completed.copy(tasks = completed.tasks.map { if (it.id == "a") it.copy(completed = false, completedTime = null) else it })
        assertEquals(listOf(original), undone.pending)
    }

    @Test fun templateIntervalsAndSnapshotsProjectCorrectly() {
        val tomorrow = today.plusDays(1)
        val templates = listOf(
            FixedTaskTemplate("old", "旧名字", today, tomorrow),
            FixedTaskTemplate("new", "新名字", tomorrow, null),
        )
        val snapshot = task("stored", true).copy(title = "旧名字", templateId = "old")
        assertEquals(listOf(snapshot), projectDailyRecord(today, listOf(snapshot), templates).tasks)
        val future = projectDailyRecord(tomorrow, emptyList(), templates)
        assertEquals("新名字", future.fixed.single().title)
        assertTrue(future.fixed.single().isPreview)
        assertTrue(projectDailyRecord(today.minusDays(1), emptyList(), templates).tasks.isEmpty())
    }
}
