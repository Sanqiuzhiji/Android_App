package com.example.myapplication.feature.reminders

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.*
import java.util.UUID

enum class ReminderMode(val label: String) { PLACE("地址"), TIME("时间"), BOTH("地址＋时间") }
data class SavedPlace(val id: String = UUID.randomUUID().toString(), val name: String, val lat: Double, val lon: Double, val radius: Int)
data class ReminderPoint(
    val id: String = UUID.randomUUID().toString(), val title: String, val date: String,
    val time: String?, val early: Int, val window: Int, val mode: ReminderMode,
    val placeId: String?, val radius: Int, val taskId: String, val templateId: String?,
    val sound: Boolean, val vibrate: Boolean, val enabled: Boolean = true, val fired: Boolean = false,
    val repeatMinutes: Int = 10, val lastNotifiedAt: Long = 0,
    val entryArmed: Boolean = false, val stopReason: String = "",
) {
    fun start(): Long = LocalDate.parse(date).atTime(time?.let(LocalTime::parse) ?: LocalTime.MIDNIGHT)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - early * 60_000L
    fun end(): Long = if (time == null) LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        else start() + (early + window) * 60_000L
}

/** One application-scoped store; synchronous commits keep delivery state durable before receivers finish. */
class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("reminders-v2", Context.MODE_PRIVATE)
    val places = MutableStateFlow(read("places").map { SavedPlace(it.getString("id"), it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.getInt("radius")) })
    val points = MutableStateFlow(read("points").map {
        ReminderPoint(it.getString("id"), it.getString("title"), it.getString("date"), it.optString("time").ifEmpty { null },
            it.getInt("early"), it.getInt("window"), ReminderMode.valueOf(it.getString("mode")), it.optString("placeId").ifEmpty { null },
            it.getInt("radius"), it.getString("taskId"), it.optString("templateId").ifEmpty { null }, it.getBoolean("sound"), it.getBoolean("vibrate"), it.getBoolean("enabled"), it.getBoolean("fired"),
            it.optInt("repeatMinutes", 10), it.optLong("lastNotifiedAt", 0), it.optBoolean("entryArmed", false), it.optString("stopReason", ""))
    })
    private fun read(key: String): List<JSONObject> { val a = JSONArray(prefs.getString(key, "[]")); return (0 until a.length()).map { a.getJSONObject(it) } }
    @Synchronized fun exportBackup(): String = ReminderBackupCodec.encode(places.value, points.value)
    @Synchronized fun restoreBackup(backup: ReminderBackup) {
        check(prefs.edit().putString("places", ReminderBackupCodec.placesJson(backup.places).toString())
            .putString("points", ReminderBackupCodec.pointsJson(backup.points).toString()).commit()) { "恢复写入失败" }
        places.value = backup.places
        points.value = backup.points
    }
    @Synchronized fun save(place: SavedPlace) { val next = places.value.filterNot { it.id == place.id } + place
        check(prefs.edit().putString("places", JSONArray(next.map { JSONObject().put("id",it.id).put("name",it.name).put("lat",it.lat).put("lon",it.lon).put("radius",it.radius) }).toString()).commit())
        places.value = next
    }
    @Synchronized fun save(point: ReminderPoint) = write(points.value.filterNot { it.id == point.id } + point)
    @Synchronized fun updateIfCurrent(previous: ReminderPoint, next: ReminderPoint): Boolean {
        if (points.value.find { it.id == previous.id } != previous) return false
        save(next)
        return true
    }
    @Synchronized fun delete(id: String) = write(points.value.filterNot { it.id == id })
    @Synchronized fun deletePlace(id: String) {
        require(points.value.none { it.placeId == id }) { "请先删除或修改引用该地点的提醒" }
        val next = places.value.filterNot { it.id == id }
        check(prefs.edit().putString("places", JSONArray(next.map { JSONObject().put("id",it.id).put("name",it.name).put("lat",it.lat).put("lon",it.lon).put("radius",it.radius) }).toString()).commit()); places.value = next
    }
    private fun write(next: List<ReminderPoint>) {
        check(prefs.edit().putString("points", ReminderBackupCodec.pointsJson(next).toString()).commit()); points.value = next
    }
}
