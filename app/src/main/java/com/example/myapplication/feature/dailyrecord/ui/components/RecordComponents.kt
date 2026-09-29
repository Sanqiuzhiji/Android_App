package com.example.myapplication.feature.dailyrecord.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.feature.dailyrecord.model.Task
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun RefreshDateWhileVisible(refresh: () -> Unit, onForeground: () -> Unit = refresh) {
    val owner = LocalLifecycleOwner.current
    val latestRefresh by rememberUpdatedState(refresh)
    val latestForeground by rememberUpdatedState(onForeground)
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            latestForeground()
            while (true) { delay(15_000); latestRefresh() }
        }
    }
}

@Composable
fun TaskInputDialog(
    title: String,
    description: String,
    initialValue: String = "",
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    val focus = remember { FocusRequester() }
    val valid = value.trim().isNotEmpty() && value.trim().length <= 100
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true,
                    label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    enabled = !saving, isError = value.trim().length > 100,
                    supportingText = { Text("${value.trim().length} / 100") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid && !saving) onSave(value) }),
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = valid && !saving) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@Composable
fun TaskRow(
    task: Task,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val textColor by animateColorAsState(
        if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        label = "completionColor",
    )
    Surface(modifier = modifier.testTag("task-${task.id}"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).toggleable(task.completed, enabled = enabled && !task.isPreview, role = Role.Checkbox, onValueChange = onToggle)
                    .padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = task.completed, onCheckedChange = null, enabled = enabled && !task.isPreview)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, color = textColor, style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                    if (task.isPreview) Text("固定清单预览 · 当天开始记录", style = MaterialTheme.typography.labelSmall, color = textColor)
                    if (task.completed && task.completedTime != null) {
                        val time = Instant.ofEpochMilli(task.completedTime).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        Text("完成于 $time", style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                }
            }
            if (onEdit != null || onDelete != null) {
                Box {
                    TextButton(onClick = { menuOpen = true }, enabled = enabled) { Text("操作", style = MaterialTheme.typography.labelMedium) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onEdit != null) DropdownMenuItem(text = { Text("修改") }, onClick = { menuOpen = false; onEdit() })
                        if (onDelete != null) DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySection(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
