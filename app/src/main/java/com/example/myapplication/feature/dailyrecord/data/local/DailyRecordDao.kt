package com.example.myapplication.feature.dailyrecord.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyRecordDao {
    @Query("UPDATE fixed_templates SET sortOrder = :position WHERE id = :id")
    suspend fun setTemplateOrder(id: String, position: Long)

    @Query("UPDATE tasks SET sortOrder = :position WHERE templateId = :id AND date >= :today AND completed = 0")
    suspend fun setPendingFixedOrder(id: String, today: String, position: Long)

    @Query("UPDATE tasks SET sortOrder = :position WHERE id = :id AND type = 'CUSTOM'")
    suspend fun setCustomOrder(id: String, position: Long)

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE date = :date AND type = 'CUSTOM' ORDER BY sortOrder, id")
    suspend fun customTasksFor(date: String): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecord(record: DailyRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Insert
    suspend fun insertTemplate(template: FixedTemplateEntity)

    @Query("SELECT * FROM tasks WHERE date = :date ORDER BY sortOrder, id")
    fun observeTasks(date: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE date >= :from AND date <= :until ORDER BY date, sortOrder, id")
    fun observeTasksInRange(from: String, until: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM fixed_templates ORDER BY sortOrder, effectiveFrom, id")
    fun observeAllTemplates(): Flow<List<FixedTemplateEntity>>

    @Query("UPDATE tasks SET title = :title WHERE id = :id AND type = 'CUSTOM'")
    suspend fun renameCustomTask(id: String, title: String)

    @Query("SELECT * FROM fixed_templates WHERE effectiveFrom <= :date AND (effectiveUntil IS NULL OR effectiveUntil > :date) ORDER BY sortOrder, id")
    suspend fun templatesFor(date: String): List<FixedTemplateEntity>

    @Query("SELECT * FROM fixed_templates WHERE effectiveUntil IS NULL ORDER BY sortOrder, effectiveFrom, id")
    fun observeTemplates(): Flow<List<FixedTemplateEntity>>

    @Query("SELECT * FROM fixed_templates WHERE effectiveUntil IS NOT NULL ORDER BY effectiveUntil DESC, effectiveFrom DESC, id")
    fun observeStoppedTemplates(): Flow<List<FixedTemplateEntity>>

    @Query("SELECT * FROM fixed_templates WHERE id = :id")
    suspend fun template(id: String): FixedTemplateEntity?

    @Query("UPDATE fixed_templates SET effectiveUntil = :until WHERE id = :id")
    suspend fun endTemplate(id: String, until: String)

    @Query("UPDATE fixed_templates SET effectiveUntil = NULL WHERE id = :id")
    suspend fun cancelTemplateEnd(id: String)

    @Query("UPDATE fixed_templates SET title = :title WHERE id = :id")
    suspend fun renameFutureTemplate(id: String, title: String)

    @Query("UPDATE tasks SET completed = :completed, completedTime = :time WHERE id = :id")
    suspend fun setCompleted(id: String, completed: Boolean, time: Long?)

    @Query("DELETE FROM tasks WHERE id = :id AND type = 'CUSTOM'")
    suspend fun deleteCustomTask(id: String)
}
