package com.example.myapplication.feature.toolbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class ToolboxEntry(val id: String, val title: String, val description: List<String>)

private val tools = listOf(
    ToolboxEntry("daily-record", "每日记录", listOf("记录每日任务", "管理固定习惯", "查看历史完成")),
    ToolboxEntry("reminders", "任务提醒", listOf("创建任务提醒", "管理常用地点", "设置时间与到达提醒")),
)

@Composable
fun ToolboxScreen(onDailyRecord: () -> Unit, onBackup: () -> Unit, onReminders: () -> Unit = {}, onReminderBackup: () -> Unit = {}) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("个人工具箱", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            tools.forEach { tool ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "进入${tool.title}") {
                        if (tool.id == "daily-record") onDailyRecord() else onReminders()
                    }.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tool.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        tool.description.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    run {
                        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = if(tool.id == "daily-record") onBackup else onReminderBackup)) {
                            HorizontalDivider()
                            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 16.dp)) {
                                Text("备份与恢复", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
