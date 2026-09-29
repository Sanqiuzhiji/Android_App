package com.example.myapplication.feature.backup.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.myapplication.feature.dailyrecord.data.local.*

@Dao
interface BackupDao {
    @Query("SELECT * FROM daily_records ORDER BY date")
    suspend fun records(): List<DailyRecordEntity>
    @Query("SELECT * FROM fixed_templates ORDER BY sortOrder, id")
    suspend fun templates(): List<FixedTemplateEntity>
    @Query("SELECT * FROM tasks ORDER BY date, sortOrder, id")
    suspend fun tasks(): List<TaskEntity>

    @Query("DELETE FROM tasks")
    suspend fun clearTasks()
    @Query("DELETE FROM daily_records")
    suspend fun clearRecords()
    @Query("DELETE FROM fixed_templates")
    suspend fun clearTemplates()

    @Insert
    suspend fun insertRecords(values: List<DailyRecordEntity>)
    @Insert
    suspend fun insertTemplates(values: List<FixedTemplateEntity>)
    @Insert
    suspend fun insertTasks(values: List<TaskEntity>)
}
