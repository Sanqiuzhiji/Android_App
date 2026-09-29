package com.example.myapplication.feature.dailyrecord.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.dailyrecord.data.DailyRecordRepository
import com.example.myapplication.feature.dailyrecord.model.CalendarDaySummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val month: YearMonth,
    val today: LocalDate,
    val days: List<CalendarDaySummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val repository: DailyRecordRepository,
    private val dates: DateProvider,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val selectedDate = savedState.get<String>("date")?.let(LocalDate::parse) ?: dates.today()
    private val month = MutableStateFlow(savedState.get<String>("month")?.let(YearMonth::parse) ?: YearMonth.from(selectedDate))
    private val today = MutableStateFlow(dates.today())
    private val revision = MutableStateFlow(0)
    val state = combine(month, today, revision) { month, today, _ -> month to today }
        .flatMapLatest { (month, today) ->
            val first = month.atDay(1)
            repository.observeCalendar(first, month.atEndOfMonth(), today)
                .map { CalendarUiState(month, today, it, loading = false) }
                .onStart { emit(CalendarUiState(month, today)) }
                .catch { emit(CalendarUiState(month, today, loading = false, error = "读取日历失败，请重试")) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState(month.value, today.value))

    fun selectMonth(value: YearMonth) {
        // Practical bounds keep date navigation and year input consistent.
        if (value.year !in 1900..9999) return
        month.value = value
        savedState["month"] = value.toString()
    }
    fun moveMonth(offset: Long) = selectMonth(month.value.plusMonths(offset))
    fun showToday() = selectMonth(YearMonth.from(dates.today()))
    fun refreshToday() { today.value = dates.today() }
    fun retry() { revision.value++ }
}
