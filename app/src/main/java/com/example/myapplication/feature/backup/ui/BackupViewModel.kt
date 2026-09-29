package com.example.myapplication.feature.backup.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.feature.backup.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportPreview(val name: String, val exportedAt: Long, val days: Int, val templates: Int, val tasks: Int)
data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val lastExport: ExportedBackup? = null,
    val preview: ImportPreview? = null,
)

class BackupViewModel(private val repository: BackupRepository, private val files: BackupFiles) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState(lastExport = files.lastExport()))
    val state = _state.asStateFlow()
    private var pending: BackupSnapshot? = null
    fun suggestedName() = files.suggestedName()
    fun clearMessage() { _state.value = _state.value.copy(message = null) }
    fun showMessage(message: String) { _state.value = _state.value.copy(message = message) }

    fun export(uri: Uri, flags: Int) = work("导出失败，所选位置可能留下不完整文件，请重新导出") {
        val result = withContext(Dispatchers.IO) {
            val text = BackupCodec.encode(repository.snapshot())
            files.keepPermission(uri, flags)
            files.write(uri, text)
        }
        _state.value = _state.value.copy(lastExport = result, message = "备份已导出。下方显示文件名和保存位置，可直接定位或分享。")
    }

    fun inspect(uri: Uri) {
        if (_state.value.busy) return
        pending = null
        _state.value = _state.value.copy(preview = null)
        work("无法导入：文件不完整、格式不兼容或无法读取。现有记录未改变") {
            val (snapshot, name) = withContext(Dispatchers.IO) {
                BackupCodec.decode(files.read(uri)) to files.displayName(uri)
            }
            pending = snapshot
            _state.value = _state.value.copy(preview = ImportPreview(name, snapshot.exportedAt, snapshot.records.size, snapshot.templates.size, snapshot.tasks.size))
        }
    }

    fun cancelImport() {
        if (_state.value.busy) return
        pending = null
        _state.value = _state.value.copy(preview = null)
    }

    fun restore() {
        val snapshot = pending ?: return
        work("恢复失败，现有记录已保留，请重试") {
            withContext(Dispatchers.IO) { repository.restore(snapshot) }
            pending = null
            _state.value = _state.value.copy(preview = null, message = "恢复完成：${snapshot.records.size} 天记录，${snapshot.tasks.size} 项任务。")
        }
    }

    private fun work(failure: String, action: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { showMessage(failure) }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
