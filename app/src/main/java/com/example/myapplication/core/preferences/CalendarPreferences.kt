package com.example.myapplication.core.preferences

import android.content.Context

interface CalendarPreferences {
    var showStatusText: Boolean
}

class LocalCalendarPreferences(context: Context) : CalendarPreferences {
    private val preferences = context.applicationContext.getSharedPreferences("toolbox_preferences", Context.MODE_PRIVATE)
    override var showStatusText: Boolean
        get() = preferences.getBoolean("daily_record_calendar_show_status_text", false)
        set(value) { preferences.edit().putBoolean("daily_record_calendar_show_status_text", value).apply() }
}
