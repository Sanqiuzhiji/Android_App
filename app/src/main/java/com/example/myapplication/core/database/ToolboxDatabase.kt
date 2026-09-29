package com.example.myapplication.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.myapplication.feature.backup.data.BackupDao
import com.example.myapplication.feature.dailyrecord.data.local.DailyRecordDao
import com.example.myapplication.feature.dailyrecord.data.local.DailyRecordEntity
import com.example.myapplication.feature.dailyrecord.data.local.FixedTemplateEntity
import com.example.myapplication.feature.dailyrecord.data.local.TaskEntity

@Database(
    entities = [DailyRecordEntity::class, FixedTemplateEntity::class, TaskEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ToolboxDatabase : RoomDatabase() {
    abstract fun dailyRecordDao(): DailyRecordDao
    abstract fun backupDao(): BackupDao
}
