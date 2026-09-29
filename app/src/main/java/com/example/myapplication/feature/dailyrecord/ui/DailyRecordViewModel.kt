package com.example.myapplication.feature.dailyrecord.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.dailyrecord.data.DailyRecordRepository
import com.example.myapplication.feature.dailyrecord.model.DailyRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DailyRecordUiState(
    val date: LocalDate,
    val record: DailyRecord = DailyRecord(date, emptyList()),
    val loading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DailyRecordViewModel(
    private val repository: DailyRecordRepository,
    private val dates: DateProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private var knownToday = dates.today()
    private val selectedDate = MutableStateFlow(
        if (savedState.get<String>("sessionDate") == knownToday.toString()) {
            savedState.get<String>("selectedDate")?.let(LocalDate::parse) ?: knownToday
        } else knownToday,
    )
    private val revision = MutableStateFlow(0)
    private val _today = MutableStateFlow(knownToday)
    val today = _today.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    val state: StateFlow<DailyRecordUiState> = combine(selectedDate, revision) { date, _ -> date }
        .flatMapLatest { date ->
            flow {
                emit(DailyRecordUiState(date))
                repository.ensureDay(date)
                emitAll(repository.observeDay(date).map { DailyRecordUiState(date, it, loading = false) })
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(DailyRecordUiState(date, loading = false, error = "读取记录失败，请重试"))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyRecordUiState(selectedDate.value))

    fun selectDate(date: LocalDate) {
        if (date.year !in 1900..9999) return
        selectedDate.value = date
        rememberDate()
    }
    fun moveDay(offset: Long) = selectDate(selectedDate.value.plusDays(offset))
    fun goToToday() = selectDate(dates.today())
    fun retry() { revision.value++ }
    fun clearMessage() { _message.value = null }

    fun refreshToday(onForeground: Boolean = false) {
        val current = dates.today()
        if (current != knownToday) {
            if (onForeground || selectedDate.value == knownToday) selectedDate.value = current
            knownToday = current
            _today.value = current
            revision.value++ // Materialize a previously previewed day when midnight is crossed.
            rememberDate()
        }
    }

    fun addTask(date: LocalDate, title: String, onSaved: () -> Unit) {
        perform(onSaved) { repository.addCustomTask(date, title) }
    }
    fun setCompleted(id: String, completed: Boolean) = perform { repository.setCompleted(id, completed) }
    fun renameTask(id: String, title: String, onSaved: () -> Unit) = perform(onSaved) {
        repository.renameCustomTask(id, title)
    }
    fun deleteTask(id: String) = perform { repository.deleteCustomTask(id) }
    fun moveTask(id: String, direction: Int) = perform { repository.moveCustomTask(id, direction) }
    fun moveTaskTo(id: String, targetId: String, onFinished: (Boolean) -> Unit) =
        perform(onFinished = onFinished) { repository.moveCustomTaskTo(id, targetId) }

    private fun perform(onSaved: () -> Unit = {}, onFinished: (Boolean) -> Unit = {}, action: suspend () -> Unit) {
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

    private fun rememberDate() {
        savedState["sessionDate"] = knownToday.toString()
        savedState["selectedDate"] = selectedDate.value.toString()
    }
    init { rememberDate() }
}
