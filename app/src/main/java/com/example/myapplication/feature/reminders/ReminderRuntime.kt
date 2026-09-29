package com.example.myapplication.feature.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.app.ToolboxApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

internal val Context.reminderStore get() = (applicationContext as ToolboxApplication).container.reminders

object ReminderRuntime {
    private val deliveryLock = Mutex()
    suspend fun restore(c: Context, backup: ReminderBackup) = deliveryLock.withLock {
        c.reminderStore.restoreBackup(backup)
        sync(c, false)
    }
    @Volatile internal var currentLocation: Location? = null
    suspend fun setEnabled(c: Context, id: String, enabled: Boolean) = deliveryLock.withLock {
        val p = c.reminderStore.points.value.find { it.id == id } ?: return@withLock
        c.reminderStore.save(p.copy(enabled = enabled, stopReason = "", entryArmed = false,
            lastNotifiedAt = if(enabled && p.mode != ReminderMode.PLACE && System.currentTimeMillis() >= p.start()) System.currentTimeMillis() else p.lastNotifiedAt))
        if (!enabled) c.getSystemService(NotificationManager::class.java).cancel(p.id.hashCode())
        schedule(c)
    }
    suspend fun refreshTasks(c: Context) = deliveryLock.withLock {
        val records = (c.applicationContext as ToolboxApplication).container.records
        val points = c.reminderStore.points.value
        val days = points.map { it.date }.distinct().associateWith { records.observeDay(LocalDate.parse(it)).first() }
        for (p in points) {
            val task = days.getValue(p.date).tasks.find { if(p.templateId != null) it.templateId == p.templateId else it.id == p.taskId }
            val reason = if(task == null) "missing" else if(task.completed) "completed" else ""
            if(reason.isNotEmpty()) {
                val stopped = p.copy(enabled = false, stopReason = reason, entryArmed = false)
                if(stopped != p) c.reminderStore.updateIfCurrent(p, stopped)
                c.getSystemService(NotificationManager::class.java).cancel(p.id.hashCode())
            }
        }
        sync(c)
    }
    fun locationAllowed(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun exactAllowed(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 700, Intent(c, ReminderReceiver::class.java).setAction("REMINDER_ALARM"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(c: Context) {
        val alarm = c.getSystemService(AlarmManager::class.java)
        alarm.cancel(pending(c))
        val now = System.currentTimeMillis()
        val next = c.reminderStore.points.value.mapNotNull { nextReminderCheck(it, now) }.minOrNull() ?: return
        try {
            if (exactAllowed(c)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(c))
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(c))
        } catch (_: SecurityException) { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(c)) }
    }
    fun sync(c: Context, startLocation: Boolean = false) {
        schedule(c)
        val needsLocation = c.reminderStore.points.value.any { it.enabled && it.stopReason.isEmpty() && it.mode != ReminderMode.TIME && (it.mode == ReminderMode.PLACE || it.end() >= System.currentTimeMillis()) }
        if (!needsLocation) c.stopService(Intent(c, ReminderLocationService::class.java))
        else if (startLocation && locationAllowed(c)) ContextCompat.startForegroundService(c, Intent(c, ReminderLocationService::class.java))
    }
    suspend fun evaluate(c: Context, location: Location? = currentLocation) = deliveryLock.withLock {
        val now = System.currentTimeMillis()
        val records = (c.applicationContext as ToolboxApplication).container.records
        for (p in c.reminderStore.points.value.filter { it.enabled }) {
            val place = c.reminderStore.places.value.find { it.id == p.placeId }
            val inside: Boolean? = if(location != null && place != null && location.hasAccuracy() && location.accuracy <= p.radius &&
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) in 0..120_000_000_000L) {
                val distance = FloatArray(1).also { Location.distanceBetween(location.latitude, location.longitude, place.lat, place.lon, it) }[0]
                when { distance <= p.radius -> true; distance > p.radius + maxOf(25f, location.accuracy) -> false; else -> null }
            } else null
            val day = records.observeDay(LocalDate.parse(p.date)).first()
            val task = day.tasks.find { if (p.templateId != null) it.templateId == p.templateId else it.id == p.taskId }
            if (task == null || task.completed) {
                c.reminderStore.updateIfCurrent(p, p.copy(enabled = false, stopReason = if(task == null) "missing" else "completed", entryArmed = false))
                c.getSystemService(NotificationManager::class.java).cancel(p.id.hashCode())
                continue
            }
            val decision = reminderDecision(p, now, inside)
            if (!decision.notify) {
                if(decision.armed != p.entryArmed) c.reminderStore.updateIfCurrent(p, p.copy(entryArmed = decision.armed))
                continue
            }
            if(c.reminderStore.points.value.find { it.id == p.id } != p) continue
            if (!c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) continue
            val channel = "reminder_${p.sound}_${p.vibrate}"
            val manager = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(channel, "任务提醒 · ${if(p.sound) "响铃" else "静音"} · ${if(p.vibrate) "震动" else "不震动"}", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(p.vibrate)
                    setSound(if(p.sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                })
                if (manager.getNotificationChannel(channel).importance == NotificationManager.IMPORTANCE_NONE) continue
            }
            val open = PendingIntent.getActivity(c, p.id.hashCode(), Intent(c, MainActivity::class.java).putExtra("reminderDate", p.date).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(c, channel).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(task.title).setContentText("${p.date} · ${p.mode.label}提醒${place?.let { " · ${it.name}" } ?: ""}")
                .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(if(p.sound) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) else null)
                .setVibrate(if(p.vibrate) longArrayOf(0, 400, 200, 400) else longArrayOf(0)).build()
            try {
                manager.notify(p.id.hashCode(), notification)
                c.reminderStore.updateIfCurrent(p, p.copy(fired = true, lastNotifiedAt = now, entryArmed = false))
            } catch (_: SecurityException) { }
        }
        schedule(c)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderRuntime.evaluate(context)
                ReminderRuntime.schedule(context)
                // A running location service also checks window starts while the user is already inside.
                if (intent.action == Intent.ACTION_BOOT_COMPLETED && context.reminderStore.points.value.any { it.enabled && it.mode != ReminderMode.TIME }) {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("resume_location", "恢复地点提醒", NotificationManager.IMPORTANCE_DEFAULT))
                    val open = PendingIntent.getActivity(context, 701, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    if (manager.areNotificationsEnabled()) manager.notify(701, NotificationCompat.Builder(context, "resume_location").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("恢复地点提醒")
                        .setContentText("手机已重启，请打开个人工具箱恢复地点监测").setContentIntent(open).setAutoCancel(true).build())
                }
            } finally { result.finish() }
        }
    }
}

