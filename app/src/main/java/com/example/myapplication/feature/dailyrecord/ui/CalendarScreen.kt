package com.example.myapplication.feature.dailyrecord.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.core.preferences.CalendarPreferences
import com.example.myapplication.feature.dailyrecord.model.CalendarDayStatus
import com.example.myapplication.feature.dailyrecord.model.monthGrid
import com.example.myapplication.feature.dailyrecord.ui.components.RefreshDateWhileVisible
import java.time.LocalDate
import java.time.YearMonth

private val CalendarDayStatus.label: String
    get() = when (this) {
        CalendarDayStatus.ORDINARY -> "普通"
        CalendarDayStatus.PLANNED -> "已安排"
        CalendarDayStatus.COMPLETED -> "已完成"
        CalendarDayStatus.PENDING -> "待处理"
    }

@Composable
private fun statusColors(status: CalendarDayStatus): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (status) {
        CalendarDayStatus.ORDINARY -> if (dark) Color(0xFF30343A) to Color(0xFFE0E3E8) else Color(0xFFECEEF1) to Color(0xFF464B53)
        CalendarDayStatus.PLANNED -> if (dark) Color(0xFF183451) to Color(0xFFA9D0FF) else Color(0xFFE1EEFF) to Color(0xFF174C8D)
        CalendarDayStatus.COMPLETED -> if (dark) Color(0xFF193D2C) to Color(0xFFA1DFB6) else Color(0xFFDEF3E4) to Color(0xFF225D37)
        CalendarDayStatus.PENDING -> if (dark) Color(0xFF4A3019) to Color(0xFFFFCB94) else Color(0xFFFFEBD6) to Color(0xFF874713)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    model: CalendarViewModel,
    preferences: CalendarPreferences,
    onBack: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val summaries = remember(state.days) { state.days.associateBy { it.date } }
    var choosingMonth by rememberSaveable { mutableStateOf(false) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }
    var showStatusText by rememberSaveable { mutableStateOf(preferences.showStatusText) }
    RefreshDateWhileVisible(model::refreshToday)
    Scaffold(topBar = {
        TopAppBar(title = { Text("记录日历") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            actions = {
                TextButton(onClick = { showingSettings = true }) { Text("设置") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { model.moveMonth(-1) }, enabled = state.month > YearMonth.of(1900, 1)) { Text("上月") }
                TextButton(onClick = { choosingMonth = true }, modifier = Modifier.weight(1f)) {
                    Text("${state.month.year}年${state.month.monthValue}月", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = { model.moveMonth(1) }, enabled = state.month < YearMonth.of(9999, 12)) { Text("下月") }
            }
            TextButton(onClick = model::showToday, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("回到本月") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
            }
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.error != null -> {
                    Text(state.error!!)
                    Button(onClick = model::retry) { Text("重试") }
                }
                else -> monthGrid(state.month).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        week.forEach dayCell@ { date ->
                            val day = date?.let { summaries[it] }
                            if (day == null) {
                                Spacer(Modifier.weight(1f).heightIn(min = 58.dp))
                                return@dayCell
                            }
                            val (background, foreground) = statusColors(day.status)
                            val isToday = day.date == state.today
                            val selected = day.date == model.selectedDate
                            Surface(
                                onClick = { onSelect(day.date) },
                                enabled = day.date.year in 1900..9999,
                                modifier = Modifier.weight(1f).heightIn(min = 58.dp)
                                    .testTag("calendar-day-${day.date}")
                                    .semantics { contentDescription = "${day.date}，${day.status.label}，完成 ${day.completed}/${day.total}${if (isToday) "，今天" else ""}" },
                                shape = MaterialTheme.shapes.small,
                                color = background, contentColor = foreground,
                                border = when {
                                    selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                    isToday -> BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)
                                    else -> null
                                },
                            ) {
                                Column(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall)
                                    if (showStatusText) Text(when {
                                        isToday -> "今天"
                                        else -> day.status.label
                                    }, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            Text("点击日期进入记录；点击年月可快速跳转。", style = MaterialTheme.typography.bodySmall)
            CalendarLegend()
        }
    }
    if (choosingMonth) MonthInputDialog(state.month, onDismiss = { choosingMonth = false }) {
        model.selectMonth(it); choosingMonth = false
    }
    if (showingSettings) AlertDialog(
        onDismissRequest = { showingSettings = false },
        title = { Text("日历显示设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().toggleable(value = showStatusText, role = Role.Switch, onValueChange = {
                    showStatusText = it
                    preferences.showStatusText = it
                }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("显示日期状态文字", Modifier.weight(1f))
                    Switch(checked = showStatusText, onCheckedChange = null)
                }
                Text("开启后，数字下显示普通、已安排等文字。关闭后只显示数字和颜色；今天仍有边框标记。设置自动保存。")
            }
        },
        confirmButton = { TextButton(onClick = { showingSettings = false }) { Text("完成") } },
    )
}

@Composable
private fun CalendarLegend() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("状态说明", style = MaterialTheme.typography.titleSmall)
            CalendarDayStatus.entries.forEach { status ->
                val (background, foreground) = statusColors(status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = MaterialTheme.shapes.small, color = background, contentColor = foreground) {
                        Text(status.label, Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(when (status) {
                        CalendarDayStatus.ORDINARY -> "无任务，或未来只有固定清单"
                        CalendarDayStatus.PLANNED -> "未来已有临时安排"
                        CalendarDayStatus.COMPLETED -> "今天或过去的任务全部完成"
                        CalendarDayStatus.PENDING -> "今天或过去仍有未完成任务"
                    }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("已有任务优先显示完成状态；未来安排不会提前标为待处理。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MonthInputDialog(month: YearMonth, onDismiss: () -> Unit, onSelect: (YearMonth) -> Unit) {
    var year by rememberSaveable { mutableStateOf(month.year.toString()) }
    var number by rememberSaveable { mutableStateOf(month.monthValue.toString()) }
    val yearValue = year.toIntOrNull()
    val monthValue = number.toIntOrNull()
    val valid = yearValue != null && yearValue in 1900..9999 && monthValue != null && monthValue in 1..12
    AlertDialog(onDismissRequest = onDismiss, title = { Text("跳转到月份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = year, onValueChange = { year = it }, label = { Text("年份（1900–9999）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("月份（1–12）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSelect(YearMonth.of(yearValue!!, monthValue!!)) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
