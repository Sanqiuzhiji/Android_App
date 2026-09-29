package com.example.myapplication.feature.dailyrecord.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.dailyrecord.model.FixedTaskTemplate
import com.example.myapplication.feature.dailyrecord.ui.components.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FixedTemplatesScreen(model: FixedTemplatesViewModel, dates: DateProvider, onBack: () -> Unit, onStopped: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val stoppedState by model.stoppedState.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTitle by rememberSaveable { mutableStateOf("") }
    var stoppingId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val drag = rememberTaskDragReorder(listState, state.templates.map { it.id }, !saving && !state.loading, model::moveTo)
    RefreshDateWhileVisible(model::refreshToday)
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("固定清单") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!drag.isDragging) ExtendedFloatingActionButton(onClick = { editingId = null; editingTitle = ""; editing = true }) { Text("＋ 添加固定任务") }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).clipToBounds()) {
            LazyColumn(Modifier.fillMaxSize().then(drag.gestureModifier()), state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("每天重新开始", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("新增任务从今天开始。修改或停用从明天生效，今天和历史记录都会保留。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("长按卡片拖动排序，靠近上下边缘可自动滚动，松手保存。顺序用于今天未完成的任务及后续清单。", style = MaterialTheme.typography.bodySmall)
                }
                if (state.loading) item { CircularProgressIndicator() }
                if (state.error != null) item { Text(state.error!!); Button(onClick = model::retry) { Text("重试") } }
                if (!state.loading && state.error == null && state.templates.isEmpty()) item { EmptySection("添加晨跑、阅读等每天想坚持的小事。") }
                items(drag.ordered(state.templates) { it.id }, key = { it.id }) { template ->
                    FixedTemplateCard(template, dates, enabled = !saving && !drag.isBusy,
                        onEdit = { editingId = template.id; editingTitle = template.title; editing = true },
                        onStop = { stoppingId = template.id },
                        modifier = Modifier.fillMaxWidth()
                        .animateItem(placementSpec = if (drag.activeId == template.id) null else spring(stiffness = 500f))
                        .then(drag.itemModifier(template.id)))
                }
                item(key = "stopped-tasks") {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onStopped).padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("已停用任务", style = MaterialTheme.typography.titleSmall)
                            Text("单独查看或重新启用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!stoppedState.loading) Text("${stoppedState.templates.size} 项  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TaskDragOverlay(drag) { id ->
                state.templates.firstOrNull { it.id == id }?.let { template ->
                    FixedTemplateCard(template, dates, enabled = false)
                }
            }
        }
    }
    if (editing) TaskInputDialog(
        if (editingId == null) "添加固定任务" else "修改固定任务",
        if (editingId == null) "从今天起，每天生成一项独立任务。" else "新名称从明天起使用，历史名称保持不变。",
        initialValue = editingTitle, saving = saving, onDismiss = { editing = false },
        onSave = { model.save(editingId, it) { editing = false } },
    )
    stoppingId?.let { id ->
        AlertDialog(onDismissRequest = { if (!saving) stoppingId = null }, title = { Text("停用这项固定任务？") },
            text = { Text("从明天起不再生成，已有记录不会删除。") },
            confirmButton = { TextButton(enabled = !saving, onClick = { model.stop(id) { stoppingId = null } }) { Text("停用") } },
            dismissButton = { TextButton(enabled = !saving, onClick = { stoppingId = null }) { Text("取消") } })
    }
}

@Composable
private fun FixedTemplateCard(
    template: FixedTaskTemplate,
    dates: DateProvider,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onEdit: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(template.title, style = MaterialTheme.typography.titleMedium)
            Text(when {
                template.effectiveUntil != null -> "${template.effectiveUntil} 起此版本不再生成"
                template.effectiveFrom > dates.today() -> "${template.effectiveFrom} 起生效"
                else -> "每天 · ${template.effectiveFrom} 起"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (template.effectiveUntil == null) Row {
                TextButton(enabled = enabled, onClick = onEdit) { Text("修改") }
                TextButton(enabled = enabled, onClick = onStop) { Text("停用") }
            }
        }
    }
}
