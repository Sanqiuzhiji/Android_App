package com.example.myapplication

import com.example.myapplication.feature.reminders.*
import org.junit.Assert.*
import org.junit.Test

class ReminderRulesTest {
    private fun point(mode: ReminderMode) = ReminderPoint(title = "回宿舍整理资料", date = "2026-09-28", time = "12:00",
        early = 10, window = 60, mode = mode, placeId = "dorm", radius = 200, taskId = "task", templateId = null,
        sound = true, vibrate = true)

    @Test fun addressRequiresExitThenEntryAndRearmsForLaterVisits() {
        var p = point(ReminderMode.PLACE)
        val now = p.start()
        assertFalse(reminderDecision(p, now, true).notify)
        assertFalse(reminderDecision(p, now, null).armed)
        p = p.copy(entryArmed = reminderDecision(p, now, false).armed)
        assertTrue(reminderDecision(p, now, true).notify)
        p = p.copy(entryArmed = false, fired = true, lastNotifiedAt = now)
        assertFalse(reminderDecision(p, now + 60_000, true).notify)
        p = p.copy(entryArmed = reminderDecision(p, now + 60_000, false).armed)
        assertTrue(reminderDecision(p, now + 120_000, true).notify)
    }

    @Test fun timeRepeatsAtIntervalEvenAfterOldOneHourLimit() {
        val p = point(ReminderMode.TIME)
        assertFalse(reminderDecision(p, p.start() - 1, null).notify)
        assertTrue(reminderDecision(p, p.start(), null).notify)
        val reminded = p.copy(fired = true, lastNotifiedAt = p.start())
        assertFalse(reminderDecision(reminded, p.start() + 599_999, null).notify)
        assertTrue(reminderDecision(reminded, p.start() + 600_000, null).notify)
        assertTrue(reminderDecision(reminded, p.end() + 1, null).notify)
        assertEquals(p.start() + 600_000, nextReminderCheck(reminded, p.start()))
    }

    @Test fun combinedRequiresPlaceAndWindowForEveryRepeat() {
        val p = point(ReminderMode.BOTH)
        assertFalse(reminderDecision(p, p.start() - 1, true).notify)
        assertTrue(reminderDecision(p, p.start(), true).notify)
        assertFalse(reminderDecision(p, p.start(), false).notify)
        val reminded = p.copy(fired = true, lastNotifiedAt = p.start())
        assertFalse(reminderDecision(reminded, p.start() + 599_999, true).notify)
        assertTrue(reminderDecision(reminded, p.start() + 600_000, true).notify)
        assertFalse(reminderDecision(reminded, p.start() + 600_000, null).notify)
        assertFalse(reminderDecision(reminded, p.end() + 1, true).notify)
        assertNull(nextReminderCheck(reminded, p.end() + 1))
    }

    @Test fun pauseAndCompletionPreventEveryModeFromNotifying() {
        ReminderMode.entries.forEach { mode ->
            val p = point(mode).copy(entryArmed = true)
            assertFalse(reminderDecision(p.copy(enabled = false), p.start(), true).notify)
            assertFalse(reminderDecision(p.copy(stopReason = "completed"), p.start(), true).notify)
            assertNull(nextReminderCheck(p.copy(enabled = false), p.start()))
        }
    }

    @Test fun retryNeverSchedulesPastAlarmsOrBeyondCombinedExpiry() {
        val p = point(ReminderMode.BOTH)
        assertEquals(p.end() + 1, nextReminderCheck(p, p.end() - 1))
        assertTrue(nextReminderCheck(point(ReminderMode.TIME), p.start())!! > p.start())
    }

    @Test fun earlyMinutesDoNotMoveWindowEnd() {
        val p = point(ReminderMode.BOTH)
        assertEquals(p.copy(early = 0).end(), p.end())
        assertEquals(70 * 60_000L, p.end() - p.start())
    }
}
