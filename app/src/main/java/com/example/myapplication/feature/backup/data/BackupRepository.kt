package com.example.myapplication.feature.backup.data

import androidx.room.withTransaction
import com.example.myapplication.core.database.ToolboxDatabase
import com.example.myapplication.core.time.DateProvider

class BackupRepository(private val database: ToolboxDatabase, private val dates: DateProvider) {
    private val dao = database.backupDao()

    suspend fun snapshot(): BackupSnapshot = database.withTransaction {
        BackupSnapshot(dates.nowMillis(), dao.records(), dao.templates(), dao.tasks())
    }

    suspend fun restore(snapshot: BackupSnapshot) {
        BackupCodec.validate(snapshot)
        // Clear and insert within one transaction. Any failure rolls the whole restore back.
        database.withTransaction {
            dao.clearTasks()
            dao.clearRecords()
            dao.clearTemplates()
            dao.insertRecords(snapshot.records)
            dao.insertTemplates(snapshot.templates)
            dao.insertTasks(snapshot.tasks)
        }
    }
}
