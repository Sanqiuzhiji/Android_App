package com.example.myapplication.feature.dailyrecord.model

import java.time.LocalDate
import java.time.YearMonth

/** Blank cells align weekdays without displaying dates from neighbouring months. */
fun monthGrid(month: YearMonth): List<List<LocalDate?>> {
    val cells = MutableList<LocalDate?>(month.atDay(1).dayOfWeek.value - 1) { null }
    (1..month.lengthOfMonth()).forEach { cells += month.atDay(it) }
    while (cells.size % 7 != 0) cells += null
    return cells.chunked(7)
}
