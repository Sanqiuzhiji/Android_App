package com.example.myapplication.feature.backup.data

import com.example.myapplication.feature.dailyrecord.data.local.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalDate

data class BackupSnapshot(
    val exportedAt: Long,
    val records: List<DailyRecordEntity>,
    val templates: List<FixedTemplateEntity>,
    val tasks: List<TaskEntity>,
)

/** Versioned, portable JSON. No paths, permissions or preview tasks are exported. */
object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    private const val FORMAT = "personal-toolbox-backup"

    fun encode(snapshot: BackupSnapshot): String {
        validate(snapshot)
        return JSONObject().apply {
            put("format", FORMAT)
            put("module", "daily-record")
            put("version", 1)
            put("exportedAt", snapshot.exportedAt)
            put("records", JSONArray().apply { snapshot.records.forEach { row ->
                put(JSONObject().put("date", row.date).put("createdAt", row.createdAt))
            } })
            put("templates", JSONArray().apply { snapshot.templates.forEach { row ->
                put(JSONObject().put("id", row.id).put("title", row.title)
                    .put("effectiveFrom", row.effectiveFrom).put("effectiveUntil", row.effectiveUntil ?: JSONObject.NULL)
                    .put("sortOrder", row.sortOrder))
            } })
            put("tasks", JSONArray().apply { snapshot.tasks.forEach { row ->
                put(JSONObject().put("id", row.id).put("date", row.date).put("title", row.title).put("type", row.type)
                    .put("templateId", row.templateId ?: JSONObject.NULL).put("completed", row.completed)
                    .put("completedTime", row.completedTime ?: JSONObject.NULL).put("sortOrder", row.sortOrder))
            } })
        }.toString(2)
    }

    fun decode(text: String): BackupSnapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "备份文件超过 20 MB，暂不支持导入" }
        val reader = JSONTokener(text.removePrefix("\uFEFF"))
        val root = reader.nextValue() as? JSONObject ?: error("备份必须是 JSON 对象")
        require(reader.nextClean() == '\u0000') { "备份文件末尾包含无效内容" }
        require(root.string("format") == FORMAT) { "这不是个人工具箱的备份文件" }
        // Earlier v1 files had no module field and contained daily-record data only.
        require(!root.has("module") || root.string("module") == "daily-record") { "这不是每日记录的备份文件" }
        require(root.integer("version") == 1L) { "不支持此备份版本，请更新应用后重试" }
        val result = BackupSnapshot(root.integer("exportedAt"),
            root.objects("records").map { DailyRecordEntity(it.string("date"), it.integer("createdAt")) },
            root.objects("templates").map {
                FixedTemplateEntity(it.string("id"), it.string("title"), it.string("effectiveFrom"), it.nullableString("effectiveUntil"), it.integer("sortOrder"))
            },
            root.objects("tasks").map {
                val completed = it.get("completed") as? Boolean ?: error("完成状态格式错误")
                TaskEntity(it.string("id"), it.string("date"), it.string("title"), it.string("type"),
                    it.nullableString("templateId"), completed, if (it.get("completedTime") == JSONObject.NULL) null else it.integer("completedTime"), it.integer("sortOrder"))
            },
        )
        validate(result)
        return result
    }

    fun validate(snapshot: BackupSnapshot) {
        require(snapshot.exportedAt >= 0) { "备份时间无效" }
        require(snapshot.records.size <= 100_000 && snapshot.templates.size <= 100_000 && snapshot.tasks.size <= 100_000) { "备份中的记录数量超出支持范围" }
        require(snapshot.records.map { it.date }.toSet().size == snapshot.records.size) { "存在重复日期" }
        require(snapshot.templates.map { it.id }.toSet().size == snapshot.templates.size) { "存在重复固定模板" }
        require(snapshot.tasks.map { it.id }.toSet().size == snapshot.tasks.size) { "存在重复任务" }
        val recordDates = snapshot.records.map { it.date }.toSet()
        val templates = snapshot.templates.associateBy { it.id }
        val fixedKeys = mutableSetOf<Pair<String, String>>()
        snapshot.records.forEach { date(it.date); require(it.createdAt >= 0) { "记录时间无效" } }
        snapshot.templates.forEach {
            id(it.id); title(it.title)
            val from = date(it.effectiveFrom)
            require(it.effectiveUntil == null || date(it.effectiveUntil) >= from) { "固定模板生效日期无效" }
            require(it.sortOrder >= 0) { "模板排序值无效" }
        }
        snapshot.tasks.forEach {
            id(it.id); title(it.title); date(it.date)
            require(it.date in recordDates) { "任务缺少对应的每日记录" }
            require(it.sortOrder >= 0) { "任务排序值无效" }
            require(it.completed == (it.completedTime != null) && (it.completedTime == null || it.completedTime >= 0)) { "任务完成时间无效" }
            when (it.type) {
                "FIXED" -> {
                    val templateId = it.templateId
                    require(templateId != null && templateId in templates) { "固定任务缺少模板" }
                    require(fixedKeys.add(it.date to templateId)) { "同一天存在重复固定任务" }
                }
                "CUSTOM" -> require(it.templateId == null) { "临时任务不能关联固定模板" }
                else -> error("未知任务类型")
            }
        }
    }

    private fun date(value: String): LocalDate {
        val date = LocalDate.parse(value)
        require(date.year in 1900..9999 && date.toString() == value) { "日期格式无效" }
        return date
    }
    private fun id(value: String) { require(value.isNotBlank() && value.length <= 128 && !value.startsWith("preview:")) { "记录标识无效" } }
    private fun title(value: String) { require(value.trim().isNotEmpty() && value.length <= 100) { "任务名称无效" } }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("字段 $key 必须是文字")
    private fun JSONObject.nullableString(key: String): String? = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "字段 $key 必须是整数" }
        return (value as Number).toLong()
    }
    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = get(key) as? JSONArray ?: error("字段 $key 必须是列表")
        require(array.length() <= 100_000) { "备份中的记录数量超出支持范围" }
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
}
