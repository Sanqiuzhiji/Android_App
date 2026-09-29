package com.example.myapplication.feature.reminders

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.location.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.myapplication.feature.dailyrecord.data.DailyRecordRepository
import com.example.myapplication.feature.dailyrecord.model.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun reminderMoment(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(records: DailyRecordRepository, onBack: () -> Unit, onDay: (String) -> Unit) {
    val context = LocalContext.current
    val store = context.reminderStore
    val points by store.points.collectAsState()
    val places by store.places.collectAsState()
    var editing by remember { mutableStateOf<ReminderPoint?>(null) }
    var creating by remember { mutableStateOf(false) }
    var placeEditor by remember { mutableStateOf(false) }
    var editPlace by remember { mutableStateOf<SavedPlace?>(null) }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun sync() { runCatching { ReminderRuntime.sync(context, true) }.onFailure { message = "无法启动定位：${it.localizedMessage}" } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++; sync() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) { refresh++; sync() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("任务提醒") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("任务完成后自动停止提醒；也可随时暂停或恢复。点击通知进入对应日期的每日记录。")
            key(refresh) {
                Text("定位：${if(ReminderRuntime.locationAllowed(context)) "已授权" else "未授权精确位置"} · 准时提醒：${if(ReminderRuntime.exactAllowed(context)) "已授权" else "可能延迟"}")
                Text("通知：${if(context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) "已开启" else "未开启，无法提醒"}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { permissions.launch(buildList { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION); if(Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray()) }) { Text("开启定位 / 通知") }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("应用设置") }
            }
            if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("允许准时提醒") }
            Text("地址模式不限制日期时刻，日期用于关联任务。组合模式仅在有效区间内提醒。锁屏定位需允许定位并保持运行通知；重启后请打开应用恢复地点监测。", style = MaterialTheme.typography.bodySmall)
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = { editing = null; creating = true }) { Text("＋ 创建提醒点") }
            if (points.isEmpty()) Text("还没有提醒点，先添加常用地点或创建提醒。")
            points.sortedBy { it.date + (it.time ?: "") }.forEach { p ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        val status = when {
                            p.stopReason == "completed" -> "任务已完成"
                            p.stopReason == "missing" -> "关联任务不存在"
                            !p.enabled -> "已暂停"
                            p.mode == ReminderMode.BOTH && p.end() < System.currentTimeMillis() -> "区间已结束"
                            p.mode == ReminderMode.PLACE -> "等待下次进入"
                            p.lastNotifiedAt > 0 -> "重复提醒中"
                            else -> "等待提醒"
                        }
                        Text("${p.date}${if(p.mode == ReminderMode.PLACE) "" else " ${p.time ?: "旧版仅日期"}"} · ${p.mode.label} · $status")
                        Text("${places.find { it.id == p.placeId }?.name ?: "无地点"} · ${p.radius} 米 · 提前 ${p.early} 分钟")
                        Text("${if(p.sound) "响铃 " else ""}${if(p.vibrate) "震动" else ""}${if(p.mode == ReminderMode.BOTH && p.time != null) " · 截止设定时刻后 ${p.window} 分钟" else ""}")
                        if(p.mode == ReminderMode.BOTH && p.time != null) Text("提醒区间\n${reminderMoment(p.start())} 至\n${reminderMoment(p.end())}")
                        if(p.mode != ReminderMode.PLACE) Text("未完成时每 ${p.repeatMinutes} 分钟提醒${if(p.mode == ReminderMode.BOTH) "（仅有效区间内且在地点范围内）" else "，直到完成或暂停"}")
                        if(p.lastNotifiedAt > 0) Text("上次提醒：${reminderMoment(p.lastNotifiedAt)}", style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick = { onDay(p.date) }) { Text("查看任务") }; TextButton(onClick = { editing = p; creating = true }) { Text("编辑") }
                            TextButton(enabled = p.enabled || p.mode != ReminderMode.BOTH || p.end() >= System.currentTimeMillis(), onClick = { scope.launch {
                                ReminderRuntime.setEnabled(context, p.id, !p.enabled)
                                ReminderRuntime.refreshTasks(context)
                                sync()
                            } }) { Text(if(p.enabled) "暂停" else "恢复") }
                            TextButton(onClick = { store.delete(p.id); sync() }) { Text("删除") } }
                        if(p.mode == ReminderMode.BOTH && p.end() < System.currentTimeMillis()) Text("继续提醒请编辑日期和有效区间。", style = MaterialTheme.typography.bodySmall)
                        if(p.fired && p.enabled && p.stopReason.isEmpty() && (p.mode != ReminderMode.BOTH || p.end() >= System.currentTimeMillis())) TextButton(onClick = { scope.launch {
                            ReminderRuntime.setEnabled(context, p.id, true)
                            ReminderRuntime.refreshTasks(context)
                            sync()
                            message = if(p.mode == ReminderMode.PLACE) "已恢复：等待离开后下次进入" else "已恢复：未完成时将在下一个提醒间隔检查"
                        } }) { Text("恢复下次提醒") }
                    }
                }
            }
            HorizontalDivider()
            Text("常用地点", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { editPlace = null; placeEditor = true }) { Text("＋ 添加地点（学校 / 宿舍等）") }
            places.forEach { place ->
                Text("${place.name} · 默认 ${place.radius} 米")
                Row { TextButton(onClick = { editPlace = place; placeEditor = true }) { Text("编辑地点") }
                    TextButton(onClick = { runCatching { store.deletePlace(place.id) }.onFailure { message = it.message.orEmpty() } }) { Text("删除地点") } }
            }
        }
    }
    if (creating) ReminderEditor(records, places, editing, onDismiss = { creating = false }, onSave = { p ->
        store.save(p); sync(); scope.launch { ReminderRuntime.evaluate(context) }; creating = false
    })
    if (placeEditor) PlaceEditor(editPlace, onDismiss = { placeEditor = false }, onSave = { store.save(it); sync(); placeEditor = false })
}

