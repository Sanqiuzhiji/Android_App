package com.example.myapplication.feature.dailyrecord.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.feature.dailyrecord.model.DailyRecord
import com.example.myapplication.feature.dailyrecord.model.TaskType
import com.example.myapplication.feature.dailyrecord.ui.components.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DailyRecordScreen(
    model: DailyRecordViewModel,
    onBack: () -> Unit,
    onTemplates: () -> Unit,
    onCalendar: (LocalDate) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val today by model.today.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var addingDate by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTitle by rememberSaveable { mutableStateOf("") }
    var dragging by remember { mutableStateOf(false) }
    RefreshDateWhileVisible(refresh = { model.refreshToday() }, onForeground = { model.refreshToday(true) })
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("每日记录") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onTemplates) { Text("固定清单") } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!dragging) ExtendedFloatingActionButton(onClick = { if (!state.loading && state.error == null) addingDate = state.date.toString() }) {
                Text("＋ 添加任务")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DateHeader(state.date, today, onChoose = { onCalendar(state.date) },
                onPrevious = { model.moveDay(-1) }, onNext = { model.moveDay(1) }, onToday = model::goToToday)
            // Each date gets its own list scroll position. Completion changes keep the same keys for animation.
            key(state.date) {
                val listState = rememberLazyListState()
                val drag = rememberTaskDragReorder(listState,
                    state.record.pending.filter { it.type == TaskType.CUSTOM }.map { it.id },
                    !saving && !state.loading, model::moveTaskTo)
                SideEffect { dragging = drag.isDragging }
                DisposableEffect(drag) { onDispose { dragging = false } }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("daily-task-list").then(drag.gestureModifier()),
                        state = listState,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            state.loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                            state.error != null -> item {
                                Text(state.error!!)
                                Button(onClick = model::retry) { Text("重试") }
                            }
                            else -> {
                                item(key = "summary") { CompletionSummary(state.record, state.date > today) }
                                if (state.date > today) item(key = "preview-note") {
                                    Text("提前规划 · 临时任务可立即记录。固定清单会随模板更新，当天生成独立完成状态。",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                for (completed in listOf(false, true)) {
                                    val tasks = if (completed) state.record.finished else state.record.pending
                                    val section = if (completed) "completed" else "pending"
                                    item(key = "$section-heading") {
                                        Row(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("section-$section"), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(if (completed) "已完成" else "未完成", style = MaterialTheme.typography.titleLarge)
                                            Text("${tasks.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (tasks.isEmpty()) item(key = "$section-empty") {
                                        EmptySection(if (completed) "完成的任务会保留在这里。" else if (state.record.tasks.isEmpty()) "点击 ＋ 添加安排，或通过右上角管理固定清单。" else "这一天的任务都完成了。")
                                    }
                                    for (type in if (completed) listOf<TaskType?>(null) else TaskType.entries) {
                                        val grouped = if (type == null) tasks else tasks.filter { it.type == type }
                                        val category = if (!completed && type == TaskType.CUSTOM) drag.ordered(grouped) { it.id } else grouped
                                        if (type != null && category.isNotEmpty()) item(key = "$section-${type.name}") {
                                            Text(if (type == TaskType.FIXED) "固定任务" else "临时任务",
                                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                            if (type == TaskType.CUSTOM) Text("长按拖动排序，靠近上下边缘自动滚动", style = MaterialTheme.typography.bodySmall)
                                        }
                                        items(category, key = { it.id }) { task ->
                                            TaskRow(task, !saving && !drag.isBusy, onToggle = { model.setCompleted(task.id, it) },
                                                modifier = Modifier.animateItem(placementSpec = if (drag.activeId == task.id) null else spring(stiffness = 500f))
                                                    .then(drag.itemModifier(task.id)),
                                                onEdit = if (task.type == TaskType.CUSTOM) ({ editingId = task.id; editingTitle = task.title }) else null,
                                                onDelete = if (task.type == TaskType.CUSTOM) ({ deletingId = task.id }) else null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TaskDragOverlay(drag) { id ->
                        state.record.custom.firstOrNull { it.id == id }?.let { task ->
                            TaskRow(task, enabled = false, onToggle = {}, onEdit = {}, onDelete = {})
                        }
                    }
                }
            }
        }
    }
    addingDate?.let { date ->
        TaskInputDialog("添加临时任务", "仅记录到 $date，不会在第二天重复。", saving = saving,
            onDismiss = { addingDate = null }, onSave = { model.addTask(LocalDate.parse(date), it) { addingDate = null } })
    }
    editingId?.let { id ->
        TaskInputDialog("修改临时任务", "只修改这项任务的名称，日期和完成状态保持不变。", initialValue = editingTitle, saving = saving,
            onDismiss = { editingId = null }, onSave = { model.renameTask(id, it) { editingId = null } })
    }
    deletingId?.let { id ->
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("删除这项临时任务？") },
            text = { Text("删除后，该任务将从这一天的记录中移除。") },
            confirmButton = { TextButton(enabled = !saving, onClick = { model.deleteTask(id); deletingId = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("取消") } })
    }
}

@Composable
private fun DateHeader(date: LocalDate, today: LocalDate, onChoose: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().pointerInput(threshold) {
        var distance = 0f
        detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = {
            if (distance < -threshold) previous() else if (distance > threshold) next()
        }, onHorizontalDrag = { change, amount -> change.consume(); distance += amount })
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onPrevious, enabled = date > LocalDate.of(1900, 1, 1)) { Text("前一天") }
            TextButton(onClick = onChoose, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.toString(), style = MaterialTheme.typography.titleLarge)
                    Text(date.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE)), style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(onClick = onNext, enabled = date < LocalDate.of(9999, 12, 31)) { Text("后一天") }
        }
        TextButton(onClick = onToday, enabled = date != today) { Text(if (date == today) "今天" else "回到今天") }
    }
}

@Composable
private fun CompletionSummary(record: DailyRecord, future: Boolean) {
    val total = record.totalCount
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (future) "计划完成情况（含固定清单预览）" else "当日完成", style = MaterialTheme.typography.labelLarge)
            Text("${total.completed} / ${total.total}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { if (total.total == 0) 0f else total.completed.toFloat() / total.total }, modifier = Modifier.fillMaxWidth())
            Text("固定 ${record.fixedCount.completed} / ${record.fixedCount.total}     ·     临时 ${record.customCount.completed} / ${record.customCount.total}",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
