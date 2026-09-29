package com.example.myapplication.feature.dailyrecord.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.feature.dailyrecord.ui.components.EmptySection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoppedTemplatesScreen(model: FixedTemplatesViewModel, onBack: () -> Unit) {
    val state by model.stoppedState.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("已停用任务") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("这里保留固定任务的旧版本和停用记录。重新启用不会修改以前的每日记录。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { error -> item { Text(error); Button(onClick = model::retry) { Text("重试") } } }
            if (!state.loading && state.error == null && state.templates.isEmpty()) item { EmptySection("还没有已停用的固定任务。") }
            items(state.templates, key = { it.id }) { template ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(template.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "使用于 ${template.effectiveFrom} 至 ${template.effectiveUntil}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(enabled = !saving, onClick = { model.reactivate(template.id) }) { Text("重新启用") }
                    }
                }
            }
        }
    }
}
