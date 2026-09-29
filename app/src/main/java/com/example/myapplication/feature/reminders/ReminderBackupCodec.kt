package com.example.myapplication.feature.reminders

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

data class ReminderBackup(val places: List<SavedPlace>, val points: List<ReminderPoint>, val exportedAt: Long)

object ReminderBackupCodec {
    fun placesJson(places: List<SavedPlace>) = JSONArray(places.map {
        JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lon", it.lon).put("radius", it.radius)
    })
    fun pointsJson(points: List<ReminderPoint>) = JSONArray(points.map {
        JSONObject().put("id", it.id).put("title", it.title).put("date", it.date).put("time", it.time ?: "")
            .put("early", it.early).put("window", it.window).put("mode", it.mode.name).put("placeId", it.placeId ?: "")
            .put("radius", it.radius).put("taskId", it.taskId).put("templateId", it.templateId ?: "")
            .put("sound", it.sound).put("vibrate", it.vibrate).put("enabled", it.enabled).put("fired", it.fired)
            .put("repeatMinutes", it.repeatMinutes).put("lastNotifiedAt", it.lastNotifiedAt)
            .put("entryArmed", it.entryArmed).put("stopReason", it.stopReason)
    })
    fun encode(places: List<SavedPlace>, points: List<ReminderPoint>): String = JSONObject()
        .put("module", "task-reminders").put("version", 2).put("exportedAt", System.currentTimeMillis())
        .put("places", placesJson(places)).put("points", pointsJson(points)).toString(2)

    fun decode(text: String): ReminderBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= 20 * 1024 * 1024) { "备份超过 20 MB" }
        val root = JSONObject(text)
        require(root.getString("module") == "task-reminders") { "请选择任务提醒备份，不能导入每日记录备份" }
        require(root.getInt("version") in 1..2) { "暂不支持该备份版本" }
        val placeArray = root.getJSONArray("places")
        val pointArray = root.getJSONArray("points")
        require(placeArray.length() <= 10000 && pointArray.length() <= 10000) { "备份项目过多（最多各 10000 项）" }
        val places = (0 until placeArray.length()).map { index ->
            val o = placeArray.getJSONObject(index)
            SavedPlace(o.text("id", 128), o.text("name", 60), o.getDouble("lat"), o.getDouble("lon"), o.getInt("radius")).also {
                require(it.lat.isFinite() && it.lat in -90.0..90.0 && it.lon.isFinite() && it.lon in -180.0..180.0 && it.radius in 50..10000) { "地点坐标或半径无效" }
            }
        }
        val ids = places.map { it.id }.toSet()
        require(ids.size == places.size) { "地点 ID 重复" }
        val points = (0 until pointArray.length()).map { index ->
            val o = pointArray.getJSONObject(index)
            ReminderPoint(o.text("id",128), o.text("title",100), o.text("date",10), o.getString("time").ifEmpty { null },
                o.getInt("early"), o.getInt("window"), ReminderMode.valueOf(o.getString("mode")), o.getString("placeId").ifEmpty { null },
                o.getInt("radius"), o.text("taskId",256), o.getString("templateId").ifEmpty { null },
                o.getBoolean("sound"), o.getBoolean("vibrate"), o.getBoolean("enabled"), o.getBoolean("fired"),
                o.optInt("repeatMinutes", 10), o.optLong("lastNotifiedAt", 0), false, o.optString("stopReason", "")).also {
                require(it.repeatMinutes in 1..1440 && it.lastNotifiedAt in 0..253402300799999L && it.stopReason in setOf("", "completed", "missing")) { "重复提醒设置无效" }
                require(LocalDate.parse(it.date).year in 1900..9999) { "提醒日期超出范围" }
                it.time?.let(LocalTime::parse)
                require(it.early in 0..1440 && it.window in 0..1440 && it.radius in 50..10000 && (it.sound || it.vibrate)) { "提醒设置无效" }
                require(it.mode == ReminderMode.TIME || it.placeId in ids) { "提醒引用的地点不存在" }
                require(it.placeId == null || it.placeId in ids) { "地点关联无效" }
                require(it.templateId == null || it.templateId.length in 1..128) { "固定任务关联无效" }
                require(it.mode != ReminderMode.BOTH || it.time == null || it.early + it.window > 0) { "组合提醒时间区间无效" }
            }
        }
        require(points.map { it.id }.toSet().size == points.size) { "提醒 ID 重复" }
        val exportedAt = root.getLong("exportedAt")
        require(exportedAt in 0..253402300799999L) { "导出时间无效" }
        return ReminderBackup(places, points, exportedAt)
    }
    private fun JSONObject.text(key: String, max: Int) = getString(key).also { require(it.isNotBlank() && it.length <= max) { "字段 $key 无效" } }
}
