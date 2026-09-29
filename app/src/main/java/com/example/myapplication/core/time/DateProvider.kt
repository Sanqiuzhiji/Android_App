package com.example.myapplication.core.time

import java.time.Clock
import java.time.LocalDate

class DateProvider(private val clock: Clock? = null) {
    // Resolve the system zone on each call so timezone changes are respected.
    fun today(): LocalDate = LocalDate.now(clock ?: Clock.systemDefaultZone())
    fun nowMillis(): Long = (clock ?: Clock.systemDefaultZone()).millis()
}
