package com.example.myapplication.feature.dailyrecord.data

import com.example.myapplication.feature.dailyrecord.model.DailyRecord
import com.example.myapplication.feature.dailyrecord.model.FixedTaskTemplate
import com.example.myapplication.feature.dailyrecord.model.CalendarDaySummary
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface DailyRecordRepository {
    suspend fun moveTemplate(id: String, direction: Int)
    suspend fun moveCustomTask(id: String, direction: Int)
    suspend fun moveTemplateTo(id: String, targetId: String)
    suspend fun moveCustomTaskTo(id: String, targetId: String)
    fun observeDay(date: LocalDate): Flow<DailyRecord>
    fun observeTemplates(today: LocalDate): Flow<List<FixedTaskTemplate>>
    fun observeStoppedTemplates(): Flow<List<FixedTaskTemplate>>
    fun observeCalendar(from: LocalDate, until: LocalDate, today: LocalDate): Flow<List<CalendarDaySummary>>
    suspend fun ensureDay(date: LocalDate)
    suspend fun addCustomTask(date: LocalDate, title: String)
    suspend fun renameCustomTask(id: String, title: String)
    suspend fun addTemplate(title: String)
    suspend fun renameTemplate(id: String, title: String)
    suspend fun stopTemplate(id: String)
    suspend fun reactivateTemplate(id: String)
    suspend fun setCompleted(id: String, completed: Boolean)
    suspend fun deleteCustomTask(id: String)
}
