package com.example.myapplication.dailyrecord

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myapplication.app.ToolboxApp
import com.example.myapplication.core.database.ToolboxDatabase
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.core.preferences.CalendarPreferences
import com.example.myapplication.feature.dailyrecord.data.LocalDailyRecordRepository
import com.example.myapplication.feature.backup.data.BackupRepository
import com.example.myapplication.feature.backup.data.BackupFiles
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DailyRecordUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ToolboxDatabase
    private lateinit var repository: LocalDailyRecordRepository
    private val dates = DateProvider(MutableTestClock())
    private val preferences = object : CalendarPreferences {
        override var showStatusText = false
    }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ToolboxDatabase::class.java).build()
        repository = LocalDailyRecordRepository(database, dates)
    }
    @After fun close() { database.close() }

    private fun start(dark: Boolean = false) {
        val backups = BackupRepository(database, dates)
        val files = BackupFiles(ApplicationProvider.getApplicationContext(), "backup-ui-test-locations")
        compose.setContent { MyApplicationTheme(darkTheme = dark, dynamicColor = false) { ToolboxApp(repository, dates, preferences, backups, files) } }
        compose.onNodeWithText("每日记录").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("当日完成").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun addFixedAndCustomTasksCompleteAndBrowseHistory() {
        start()
        compose.onNodeWithText("固定清单").performClick()
        compose.onNodeWithText("＋ 添加固定任务").performClick()
        compose.onNodeWithText("任务名称").performTextInput("阅读30分钟")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("阅读30分钟").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("保存").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("返回").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("阅读30分钟") and isToggleable()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("阅读30分钟") and isToggleable()).performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeDay(dates.today()).first().fixed.single().completed } }
        compose.onNodeWithText("＋ 添加任务").performClick()
        compose.onNodeWithText("任务名称").performTextInput("修改代码")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("保存").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onNodeWithText("前一天").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("2026-09-26").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("回到今天").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("阅读30分钟") and isToggleable()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("daily-task-list").performScrollToNode(hasText("阅读30分钟") and isToggleable())
        compose.onNode(hasText("阅读30分钟") and isToggleable()).assertIsOn()
    }

    @Test fun fullCalendarShowsInlineLegendAndAllowsFutureSelection() {
        start(dark = true)
        compose.onNodeWithText("2026-09-27").performClick()
        compose.onNodeWithText("认识日历颜色").assertDoesNotExist()
        compose.onAllNodesWithText("状态说明").assertCountEquals(1)
        compose.onNodeWithTag("calendar-day-2026-08-31").assertDoesNotExist()
        compose.onNodeWithTag("calendar-day-2026-10-01").assertDoesNotExist()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("calendar-day-2026-09-28").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("calendar-day-2026-09-28").performClick()
        compose.onNodeWithText("2026-09-28").assertExists()
        compose.onNodeWithText("＋ 添加任务").performClick()
        compose.onNodeWithText("任务名称").performTextInput("明天开会")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeDay(dates.today().plusDays(1)).first().custom.isNotEmpty() } }
        compose.onNodeWithText("2026-09-28").performClick()
        compose.onNodeWithText("认识日历颜色").assertDoesNotExist()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("calendar-day-2026-09-28") and hasContentDescription("2026-09-28，已安排，完成 0/1")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun completedTaskMovesBelowPendingTaskAndReturnsWhenUnchecked() {
        runBlocking {
            repository.addCustomTask(dates.today(), "先完成这项")
            repository.addCustomTask(dates.today(), "保留未完成")
        }
        start()
        val toggle = hasText("先完成这项") and isToggleable()
        compose.onNodeWithTag("daily-task-list").performScrollToNode(toggle)
        compose.onNode(toggle).performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeDay(dates.today()).first().finished.size == 1 } }
        compose.onNodeWithTag("daily-task-list").performScrollToNode(toggle)
        compose.onNode(toggle).assertIsOn()
        val completedBounds = compose.onNode(toggle).fetchSemanticsNode().boundsInRoot
        val headingBounds = compose.onNodeWithTag("section-completed").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(completedBounds.top >= headingBounds.bottom)
        compose.onNode(toggle).performClick()
        compose.waitUntil(10_000) { runBlocking { repository.observeDay(dates.today()).first().finished.isEmpty() } }
        compose.onNodeWithTag("daily-task-list").performScrollToNode(toggle)
        compose.onNode(toggle).assertIsOff()
    }

    @Test fun backupEntryExplainsFileLocationAndReplacementBeforeOpeningPicker() {
        start()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("打开  →").assertDoesNotExist()
        compose.onNodeWithText("备份与恢复").performClick()
        compose.onNodeWithText("导出备份 · 选择保存位置").assertExists()
        compose.onNodeWithText("选择文件导入").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("选择每日记录的 JSON 备份。先检查内容，再确认恢复；只替换每日记录的数据，不影响其他工具。建议先导出当前记录。").assertExists()
    }
}
