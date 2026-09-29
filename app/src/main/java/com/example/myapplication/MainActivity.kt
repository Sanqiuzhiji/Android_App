package com.example.myapplication

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.myapplication.app.ToolboxApp
import com.example.myapplication.app.ToolboxApplication
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private var reminderDate by androidx.compose.runtime.mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reminderDate = intent.getStringExtra("reminderDate")
        enableEdgeToEdge()
        val container = (application as ToolboxApplication).container
        setContent {
            MyApplicationTheme {
                ToolboxApp(container.records, container.dates, container.calendarPreferences, container.backups, container.backupFiles, reminderDate) { reminderDate = null }
            }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reminderDate = intent.getStringExtra("reminderDate")
    }
    override fun onResume() {
        super.onResume()
        com.example.myapplication.feature.reminders.ReminderRuntime.sync(this, true)
        lifecycleScope.launch { com.example.myapplication.feature.reminders.ReminderRuntime.evaluate(this@MainActivity) }
    }
}
