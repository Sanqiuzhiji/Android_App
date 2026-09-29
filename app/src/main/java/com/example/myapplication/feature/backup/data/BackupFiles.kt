package com.example.myapplication.feature.backup.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ExportedBackup(val uri: String, val name: String, val location: String)

/** Only accesses documents explicitly selected in the system picker. No storage permission is needed. */
class BackupFiles(context: Context, preferenceName: String = "backup_locations") {
    companion object { const val UNKNOWN_NAME = "文件名暂不可读，请定位文件查看" }
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val preferences = this.context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    fun suggestedName(): String = "每日记录备份_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.json"

    fun lastExport(): ExportedBackup? {
        val uri = preferences.getString("last_uri", null) ?: return null
        return ExportedBackup(uri, preferences.getString("last_name", UNKNOWN_NAME)!!, preferences.getString("last_location", "系统文件选择器中选定的位置")!!)
    }

    fun keepPermission(uri: Uri, flags: Int) {
        val grants = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (grants != 0) try { resolver.takePersistableUriPermission(uri, grants) } catch (_: SecurityException) {
            // Some providers only grant access for this session. Export/import can still succeed.
        }
    }

    fun write(uri: Uri, text: String): ExportedBackup {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= BackupCodec.MAX_BYTES) { "备份超过 20 MB，暂不支持导出" }
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选位置")
        stream.use { it.write(bytes); it.flush() }
        val name = displayName(uri)
        val backup = ExportedBackup(uri.toString(), name, location(uri, name))
        val previous = preferences.getString("last_uri", null)
        preferences.edit().putString("last_uri", backup.uri).putString("last_name", backup.name)
            .putString("last_location", backup.location).apply()
        // Only the latest export needs a retained grant; releasing a grant never deletes a file.
        if (previous != null && previous != backup.uri) runCatching {
            resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == previous }?.let { permission ->
                val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                resolver.releasePersistableUriPermission(permission.uri, flags)
            }
        }
        return backup
    }

    fun read(uri: Uri): String {
        val stream = resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        val bytes = stream.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val length = source.read(buffer)
                if (length == -1) break
                require(output.size() + length <= BackupCodec.MAX_BYTES) { "备份文件超过 20 MB，暂不支持导入" }
                output.write(buffer, 0, length)
            }
            output.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    fun displayName(uri: Uri): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: UNKNOWN_NAME
    } catch (_: Exception) { UNKNOWN_NAME }

    private fun location(uri: Uri, name: String): String {
        if (uri.authority == "com.android.externalstorage.documents") {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (id != null && ':' in id) {
                val volume = id.substringBefore(':')
                val path = id.substringAfter(':').replace("/", " / ")
                return "${if (volume == "primary") "内部存储" else "存储卷 $volume"} / $path"
            }
        }
        // Cloud and OEM providers may not expose a filesystem path. Do not invent one.
        val provider = runCatching {
            context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)?.loadLabel(context.packageManager)?.toString()
        }.getOrNull() ?: "系统文件选择器"
        return "$provider / $name\n具体目录请点“定位文件”查看"
    }
}
