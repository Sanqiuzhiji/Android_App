package com.example.myapplication.feature.backup.ui

import android.app.Activity
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.myapplication.feature.backup.data.BackupFiles

private fun documentIntent(action: String, initial: String? = null): Intent = Intent(action).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = if (action == Intent.ACTION_CREATE_DOCUMENT) "application/json" else "*/*"
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    if (action == Intent.ACTION_CREATE_DOCUMENT) addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    if (Build.VERSION.SDK_INT >= 26) {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial?.let(Uri::parse)
            ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(model: BackupViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { model.export(it, result.data?.flags ?: 0) }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { model.inspect(it) }
    }
    // This picker is only for locating a file; selecting a file here never imports it.
    val locatePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    fun safelyLaunch(action: () -> Unit) {
        try { action() } catch (_: Exception) { model.showMessage("无法打开，请使用手机文件管理器按下方文件名搜索，或重新选择文件。") }
    }
    BackHandler(enabled = state.busy) { }
    Scaffold(topBar = {
        TopAppBar(title = { Text("每日记录备份") }, navigationIcon = {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("仅备份每日记录", style = MaterialTheme.typography.titleLarge)
            Text("建议保存到“内部存储 → Download（下载）”，也可以在文件选择器中创建“个人工具箱备份”文件夹。保存前请确认顶部显示的文件夹。")
            Button(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = {
                safelyLaunch { exportPicker.launch(documentIntent(Intent.ACTION_CREATE_DOCUMENT).putExtra(Intent.EXTRA_TITLE, model.suggestedName())) }
            }) { Text("导出备份 · 选择保存位置") }
            Text("仅包含每日记录的任务、完成时间和固定清单历史，不包含其他工具的数据。备份文件为 JSON，可复制到电脑或分享保存。", style = MaterialTheme.typography.bodySmall)
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在处理，请稍候…")
            }
            state.message?.let { message ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(16.dp))
                    TextButton(onClick = model::clearMessage) { Text("知道了") }
                }
            }
            state.lastExport?.let { backup ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("最近一次导出的备份", style = MaterialTheme.typography.titleMedium)
                        SelectionContainer { Text("文件名：${backup.name}\n\n保存位置：${backup.location}") }
                        TextButton(enabled = !state.busy, onClick = {
                            safelyLaunch { locatePicker.launch(documentIntent(Intent.ACTION_OPEN_DOCUMENT, backup.uri)) }
                        }) { Text("定位文件") }
                        TextButton(enabled = backup.name != BackupFiles.UNKNOWN_NAME, onClick = { clipboard.setText(AnnotatedString(backup.name)); model.showMessage("文件名已复制，可在手机文件管理器中搜索。") }) { Text("复制文件名") }
                        TextButton(enabled = !state.busy, onClick = {
                            safelyLaunch {
                                val uri = Uri.parse(backup.uri)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri(backup.name, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "分享备份文件"))
                            }
                        }) { Text("分享备份") }
                        Text("定位由系统文件选择器提供；部分手机或网盘不会自动跳转目录，可使用复制的文件名搜索。文件移动或删除后，需要重新选择。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Text("恢复已有备份", style = MaterialTheme.typography.titleLarge)
            Text("选择每日记录的 JSON 备份。先检查内容，再确认恢复；只替换每日记录的数据，不影响其他工具。建议先导出当前记录。")
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !state.busy, onClick = {
                safelyLaunch { importPicker.launch(documentIntent(Intent.ACTION_OPEN_DOCUMENT, state.lastExport?.uri)) }
            }) { Text("选择文件导入") }
        }
    }
    state.preview?.let { preview ->
        val time = Instant.ofEpochMilli(preview.exportedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        AlertDialog(onDismissRequest = model::cancelImport, title = { Text("确认恢复备份") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(preview.name)
                    Text("导出时间：$time\n每日记录：${preview.days} 天\n固定模板（含历史版本）：${preview.templates} 项\n任务：${preview.tasks} 项")
                    Text(if (preview.days == 0 && preview.templates == 0 && preview.tasks == 0) "这是空备份，恢复会清空每日记录的数据，不影响其他工具。" else "此操作只替换每日记录的数据，不是合并，也不会修改其他工具。")
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = !state.busy, onClick = model::restore) { Text(if (state.busy) "恢复中…" else "替换每日记录数据") } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = model::cancelImport) { Text("取消") } },
        )
    }
}
