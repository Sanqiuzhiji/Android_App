package com.example.myapplication

import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.dailyrecord.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DailyRecordTest {
    @Test fun completedTasksFollowCompletionTimeAcrossCategories() {
        val date = LocalDate.of(2026, 9, 27)
        val record = DailyRecord(date, listOf(
            Task("fixed", "固定", date, TaskType.FIXED, true, 30L),
            Task("pending", "未完成", date, TaskType.CUSTOM, false, null),
            Task("custom", "临时", date, TaskType.CUSTOM, true, 10L),
        ))
        assertEquals(listOf("custom", "fixed"), record.finished.map { it.id })
        assertEquals(listOf("pending"), record.pending.map { it.id })
    }
    @Test fun emptyDayHasZeroCounts() {
        val day = DailyRecord(LocalDate.of(2026, 9, 27), emptyList())
        assertEquals(CompletionCount(0, 0), day.totalCount)
        assertEquals(CompletionCount(0, 0), day.fixedCount)
        assertEquals(CompletionCount(0, 0), day.customCount)
    }

    @Test fun localDateUsesLocalMidnightInsteadOfUtcMidnight() {
        val clock = Clock.fixed(Instant.parse("2026-09-27T16:00:00Z"), ZoneId.of("Asia/Shanghai"))
        assertEquals(LocalDate.of(2026, 9, 28), DateProvider(clock).today())
    }

    @Test fun countsIncludeCompletedItemsWithoutRemovingThem() {
        val date = LocalDate.of(2026, 9, 27)
        val day = DailyRecord(date, listOf(
            Task("a", "晨跑", date, TaskType.FIXED, true, 1L),
            Task("b", "阅读", date, TaskType.FIXED, false, null),
            Task("c", "买菜", date, TaskType.CUSTOM, true, 2L),
        ))
        assertEquals(CompletionCount(2, 3), day.totalCount)
        assertEquals(CompletionCount(1, 2), day.fixedCount)
        assertEquals(CompletionCount(1, 1), day.customCount)
    }
}