@Composable
private fun ReminderEditor(records: DailyRecordRepository, places: List<SavedPlace>, original: ReminderPoint?, onDismiss: () -> Unit, onSave: (ReminderPoint) -> Unit) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.parse(original?.date ?: LocalDate.now().toString())) }
    var time by remember { mutableStateOf(original?.time ?: LocalTime.now().plusHours(1).withSecond(0).withNano(0).toString()) }
    var mode by remember { mutableStateOf(original?.mode ?: ReminderMode.TIME) }
    var placeId by remember { mutableStateOf(original?.placeId ?: places.firstOrNull()?.id) }
    var radius by remember { mutableStateOf((original?.radius ?: places.firstOrNull()?.radius ?: 200).toString()) }
    var early by remember { mutableStateOf((original?.early ?: 0).toString()) }
    var window by remember { mutableStateOf((original?.window ?: 60).toString()) }
    var repeat by remember { mutableStateOf((original?.repeatMinutes ?: 10).toString()) }
    var sound by remember { mutableStateOf(original?.sound ?: true) }
    var vibrate by remember { mutableStateOf(original?.vibrate ?: true) }
    var selectedId by remember { mutableStateOf(original?.taskId) }
    var selectedTemplate by remember { mutableStateOf(original?.templateId) }
    var error by remember { mutableStateOf("") }
    val dayFlow = remember(date) { records.observeDay(date) }
    val day by dayFlow.collectAsState(initial = DailyRecord(date, emptyList()))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(original == null) "创建提醒点" else "编辑提醒点") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("提醒模式", style = MaterialTheme.typography.titleMedium)
            ReminderMode.entries.forEach { value -> Row { RadioButton(mode == value, { mode = value }); TextButton(onClick = { mode = value }) { Text("${value.label}模式") } } }
            if(mode == ReminderMode.PLACE) Text("创建或恢复时已经在地点内不会马上提醒；离开后再次进入才提醒。任务未完成时，每次重新进入都提醒。", style = MaterialTheme.typography.bodySmall)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if(mode == ReminderMode.PLACE) "关联任务的日期" else "提醒日期与时间", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y,m+1,d); selectedId = null; selectedTemplate = null }, date.year,date.monthValue-1,date.dayOfMonth).show() }) { Text("日期：$date") }
                    if(mode != ReminderMode.PLACE) {
                        TextButton(onClick = { val t = LocalTime.parse(time); TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h,m).toString() },t.hour,t.minute,true).show() }) { Text("时间：$time") }
                    }
                }
            }
            if(mode != ReminderMode.PLACE) {
                OutlinedTextField(early, { early = it }, label = { Text("提前分钟（0 为准时，最多 1440）") })
                OutlinedTextField(repeat, { repeat = it }, label = { Text("重复提醒间隔（分钟）") },
                    supportingText = { Text(if(mode == ReminderMode.TIME) "默认 10 分钟，未完成且未暂停时持续提醒。" else "默认 10 分钟，仅在有效区间内且位于地点范围内重复提醒。") })
                if(mode == ReminderMode.BOTH) {
                    OutlinedTextField(window, { window = it }, label = { Text("设定时刻后有效（分钟）") },
                        supportingText = { Text("从设定时刻计算截止时间，提前提醒不会让截止时间提前。") })
                }
                val earlyMinutes = early.toIntOrNull()?.takeIf { it in 0..1440 }
                val afterMinutes = window.toIntOrNull()?.takeIf { it in 0..1440 }
                if(earlyMinutes != null) {
                    val scheduled = date.atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val start = scheduled - earlyMinutes * 60_000L
                    if(mode == ReminderMode.TIME) Text("实际提醒时间：${reminderMoment(start)}", style = MaterialTheme.typography.bodySmall)
                    else if(afterMinutes != null) {
                        Text("实际提醒区间\n开始：${reminderMoment(start)}\n截止：${reminderMoment(scheduled + afterMinutes * 60_000L)}", style = MaterialTheme.typography.bodyMedium)
                        Text("开始＝设定时间－提前分钟；截止＝设定时间＋时刻后有效分钟。在这个区间内到达地点，或区间开始时已在地点内，开始按间隔提醒。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(mode != ReminderMode.TIME) {
                Text("选择常用地点")
                if(places.isEmpty()) Text("请先返回添加常用地点", color = MaterialTheme.colorScheme.error)
                places.forEach { p -> Row { RadioButton(placeId == p.id, { placeId = p.id; radius = p.radius.toString() }); TextButton(onClick = { placeId = p.id; radius = p.radius.toString() }) { Text(p.name) } } }
                OutlinedTextField(radius, { radius = it }, label = { Text("提醒半径（50–10000 米）") })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("关联任务", style = MaterialTheme.typography.titleMedium)
            OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$date 的未完成任务", style = MaterialTheme.typography.titleSmall)
            if(day.pending.isEmpty()) Text("当天没有未完成任务，请先在每日记录中添加。")
            TaskType.entries.forEach { type ->
                Text(if(type == TaskType.FIXED) "固定任务" else "临时任务")
                day.pending.filter { it.type == type }.forEach { task ->
                    val selected = if(selectedTemplate != null) selectedTemplate == task.templateId else selectedId == task.id
                    Row { RadioButton(selected, { selectedId = task.id; selectedTemplate = task.templateId }); TextButton(onClick = { selectedId = task.id; selectedTemplate = task.templateId }) { Text(task.title) } }
                }
            }
            }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("提醒方式", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("收到提醒时如何通知你（可同时开启）", style = MaterialTheme.typography.bodySmall)
                    CheckLine("响铃",sound) { sound = it }
                    CheckLine("震动",vibrate) { vibrate = it }
                }
            }
            if(original != null) Text("保存修改会重新启用此提醒点。地址模式会等待下次进入。")
            if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching {
            require(day.date == date) { "正在加载当天任务，请稍后保存" }
            val task = day.pending.find { if(selectedTemplate != null) it.templateId == selectedTemplate else it.id == selectedId }
            require(task != null) { "请选择当天的未完成任务" }
            require(sound || vibrate) { "至少选择一种提醒方式" }
            val r = if(mode == ReminderMode.TIME) 200 else radius.toIntOrNull()
            require(r != null && r in 50..10000) { "半径应为 50–10000 米" }
            require(mode == ReminderMode.TIME || places.any { it.id == placeId }) { "请选择地点" }
            val e = if(mode == ReminderMode.PLACE) 0 else early.toIntOrNull()
            val w = if(mode != ReminderMode.BOTH) 60 else window.toIntOrNull()
            require(e != null && e in 0..1440 && w != null && w in 0..1440) { "分钟数应为 0–1440" }
            require(mode != ReminderMode.BOTH || e + w > 0) { "组合模式有效区间至少需要 1 分钟" }
            val repeatMinutes = if(mode == ReminderMode.PLACE) 10 else repeat.toIntOrNull()
            require(repeatMinutes != null && repeatMinutes in 1..1440) { "重复间隔应为 1–1440 分钟" }
            val p = ReminderPoint(id = original?.id ?: java.util.UUID.randomUUID().toString(),title = task.title,date = date.toString(),time = if(mode == ReminderMode.PLACE) null else time,early = e,window = w,mode = mode,placeId = if(mode == ReminderMode.TIME) null else placeId,radius = r,taskId = task.id,templateId = task.templateId,sound = sound,vibrate = vibrate, repeatMinutes = repeatMinutes)
            require(mode != ReminderMode.BOTH || p.end() > System.currentTimeMillis()) { "提醒区间已过，请选择未来时间" }
            onSave(p)
        }.onFailure { error = it.message ?: "保存失败" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun CheckLine(label: String, checked: Boolean, change: (Boolean) -> Unit) { Row { Checkbox(checked,change); TextButton(onClick = { change(!checked) }) { Text(label) } } }

@Composable
private fun PlaceEditor(original: SavedPlace?, onDismiss: () -> Unit, onSave: (SavedPlace) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(original?.name ?: "") }
    var lat by remember { mutableStateOf(original?.lat?.toString() ?: "") }
    var lon by remember { mutableStateOf(original?.lon?.toString() ?: "") }
    var radius by remember { mutableStateOf((original?.radius ?: 200).toString()) }
    var message by remember { mutableStateOf("") }
    var map by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val manager = remember { context.getSystemService(LocationManager::class.java) }
    val listener = remember { object : LocationListener {
        override fun onLocationChanged(location: Location) { lat = location.latitude.toString(); lon = location.longitude.toString(); message = "定位精度约 ${location.accuracy.toInt()} 米，请核对地点"; locating = false }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    } }
    LaunchedEffect(locating) { if(locating) { delay(30_000); locating = false; message = "定位超时，请到室外重试或使用地图选点" } else manager.removeUpdates(listener) }
    DisposableEffect(Unit) { onDispose { manager.removeUpdates(listener) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { message = "授权后请再次点击获取当前位置" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("常用地点") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("名称：学校、宿舍、图书馆…")})
            OutlinedTextField(radius,{radius=it},label={Text("默认半径（50–10000 米）")})
            TextButton(enabled = !locating, onClick = {
                if(!ReminderRuntime.locationAllowed(context)) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                else runCatching {
                    val providers = listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
                    require(providers.isNotEmpty()) { "请先开启手机定位服务" }
                    locating = true; message = "正在获取当前位置…"
                    try { providers.forEach { manager.requestLocationUpdates(it,1000L,0f,listener,Looper.getMainLooper()) } }
                    catch (_: SecurityException) { locating = false; message = "定位权限已撤销，请重新授权" }
                }.onFailure { locating = false; message = it.message.orEmpty() }
            }) { Text(if(locating) "定位中…" else "获取当前位置") }
            TextButton(onClick = { map = true }) { Text("打开地图选点") }
            OutlinedTextField(lat,{lat=it},label={Text("纬度（WGS84）")})
            OutlinedTextField(lon,{lon=it},label={Text("经度（WGS84）")})
            Text("在地图中搜索大概位置，再点选具体地点。也可以直接获取当前位置。",style = MaterialTheme.typography.bodySmall)
            if(message.isNotEmpty()) Text(message)
        }
    },confirmButton = { TextButton(onClick = { runCatching {
        val a=lat.toDoubleOrNull(); val b=lon.toDoubleOrNull(); val r=radius.toIntOrNull()
        require(name.isNotBlank() && name.length <= 60) { "请输入 1–60 字地点名称" }
        require(a != null && a.isFinite() && a in -90.0..90.0 && b != null && b.isFinite() && b in -180.0..180.0) { "请获取有效位置" }
        require(r != null && r in 50..10000) { "半径应为 50–10000 米" }
        onSave(SavedPlace(original?.id ?: java.util.UUID.randomUUID().toString(),name.trim(),a,b,r))
    }.onFailure { message=it.message.orEmpty() } }) { Text("保存地点") } },dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
    if(map) MapPicker(lat.toDoubleOrNull() ?: 35.0,lon.toDoubleOrNull() ?: 105.0,onDismiss={map=false}) { a,b -> lat=a.toString();lon=b.toString();map=false }
}
