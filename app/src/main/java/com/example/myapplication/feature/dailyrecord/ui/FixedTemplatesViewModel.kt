package com.example.myapplication.feature.dailyrecord.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.dailyrecord.data.DailyRecordRepository
import com.example.myapplication.feature.dailyrecord.model.FixedTaskTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TemplatesUiState(
    val templates: List<FixedTaskTemplate> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FixedTemplatesViewModel(
    private val repository: DailyRecordRepository,
    private val dates: DateProvider,
) : ViewModel() {
    private val day = MutableStateFlow(dates.today())
    private val revision = MutableStateFlow(0)
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    val state = combine(day, revision) { date, _ -> date }.flatMapLatest { date ->
        repository.observeTemplates(date)
            .map { TemplatesUiState(it, loading = false) }
            .catch { emit(TemplatesUiState(loading = false, error = "读取清单失败，请重试")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplatesUiState())
    val stoppedState = revision.flatMapLatest {
        repository.observeStoppedTemplates()
            .map { TemplatesUiState(it, loading = false) }
            .catch { emit(TemplatesUiState(loading = false, error = "读取已停用任务失败，请重试")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplatesUiState())

    fun refreshToday() { day.value = dates.today() }
    fun retry() { revision.value++ }
    fun clearMessage() { _message.value = null }
    fun save(id: String?, title: String, onSaved: () -> Unit) = perform(onSaved) {
        if (id == null) repository.addTemplate(title) else repository.renameTemplate(id, title)
    }
    fun stop(id: String, onSaved: () -> Unit) = perform(onSaved) { repository.stopTemplate(id) }
    fun reactivate(id: String, onSaved: () -> Unit = {}) = perform(onSaved) { repository.reactivateTemplate(id) }
    fun move(id: String, direction: Int) = perform({}) { repository.moveTemplate(id, direction) }
    fun moveTo(id: String, targetId: String, onFinished: (Boolean) -> Unit) =
        perform({}, onFinished) { repository.moveTemplateTo(id, targetId) }

    private fun perform(onSaved: () -> Unit, onFinished: (Boolean) -> Unit = {}, action: suspend () -> Unit) {
        if (_saving.value) { onFinished(false); return }
        _saving.value = true
        viewModelScope.launch {
            var success = false
            try { action(); onSaved(); success = true }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _message.value = if (error is IllegalArgumentException) error.message else "保存失败，请重试"
            }
            finally { _saving.value = false; onFinished(success) }
        }
    }
}
