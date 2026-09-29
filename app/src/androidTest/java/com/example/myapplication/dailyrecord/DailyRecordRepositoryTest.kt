package com.example.myapplication.dailyrecord

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.core.database.ToolboxDatabase
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.dailyrecord.data.LocalDailyRecordRepository
import com.example.myapplication.feature.dailyrecord.model.CalendarDayStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MutableTestClock(var current: Instant = Instant.parse("2026-09-27T04:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
    override fun withZone(zone: ZoneId): Clock = fixed(current, zone)
    override fun instant(): Instant = current
    fun nextDay() { current = current.plusSeconds(86_400) }
}

@RunWith(AndroidJUnit4::class)
class DailyRecordRepositoryTest {
    private lateinit var database: ToolboxDatabase
    private lateinit var repository: LocalDailyRecordRepository
    private lateinit var clock: MutableTestClock
    private lateinit var dates: DateProvider
    private val start = LocalDate.of(2026, 9, 27)

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ToolboxDatabase::class.java).build()
        clock = MutableTestClock()
        dates = DateProvider(clock)
        repository = LocalDailyRecordRepository(database, dates)
    }
    @After fun close() { database.close() }

    @Test fun dragDropMovesAcrossMultipleItemsAndRejectsOtherDatesOrCompletedTargets() = runBlocking {
        repeat(4) {
            repository.addTemplate("固定 $it")
            repository.addCustomTask(start, "临时 $it")
            clock.current = clock.current.plusSeconds(1)
        }
        val templates = repository.observeTemplates(start).first()
        repository.moveTemplateTo(templates.first().id, templates.last().id)
        assertEquals(listOf("固定 1", "固定 2", "固定 3", "固定 0"),
            repository.observeDay(start).first().pending.filter { it.templateId != null }.map { it.title })
        val custom = repository.observeDay(start).first().custom
        repository.setCompleted(custom[1].id, true)
        repository.moveCustomTaskTo(custom[0].id, custom[3].id)
        assertEquals(listOf("临时 2", "临时 3", "临时 0"),
            repository.observeDay(start).first().custom.filterNot { it.completed }.map { it.title })
        val before = repository.observeDay(start).first()
        repository.moveCustomTaskTo(custom[0].id, custom[1].id)
        repository.addCustomTask(start.plusDays(1), "另一天")
        val other = repository.observeDay(start.plusDays(1)).first().custom.single()
        repository.moveCustomTaskTo(custom[0].id, other.id)
        assertEquals(before, repository.observeDay(start).first())
    }

    @Test fun fixedReorderingUpdatesPendingAndFutureButPreservesSavedHistory() = runBlocking {
        repeat(3) { repository.addTemplate("固定 $it"); clock.current = clock.current.plusSeconds(1) }
        val yesterday = repository.observeDay(start).first()
        clock.nextDay()
        val today = dates.today()
        repository.ensureDay(today)
        val templates = repository.observeTemplates(today).first()
        val completed = repository.observeDay(today).first().fixed[1]
        repository.setCompleted(completed.id, true)
        val finishedBefore = repository.observeDay(today).first().finished
        repository.moveTemplate(templates.last().id, -1)
        repository.moveTemplate(templates.last().id, -1)
        assertEquals(listOf("固定 2", "固定 0"), repository.observeDay(today).first().pending.map { it.title })
        assertEquals(finishedBefore, repository.observeDay(today).first().finished)
        assertEquals(yesterday, repository.observeDay(start).first())
        val expected = listOf("固定 2", "固定 0", "固定 1")
        assertEquals(expected, repository.observeDay(today.plusDays(1)).first().fixed.map { it.title })
        repository.setCompleted(completed.id, false)
        assertEquals(expected, repository.observeDay(today).first().pending.map { it.title })
        clock.nextDay()
        repository.ensureDay(dates.today())
        assertEquals(expected, repository.observeDay(dates.today()).first().fixed.map { it.title })
    }

    @Test fun customReorderingSkipsCompletedAndStaysWithinItsDate() = runBlocking {
        repeat(3) { repository.addCustomTask(start, "临时 $it"); clock.current = clock.current.plusSeconds(1) }
        repository.addCustomTask(start.plusDays(1), "明天任务")
        val nextDay = repository.observeDay(start.plusDays(1)).first()
        val original = repository.observeDay(start).first().custom
        repository.setCompleted(original[1].id, true)
        val finishedBefore = repository.observeDay(start).first().finished
        repository.moveCustomTask(original[2].id, -1)
        val reordered = repository.observeDay(start).first()
        assertEquals(listOf("临时 2", "临时 0"), reordered.pending.map { it.title })
        assertEquals(finishedBefore, reordered.finished)
        repository.moveCustomTask(original[2].id, -1) // First pending item: no change.
        repository.moveCustomTask(original[1].id, 1) // Completed item: no change.
        assertEquals(reordered, repository.observeDay(start).first())
        assertEquals(nextDay, repository.observeDay(start.plusDays(1)).first())
        val reopenedRepository = LocalDailyRecordRepository(database, dates)
        assertEquals(reordered, reopenedRepository.observeDay(start).first())
    }

    @Test fun fixedTaskCanBeCompletedTodayAndCompletionTimeCanBeCleared() = runBlocking {
        repository.addTemplate("晨跑")
        val task = repository.observeDay(start).first().fixed.single()
        repository.setCompleted(task.id, true)
        val completed = repository.observeDay(start).first().fixed.single()
        assertTrue(completed.completed)
        assertEquals(clock.millis(), completed.completedTime)
        repository.setCompleted(task.id, false)
        assertNull(repository.observeDay(start).first().fixed.single().completedTime)
    }

    @Test fun nextDayRegeneratesFixedTasksWithoutCopyingCustomTasksOrCompletion() = runBlocking {
        repository.addTemplate("阅读30分钟")
        repository.addCustomTask(start, "购买设备")
        repository.observeDay(start).first().tasks.forEach { repository.setCompleted(it.id, true) }
        clock.nextDay()
        repository.ensureDay(dates.today())
        val tomorrow = repository.observeDay(dates.today()).first()
        assertEquals(1, tomorrow.fixed.size)
        assertFalse(tomorrow.fixed.single().completed)
        assertNull(tomorrow.fixed.single().completedTime)
        assertTrue(tomorrow.custom.isEmpty())
        val yesterday = repository.observeDay(start).first()
        assertTrue(yesterday.tasks.all { it.completed })
        assertNotEquals(yesterday.fixed.single().id, tomorrow.fixed.single().id)
    }

    @Test fun repeatedConcurrentGenerationDoesNotDuplicateOrResetTasks() = runBlocking {
        repository.addTemplate("学习英语")
        val id = repository.observeDay(start).first().fixed.single().id
        repository.setCompleted(id, true)
        coroutineScope { (1..8).map { async { repository.ensureDay(start) } }.awaitAll() }
        val day = repository.observeDay(start).first()
        assertEquals(1, day.tasks.size)
        assertTrue(day.tasks.single().completed)
    }

    @Test fun totalAndCategoryStatisticsFollowStoredTasks() = runBlocking {
        repeat(10) { repository.addTemplate("固定 $it") }
        repeat(5) { repository.addCustomTask(start, "临时 $it") }
        val day = repository.observeDay(start).first()
        (day.fixed.take(8) + day.custom.take(3)).forEach { repository.setCompleted(it.id, true) }
        val result = repository.observeDay(start).first()
        assertEquals(11, result.totalCount.completed)
        assertEquals(15, result.totalCount.total)
        assertEquals(8, result.fixedCount.completed)
        assertEquals(10, result.fixedCount.total)
        assertEquals(3, result.customCount.completed)
        assertEquals(5, result.customCount.total)
    }

    @Test fun templateEditsAndStopsPreserveUnvisitedHistory() = runBlocking {
        repository.addTemplate("旧名称")
        val originalId = repository.observeTemplates(start).first().single().id
        clock.nextDay() // Sep 28 remains unvisited.
        clock.nextDay() // Sep 29: rename effective Sep 30.
        repository.renameTemplate(originalId, "新名称")
        repository.ensureDay(start.plusDays(1))
        assertEquals("旧名称", repository.observeDay(start.plusDays(1)).first().fixed.single().title)
        clock.nextDay()
        repository.ensureDay(dates.today())
        assertEquals("新名称", repository.observeDay(dates.today()).first().fixed.single().title)
        val newId = repository.observeTemplates(dates.today()).first().single().id
        repository.stopTemplate(newId)
        clock.nextDay()
        repository.ensureDay(dates.today())
        assertTrue(repository.observeDay(dates.today()).first().fixed.isEmpty())
        assertEquals("旧名称", repository.observeDay(start).first().fixed.single().title)
    }

    @Test fun newTemplatesDoNotAppearBeforeTheirStartDate() = runBlocking {
        repository.addTemplate("今天开始")
        repository.ensureDay(start.minusDays(7))
        assertTrue(repository.observeDay(start.minusDays(7)).first().tasks.isEmpty())
        repository.addCustomTask(start.minusDays(7), "补记")
        assertEquals("补记", repository.observeDay(start.minusDays(7)).first().custom.single().title)
        assertTrue(repository.observeDay(start).first().custom.isEmpty())
    }

    @Test fun scheduledTemplateCanBeEditedAgainAndCancelled() = runBlocking {
        repository.addTemplate("原名称")
        repository.renameTemplate(repository.observeTemplates(start).first().single().id, "明天名称")
        val next = repository.observeTemplates(start).first().single { it.effectiveUntil == null }
        repository.renameTemplate(next.id, "再次修改")
        repository.stopTemplate(next.id)
        clock.nextDay()
        repository.ensureDay(dates.today())
        assertTrue(repository.observeDay(dates.today()).first().fixed.isEmpty())
        assertEquals("原名称", repository.observeDay(start).first().fixed.single().title)
    }

    @Test fun tasksSurviveDatabaseCloseAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-test-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, ToolboxDatabase::class.java, name).build()
        var disk = open()
        try {
            val first = LocalDailyRecordRepository(disk, dates)
            first.addCustomTask(start, "永久记录")
            val id = first.observeDay(start).first().tasks.single().id
            first.setCompleted(id, true)
            disk.close()
            disk = open()
            val restored = LocalDailyRecordRepository(disk, dates).observeDay(start).first().tasks.single()
            assertEquals("永久记录", restored.title)
            assertTrue(restored.completed)
            assertNotNull(restored.completedTime)
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun invalidTitlesAreRejected() = runBlocking {
        try { repository.addTemplate("   "); fail("Blank title was accepted") } catch (_: IllegalArgumentException) { }
        try { repository.addTemplate("x".repeat(101)); fail("Long title was accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(repository.observeTemplates(start).first().isEmpty())
    }

    @Test fun futurePlanningUsesLivePreviewsAndIndependentCustomTasks() = runBlocking {
        repository.addTemplate("阅读")
        val tomorrow = start.plusDays(1)
        repository.ensureDay(tomorrow)
        repository.addCustomTask(tomorrow, "项目会议")
        val future = repository.observeDay(tomorrow).first()
        assertTrue(future.fixed.single().isPreview)
        assertEquals("项目会议", future.custom.single().title)
        assertTrue(database.dailyRecordDao().observeTasks(tomorrow.toString()).first().all { it.type == "CUSTOM" })
        repository.renameCustomTask(future.custom.single().id, "项目评审")
        repository.setCompleted(future.custom.single().id, true)
        val template = repository.observeTemplates(start).first().single()
        repository.renameTemplate(template.id, "阅读30分钟")
        val updated = repository.observeDay(tomorrow).first()
        assertEquals("阅读30分钟", updated.fixed.single().title)
        assertEquals("项目评审", updated.custom.single().title)
        assertTrue(updated.custom.single().completed)
        clock.nextDay()
        repository.ensureDay(tomorrow)
        val actual = repository.observeDay(tomorrow).first()
        assertFalse(actual.fixed.single().isPreview)
        assertFalse(actual.fixed.single().completed)
        assertTrue(actual.custom.single().completed)
        assertTrue(repository.observeDay(tomorrow.plusDays(1)).first().custom.isEmpty())
    }

    @Test fun calendarProjectsUnvisitedDaysWithoutWritingTasksAndFollowsChanges() = runBlocking {
        repository.addTemplate("晨跑")
        repository.addCustomTask(start.plusDays(2), "买设备")
        val before = database.dailyRecordDao().observeTasksInRange(start.toString(), start.plusDays(6).toString()).first().size
        val summary = repository.observeCalendar(start.minusDays(1), start.plusDays(6), start).first()
        assertEquals(CalendarDayStatus.ORDINARY, summary.first().status)
        assertEquals(CalendarDayStatus.PENDING, summary.single { it.date == start }.status)
        assertEquals(CalendarDayStatus.ORDINARY, summary.single { it.date == start.plusDays(1) }.status)
        assertEquals(CalendarDayStatus.PLANNED, summary.single { it.date == start.plusDays(2) }.status)
        assertEquals(before, database.dailyRecordDao().observeTasksInRange(start.toString(), start.plusDays(6).toString()).first().size)
        repository.setCompleted(repository.observeDay(start).first().fixed.single().id, true)
        assertEquals(CalendarDayStatus.COMPLETED, repository.observeCalendar(start, start, start).first().single().status)
    }

    @Test fun stoppingATemplateRemovesFuturePreviewButKeepsHistoryAndPlans() = runBlocking {
        repository.addTemplate("英语")
        val tomorrow = start.plusDays(1)
        repository.addCustomTask(tomorrow, "客户沟通")
        repository.ensureDay(tomorrow)
        repository.stopTemplate(repository.observeTemplates(start).first().single().id)
        val future = repository.observeDay(tomorrow).first()
        assertTrue(future.fixed.isEmpty())
        assertEquals("客户沟通", future.custom.single().title)
        assertEquals("英语", repository.observeDay(start).first().fixed.single().title)
    }

    @Test fun stoppedTemplatesAreSeparatedAndCanBeReactivated() = runBlocking {
        repository.addTemplate("拉伸")
        val original = repository.observeTemplates(start).first().single()
        repository.stopTemplate(original.id)

        assertTrue(repository.observeTemplates(start).first().isEmpty())
        assertEquals("拉伸", repository.observeStoppedTemplates().first().single().title)

        // Re-enabling before tomorrow cancels the scheduled stop instead of duplicating it.
        repository.reactivateTemplate(original.id)
        assertEquals(original.id, repository.observeTemplates(start).first().single().id)
        assertTrue(repository.observeStoppedTemplates().first().isEmpty())

        repository.stopTemplate(original.id)
        clock.nextDay()
        repository.ensureDay(dates.today())
        repository.reactivateTemplate(original.id)
        val activeAgain = repository.observeTemplates(dates.today()).first().single()
        assertNotEquals(original.id, activeAgain.id)
        assertEquals("拉伸", activeAgain.title)
        assertEquals(1, repository.observeStoppedTemplates().first().size)
        assertEquals("拉伸", repository.observeDay(dates.today()).first().fixed.single().title)
    }
}
