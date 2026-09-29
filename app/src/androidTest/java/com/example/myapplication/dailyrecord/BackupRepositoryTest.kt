package com.example.myapplication.dailyrecord

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.core.database.ToolboxDatabase
import com.example.myapplication.core.time.DateProvider
import com.example.myapplication.feature.backup.data.*
import com.example.myapplication.feature.dailyrecord.data.LocalDailyRecordRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    private lateinit var database: ToolboxDatabase
    private lateinit var records: LocalDailyRecordRepository
    private lateinit var backups: BackupRepository
    private val dates = DateProvider(MutableTestClock())

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ToolboxDatabase::class.java).build()
        records = LocalDailyRecordRepository(database, dates)
        backups = BackupRepository(database, dates)
    }
    @After fun close() { database.close() }

    @Test fun jsonRoundTripRestoresHistoryCompletionAndFuturePlans() = runBlocking {
        records.addTemplate("晨跑")
        val template = records.observeTemplates(dates.today()).first().single()
        val task = records.observeDay(dates.today()).first().fixed.single()
        records.setCompleted(task.id, true)
        records.renameTemplate(template.id, "慢跑30分钟")
        records.addCustomTask(dates.today().plusDays(3), "购买设备：\"备用\"\n核对型号")
        val original = backups.snapshot()
        val json = BackupCodec.encode(original)
        assertFalse(json.contains("preview:"))
        val parsed = BackupCodec.decode(json)
        assertEquals(original, parsed)
        records.addCustomTask(dates.today(), "备份之后才添加的任务")
        backups.restore(parsed)
        assertEquals(original, backups.snapshot())
        val restored = records.observeDay(dates.today()).first().fixed.single()
        assertTrue(restored.completed)
        assertEquals(task.id, restored.id)
        assertNotNull(restored.completedTime)
        assertEquals("慢跑30分钟", records.observeDay(dates.today().plusDays(3)).first().fixed.single().title)
    }

    @Test fun duplicateOrOrphanRecordsAreRejectedBeforeExistingDataChanges() = runBlocking {
        records.addCustomTask(dates.today(), "必须保留")
        val snapshot = backups.snapshot()
        try {
            backups.restore(snapshot.copy(tasks = snapshot.tasks + snapshot.tasks))
            fail("Duplicate IDs were accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(snapshot, backups.snapshot())
        try {
            backups.restore(snapshot.copy(records = emptyList()))
            fail("Orphan task was accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(snapshot, backups.snapshot())
    }

    @Test fun unknownVersionBrokenJsonAndInvalidCompletionAreRejected() = runBlocking {
        records.addCustomTask(dates.today(), "保持原数据")
        val snapshot = backups.snapshot()
        val json = BackupCodec.encode(snapshot)
        for (invalid in listOf(
            JSONObject(json).put("version", 999).toString(),
            json.dropLast(4),
            json + " trailing garbage",
            JSONObject(json).put("tasks", "not an array").toString(),
        )) {
            var rejected = false
            try { BackupCodec.decode(invalid) } catch (_: Exception) { rejected = true }
            assertTrue("Invalid file was accepted", rejected)
        }
        try {
            backups.restore(snapshot.copy(tasks = snapshot.tasks.map { it.copy(completed = true, completedTime = null) }))
            fail("Inconsistent completion accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(snapshot, backups.snapshot())
    }

    @Test fun databaseFailureAfterClearRollsBackOriginalRecords() = runBlocking {
        records.addCustomTask(dates.today(), "恢复失败后仍在")
        val original = backups.snapshot()
        // Force a database insert failure after the restore has issued its deletes.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER backup_test_failure BEFORE INSERT ON tasks BEGIN SELECT RAISE(ABORT, 'test failure'); END",
        )
        var rejected = false
        try { backups.restore(original) } catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertEquals(original, backups.snapshot())
    }

    @Test fun repeatedRestoreIsIdempotent() = runBlocking {
        records.addTemplate("读书")
        val original = backups.snapshot()
        val parsed = BackupCodec.decode(BackupCodec.encode(original))
        backups.restore(parsed)
        backups.restore(parsed)
        assertEquals(original, backups.snapshot())
    }

    @Test fun dailyRecordScopeAcceptsLegacyBackupAndRejectsOtherTools() = runBlocking {
        records.addTemplate("每日阅读")
        val original = backups.snapshot()
        val json = JSONObject(BackupCodec.encode(original))
        assertEquals("daily-record", json.getString("module"))
        json.remove("module")
        assertEquals(original, BackupCodec.decode(json.toString()))
        json.put("module", "work-log")
        var rejected = false
        try { BackupCodec.decode(json.toString()) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(original, backups.snapshot())
    }
}
