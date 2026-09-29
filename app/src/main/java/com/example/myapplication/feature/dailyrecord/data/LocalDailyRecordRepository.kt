package com.example.myapplication.feature.dailyrecord.data

import androidx.room.withTransaction
import com.example.myapplication.core.database.ToolboxDatabase
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.dailyrecord.data.local.*
import com.example.myapplication.feature.dailyrecord.model.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID

class LocalDailyRecordRepository(
    private val database: ToolboxDatabase,
    private val dates: DateProvider,
) : DailyRecordRepository {
    private val dao = database.dailyRecordDao()

    override suspend fun moveTemplate(id: String, direction: Int) {
        require(direction == -1 || direction == 1)
        reorderTemplate(id, direction, null)
    }

    override suspend fun moveTemplateTo(id: String, targetId: String) = reorderTemplate(id, 0, targetId)

    private suspend fun reorderTemplate(id: String, direction: Int, targetId: String?) {
        database.withTransaction {
            val today = dates.today().toString()
            val templates = dao.observeTemplates().first().toMutableList()
            val index = templates.indexOfFirst { it.id == id }
            val target = if (targetId == null) index + direction else templates.indexOfFirst { it.id == targetId }
            if (index < 0 || target !in templates.indices) return@withTransaction
            templates.add(target, templates.removeAt(index))
            templates.forEachIndexed { position, template ->
                dao.setTemplateOrder(template.id, position.toLong())
                dao.setPendingFixedOrder(template.id, today, position.toLong())
            }
        }
    }

    override suspend fun moveCustomTask(id: String, direction: Int) {
        require(direction == -1 || direction == 1)
        reorderCustomTask(id, direction, null)
    }

    override suspend fun moveCustomTaskTo(id: String, targetId: String) = reorderCustomTask(id, 0, targetId)

    private suspend fun reorderCustomTask(id: String, direction: Int, targetId: String?) {
        database.withTransaction {
            val task = dao.task(id) ?: return@withTransaction
            if (task.type != TaskType.CUSTOM.name || task.completed) return@withTransaction
            val tasks = dao.customTasksFor(task.date).toMutableList()
            val pendingIndices = tasks.indices.filter { !tasks[it].completed }
            val index = pendingIndices.indexOfFirst { tasks[it].id == id }
            val target = if (targetId == null) index + direction else pendingIndices.indexOfFirst { tasks[it].id == targetId }
            if (index < 0 || target !in pendingIndices.indices) return@withTransaction
            val pending = pendingIndices.map { tasks[it] }.toMutableList()
            pending.add(target, pending.removeAt(index))
            pendingIndices.forEachIndexed { position, taskIndex -> tasks[taskIndex] = pending[position] }
            tasks.forEachIndexed { position, row -> dao.setCustomOrder(row.id, position.toLong()) }
        }
    }

    override fun observeDay(date: LocalDate) = combine(
        dao.observeTasks(date.toString()), dao.observeAllTemplates(),
    ) { rows, templates -> projectDailyRecord(date, rows.map { it.toModel() }, templates.map { it.toModel() }) }

    override fun observeCalendar(from: LocalDate, until: LocalDate, today: LocalDate) = combine(
        dao.observeTasksInRange(maxOf(from, LocalDate.of(1900, 1, 1)).toString(), minOf(until, LocalDate.of(9999, 12, 31)).toString()),
        dao.observeAllTemplates(),
    ) { rows, templates ->
        val byDate = rows.groupBy { LocalDate.parse(it.date) }
        val models = templates.map { it.toModel() }
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= until }.map { date ->
            projectDailyRecord(date, byDate[date].orEmpty().map { it.toModel() }, models).calendarSummary(today)
        }.toList()
    }

    override fun observeTemplates(today: LocalDate) = dao.observeTemplates().map { rows ->
        rows.map { it.toModel() }
    }

    override fun observeStoppedTemplates() = dao.observeStoppedTemplates().map { rows ->
        rows.map { it.toModel() }
    }

    override suspend fun ensureDay(date: LocalDate) {
        database.withTransaction { generateDay(date) }
    }

    private suspend fun generateDay(date: LocalDate) {
        val key = date.toString()
        dao.insertRecord(DailyRecordEntity(key, dates.nowMillis()))
        // Future fixed tasks are previews. Their instances are created only once the day arrives.
        if (date > dates.today()) return
        dao.insertTasks(dao.templatesFor(key).map { template ->
            TaskEntity(
                id = UUID.randomUUID().toString(), date = key, title = template.title,
                type = TaskType.FIXED.name, templateId = template.id, sortOrder = template.sortOrder,
            )
        })
    }

    override suspend fun addCustomTask(date: LocalDate, title: String) {
        val clean = cleanTitle(title)
        database.withTransaction {
            generateDay(date)
            dao.insertTasks(listOf(TaskEntity(
                id = UUID.randomUUID().toString(), date = date.toString(), title = clean,
                type = TaskType.CUSTOM.name, sortOrder = dates.nowMillis(),
            )))
        }
    }

    override suspend fun renameCustomTask(id: String, title: String) = dao.renameCustomTask(id, cleanTitle(title))

    override suspend fun addTemplate(title: String) {
        val clean = cleanTitle(title)
        database.withTransaction {
            val today = dates.today()
            dao.insertTemplate(FixedTemplateEntity(UUID.randomUUID().toString(), clean, today.toString(), sortOrder = dates.nowMillis()))
            generateDay(today)
        }
    }

    override suspend fun renameTemplate(id: String, title: String) {
        val clean = cleanTitle(title)
        database.withTransaction {
            val template = requireNotNull(dao.template(id)) { "固定任务不存在" }
            require(template.effectiveUntil == null) { "该版本已停用或已安排修改，请编辑新的版本" }
            val today = dates.today()
            if (LocalDate.parse(template.effectiveFrom).isAfter(today)) {
                // A scheduled version has no daily instances yet and can be edited directly.
                dao.renameFutureTemplate(id, clean)
            } else if (template.title != clean) {
                val tomorrow = today.plusDays(1).toString()
                dao.endTemplate(id, tomorrow)
                dao.insertTemplate(FixedTemplateEntity(UUID.randomUUID().toString(), clean, tomorrow, sortOrder = template.sortOrder))
            }
        }
    }

    override suspend fun stopTemplate(id: String) {
        database.withTransaction {
            val template = requireNotNull(dao.template(id)) { "固定任务不存在" }
            if (template.effectiveUntil == null) {
                // Empty interval cancels a scheduled version without touching older versions.
                val until = maxOf(dates.today().plusDays(1), LocalDate.parse(template.effectiveFrom))
                dao.endTemplate(id, until.toString())
            }
        }
    }

    override suspend fun reactivateTemplate(id: String) {
        database.withTransaction {
            val template = requireNotNull(dao.template(id)) { "固定任务不存在" }
            val until = requireNotNull(template.effectiveUntil) { "该固定任务仍在使用" }
            val today = dates.today()
            if (LocalDate.parse(until).isAfter(today)) {
                // The stop has not taken effect yet, so retaining this version avoids a duplicate.
                dao.cancelTemplateEnd(id)
            } else {
                dao.insertTemplate(FixedTemplateEntity(
                    UUID.randomUUID().toString(), template.title, today.toString(),
                    sortOrder = dates.nowMillis(),
                ))
                generateDay(today)
            }
        }
    }

    override suspend fun setCompleted(id: String, completed: Boolean) {
        require(!id.startsWith("preview:")) { "固定清单预览将在当天开始记录完成情况" }
        database.withTransaction {
            dao.setCompleted(id, completed, if (completed) dates.nowMillis() else null)
            if (!completed) {
                val task = dao.task(id)
                val template = task?.templateId?.let { dao.template(it) }
                if (template != null) dao.setPendingFixedOrder(template.id, dates.today().toString(), template.sortOrder)
            }
        }
    }

    override suspend fun deleteCustomTask(id: String) = dao.deleteCustomTask(id)

    private fun cleanTitle(title: String): String {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "请输入任务名称" }
        require(clean.length <= 100) { "任务名称最多 100 个字符" }
        return clean
    }

    private fun TaskEntity.toModel() = Task(
        id, title, LocalDate.parse(date), TaskType.valueOf(type), completed, completedTime, templateId,
    )

    private fun FixedTemplateEntity.toModel() = FixedTaskTemplate(
        id, title, LocalDate.parse(effectiveFrom), effectiveUntil?.let(LocalDate::parse),
    )
}
