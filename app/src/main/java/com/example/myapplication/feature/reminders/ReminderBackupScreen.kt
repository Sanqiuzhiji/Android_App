package com.example.myapplication.feature.reminders

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.app.ToolboxApplication
import com.example.myapplication.feature.backup.data.BackupFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReminderBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ToolboxApplication
    private val files = BackupFiles(app, "reminder_backup_locations")
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var preview by mutableStateOf<ReminderBackup?>(null); private set
    var previewName by mutableStateOf(""); private set
    var lastExport by mutableStateOf(files.lastExport()); private set
    fun suggestedName() = "任务提醒备份_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.json"
    private fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "处理失败，请重试" }
            finally { busy = false }
        }
    }
    fun export(uri: Uri, flags: Int) = work {
        lastExport = withContext(Dispatchers.IO) { files.keepPermission(uri, flags); files.write(uri, app.container.reminders.exportBackup()) }
        message = "任务提醒与常用地点已导出"
    }
    fun inspect(uri: Uri) = work {
        preview = null
        val result = withContext(Dispatchers.IO) { files.displayName(uri) to ReminderBackupCodec.decode(files.read(uri)) }
        previewName = result.first; preview = result.second
    }
    fun cancel() { if (!busy) preview = null }
    fun restore() = work {
        val backup = preview ?: return@work
        var missing = 0
        withContext(Dispatchers.IO) {
            val days = backup.points.map { it.date }.distinct().associateWith { app.container.records.observeDay(LocalDate.parse(it)).first() }
            val checked = backup.points.map { p ->
                val task = days.getValue(p.date).tasks.find { if(p.templateId != null) it.templateId == p.templateId else it.id == p.taskId }
                if (task == null) missing++
                if(task == null || task.completed) p.copy(enabled = false, stopReason = if(task == null) "missing" else "completed", entryArmed = false) else p.copy(entryArmed = false)
            }
            ReminderRuntime.restore(app, backup.copy(points = checked))
        }
        preview = null
        message = "恢复完成：${backup.points.size} 个提醒点，${backup.places.size} 个常用地点。" +
            if(missing > 0) "其中 $missing 个提醒找不到原任务，已暂停，请编辑重新关联。" else "已保留启用和已提醒状态。"
        // Location monitoring resumes from the visible reminder page after restoration.
    }
}

private fun reminderDocument(action: String, initial: String? = null) = Intent(action).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = if(action == Intent.ACTION_CREATE_DOCUMENT) "application/json" else "*/*"
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    if(action == Intent.ACTION_CREATE_DOCUMENT) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    if(Build.VERSION.SDK_INT >= 26) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial?.let(Uri::parse)
        ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderBackupScreen(onBack: () -> Unit, model: ReminderBackupViewModel = viewModel()) {
    val context = LocalContext.current
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode == Activity.RESULT_OK) r.data?.data?.let { model.export(it, r.data?.flags ?: 0) }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if(r.resultCode == Activity.RESULT_OK) r.data?.data?.let(model::inspect)
    }
    val locate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun launch(action: () -> Unit) { runCatching(action).onFailure { model.message = "无法打开系统文件选择器，请检查手机文件管理应用。" } }
    BackHandler(model.busy) { }
    Scaffold(topBar = { TopAppBar(title = { Text("任务提醒备份") }, navigationIcon = { TextButton(onClick = onBack, enabled = !model.busy) { Text("返回") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("备份提醒与常用地点", style = MaterialTheme.typography.titleLarge)
            Text("包含全部提醒点、常用地点、范围、提醒方式、任务关联及已提醒状态。每日记录任务需在每日记录卡片中单独备份。")
            Button(onClick = { launch { export.launch(reminderDocument(Intent.ACTION_CREATE_DOCUMENT).putExtra(Intent.EXTRA_TITLE, model.suggestedName())) } }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("导出备份 · 选择保存位置") }
            Text("建议保存到内部存储 → Download（下载），或你自己的备份文件夹。", style = MaterialTheme.typography.bodySmall)
            if(model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.message?.let { Text(it) }
            model.lastExport?.let { backup ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("最近一次导出的备份", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text("${backup.name}\n${backup.location}") }
                        TextButton(enabled = !model.busy, onClick = { launch { locate.launch(reminderDocument(Intent.ACTION_OPEN_DOCUMENT, backup.uri)) } }) { Text("定位文件") }
                        TextButton(enabled = !model.busy, onClick = { launch {
                            val uri = Uri.parse(backup.uri)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); clipData = ClipData.newRawUri(backup.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "分享任务提醒备份"))
                        } }) { Text("分享备份") }
                    }
                }
            }
            HorizontalDivider()
            Text("恢复已有备份", style = MaterialTheme.typography.titleLarge)
            Text("先检查内容，再确认替换任务提醒和常用地点。不会修改每日记录。换手机时建议先恢复每日记录，再恢复提醒；找不到原任务或任务已完成的提醒会暂停。")
            OutlinedButton(enabled = !model.busy, onClick = { launch { import.launch(reminderDocument(Intent.ACTION_OPEN_DOCUMENT, model.lastExport?.uri)) } }, modifier = Modifier.fillMaxWidth()) { Text("选择文件导入") }
            Text("恢复后进入任务提醒页面，检查通知、定位和准时提醒权限，并恢复地点监测。", style = MaterialTheme.typography.bodySmall)
        }
    }
    model.preview?.let { backup ->
        AlertDialog(onDismissRequest = model::cancel, title = { Text("确认恢复任务提醒") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(model.previewName)
                Text("提醒点：${backup.points.size} 个\n常用地点：${backup.places.size} 个")
                Text("将替换当前所有提醒点与常用地点，不合并。建议先导出当前数据。每日记录不会改变。")
                if(backup.points.isEmpty() && backup.places.isEmpty()) Text("这是空备份，恢复将清空当前提醒和地点。")
                model.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !model.busy, onClick = model::restore) { Text("替换提醒与地点") } }, dismissButton = { TextButton(enabled = !model.busy, onClick = model::cancel) { Text("取消") } })
    }
}
