package com.example.myapplication.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.myapplication.feature.dailyrecord.ui.*
import com.example.myapplication.feature.toolbox.ToolboxScreen
import com.example.myapplication.feature.dailyrecord.data.DailyRecordRepository
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.core.preferences.CalendarPreferences
import java.time.LocalDate
import com.example.myapplication.feature.backup.data.BackupRepository
import com.example.myapplication.feature.backup.data.BackupFiles
import com.example.myapplication.feature.backup.ui.BackupScreen
import com.example.myapplication.feature.backup.ui.BackupViewModel

private object Routes {
    const val HOME = "toolbox"
    const val DAILY = "daily-record"
    const val TEMPLATES = "fixed-templates"
    const val STOPPED_TEMPLATES = "stopped-fixed-templates"
    const val CALENDAR = "calendar"
    const val BACKUP = "backup"
}

@Composable
fun ToolboxApp(
    records: DailyRecordRepository,
    dates: DateProvider,
    preferences: CalendarPreferences,
    backups: BackupRepository,
    backupFiles: BackupFiles,
    reminderDate: String? = null,
    onReminderOpened: () -> Unit = {},
) {
    val nav = rememberNavController()
    LaunchedEffect(reminderDate) {
        if (reminderDate != null) {
            nav.navigate(Routes.DAILY) { launchSingleTop = true }
            nav.currentBackStackEntry?.savedStateHandle?.set("calendarSelection", reminderDate)
            onReminderOpened()
        }
    }
    // Switch directly so an outgoing screen cannot cover the visible destination
    // during a fade and intercept a quick tap after returning.
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.HOME) {
            ToolboxScreen(
                onDailyRecord = { nav.navigate(Routes.DAILY) { launchSingleTop = true } },
                onBackup = { nav.navigate(Routes.BACKUP) { launchSingleTop = true } },
                onReminders = { nav.navigate("reminders") { launchSingleTop = true } },
                onReminderBackup = { nav.navigate("reminder-backup") { launchSingleTop = true } },
            )
        }
        composable("reminders") {
            com.example.myapplication.feature.reminders.RemindersScreen(records, onBack = { nav.popBackStack() }, onDay = { date ->
                nav.navigate(Routes.DAILY) { launchSingleTop = true }
                nav.currentBackStackEntry?.savedStateHandle?.set("calendarSelection", date)
            })
        }
        composable("reminder-backup") {
            com.example.myapplication.feature.reminders.ReminderBackupScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.DAILY) { entry ->
            val model: DailyRecordViewModel = viewModel(factory = viewModelFactory {
                initializer { DailyRecordViewModel(records, dates, createSavedStateHandle()) }
            })
            val calendarSelection by entry.savedStateHandle.getStateFlow<String?>("calendarSelection", null).collectAsStateWithLifecycle()
            LaunchedEffect(calendarSelection) {
                calendarSelection?.let { model.selectDate(LocalDate.parse(it)); entry.savedStateHandle["calendarSelection"] = null }
            }
            DailyRecordScreen(model, onBack = { nav.popBackStack() }, onTemplates = {
                nav.navigate(Routes.TEMPLATES) { launchSingleTop = true }
            }, onCalendar = { date -> nav.navigate("${Routes.CALENDAR}/$date") { launchSingleTop = true } })
        }
        composable("${Routes.CALENDAR}/{date}") {
            val model: CalendarViewModel = viewModel(factory = viewModelFactory {
                initializer { CalendarViewModel(records, dates, createSavedStateHandle()) }
            })
            CalendarScreen(model, preferences, onBack = { nav.popBackStack() }, onSelect = { date ->
                nav.previousBackStackEntry?.savedStateHandle?.set("calendarSelection", date.toString())
                nav.popBackStack()
            })
        }
        composable(Routes.TEMPLATES) {
            val model: FixedTemplatesViewModel = viewModel(factory = viewModelFactory {
                initializer { FixedTemplatesViewModel(records, dates) }
            })
            FixedTemplatesScreen(model, dates, onBack = { nav.popBackStack() }, onStopped = {
                nav.navigate(Routes.STOPPED_TEMPLATES) { launchSingleTop = true }
            })
        }
        composable(Routes.STOPPED_TEMPLATES) {
            val model: FixedTemplatesViewModel = viewModel(factory = viewModelFactory {
                initializer { FixedTemplatesViewModel(records, dates) }
            })
            StoppedTemplatesScreen(model, onBack = { nav.popBackStack() })
        }
        composable(Routes.BACKUP) {
            val model: BackupViewModel = viewModel(factory = viewModelFactory {
                initializer { BackupViewModel(backups, backupFiles) }
            })
            BackupScreen(model, onBack = { nav.popBackStack() })
        }
    }
}
