package com.example.myapplication.feature.dailyrecord.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "daily_records")
data class DailyRecordEntity(@PrimaryKey val date: String, val createdAt: Long)

@Entity(tableName = "fixed_templates")
data class FixedTemplateEntity(
    @PrimaryKey val id: String,
    val title: String,
    val effectiveFrom: String,
    val effectiveUntil: String? = null,
    val sortOrder: Long,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [ForeignKey(
        entity = DailyRecordEntity::class,
        parentColumns = ["date"], childColumns = ["date"],
        onDelete = ForeignKey.RESTRICT,
    ), ForeignKey(
        entity = FixedTemplateEntity::class,
        parentColumns = ["id"], childColumns = ["templateId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("date"), Index("templateId"), Index(value = ["date", "templateId"], unique = true)],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val date: String,
    val title: String,
    val type: String,
    val templateId: String? = null,
    val completed: Boolean = false,
    val completedTime: Long? = null,
    val sortOrder: Long,
)