class ReminderLocationService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var last: Location? = null
    private lateinit var manager: LocationManager
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel("location_monitor", "地点提醒运行状态", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 702, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(702, NotificationCompat.Builder(this, "location_monitor").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("个人工具箱正在监测提醒地点").setContentText("点击管理提醒点；暂停全部地址提醒后停止定位").setContentIntent(open).setOngoing(true).build())
        manager = getSystemService(LocationManager::class.java)
        if (!ReminderRuntime.locationAllowed(this)) { stopSelf(); return }
        try { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.getAllProviders().contains(it) }.forEach { manager.requestLocationUpdates(it, 15_000L, 0f, this) } }
        catch (_: SecurityException) { stopSelf(); return }
        scope.launch {
            while (isActive) { ReminderRuntime.evaluate(this@ReminderLocationService, last); ReminderRuntime.sync(this@ReminderLocationService); delay(15_000) }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onLocationChanged(location: Location) { last = location; ReminderRuntime.currentLocation = location; scope.launch { ReminderRuntime.evaluate(this@ReminderLocationService, location) } }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { last = null; ReminderRuntime.currentLocation = null }
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onDestroy() { scope.cancel(); ReminderRuntime.currentLocation = null; if (::manager.isInitialized) manager.removeUpdates(this); super.onDestroy() }
}
