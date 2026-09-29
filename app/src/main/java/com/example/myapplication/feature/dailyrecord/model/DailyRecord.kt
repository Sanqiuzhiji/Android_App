package com.example.myapplication.feature.dailyrecord.model

import java.time.LocalDate

enum class TaskType { FIXED, CUSTOM }

data class Task(
    val id: String,
    val title: String,
    val date: LocalDate,
    val type: TaskType,
    val completed: Boolean,
    val completedTime: Long?,
    val templateId: String? = null,
    val isPreview: Boolean = false,
)

data class CompletionCount(val completed: Int, val total: Int)

data class DailyRecord(val date: LocalDate, val tasks: List<Task>) {
    val fixed get() = tasks.filter { it.type == TaskType.FIXED }
    val custom get() = tasks.filter { it.type == TaskType.CUSTOM }
    val totalCount get() = tasks.countCompletion()
    val fixedCount get() = fixed.countCompletion()
    val customCount get() = custom.countCompletion()
    val pending get() = tasks.filterNot { it.completed }.sortedBy { it.type.ordinal }
    val finished get() = tasks.filter { it.completed }
        .sortedWith(compareBy<Task> { it.completedTime ?: Long.MAX_VALUE }.thenBy { it.id })
}

private fun List<Task>.countCompletion() = CompletionCount(count { it.completed }, size)

/** A template version; its interval is [effectiveFrom, effectiveUntil). */
data class FixedTaskTemplate(
    val id: String,
    val title: String,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
)
