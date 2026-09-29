package com.example.myapplication.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.myapplication.core.database.ToolboxDatabase
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.core.preferences.LocalCalendarPreferences
import com.example.myapplication.feature.dailyrecord.data.DailyRecordRepository
import com.example.myapplication.feature.dailyrecord.data.LocalDailyRecordRepository
import com.example.myapplication.feature.backup.data.BackupRepository
import com.example.myapplication.feature.backup.data.BackupFiles
import com.example.myapplication.feature.reminders.ReminderRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate

class ToolboxApplication : Application() {
    val container by lazy { AppContainer(this) }
    private val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        reminderScope.launch {
            container.reminders.points.map { points -> points.map { it.date }.distinct().sorted() }.distinctUntilChanged()
                .flatMapLatest { dates ->
                    if (dates.isEmpty()) flowOf(Unit)
                    else combine(dates.map { container.records.observeDay(LocalDate.parse(it)) }) { Unit }
                }.collect { ReminderRuntime.refreshTasks(this@ToolboxApplication) }
        }
    }
}

class AppContainer(context: Context) {
    val reminders = com.example.myapplication.feature.reminders.ReminderStore(context)
    val dates = DateProvider()
    val calendarPreferences = LocalCalendarPreferences(context)
    private val database = Room.databaseBuilder(
        context.applicationContext, ToolboxDatabase::class.java, "toolbox.db",
    ).build()
    val records: DailyRecordRepository = LocalDailyRecordRepository(database, dates)
    val backups = BackupRepository(database, dates)
    val backupFiles = BackupFiles(context)
}
