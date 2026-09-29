package com.example.myapplication.feature.reminders

/** Unknown/uncertain positions never count as an exit. PLACE requires a confirmed exit before entry. */
data class ReminderDecision(val armed: Boolean, val notify: Boolean)

fun reminderDecision(point: ReminderPoint, now: Long, inside: Boolean?): ReminderDecision {
    if (!point.enabled || point.stopReason.isNotEmpty()) return ReminderDecision(point.entryArmed, false)
    if (point.mode == ReminderMode.PLACE) {
        if (inside == false) return ReminderDecision(true, false)
        return ReminderDecision(point.entryArmed, inside == true && point.entryArmed)
    }
    val due = now >= point.start() && (point.lastNotifiedAt == 0L || now >= point.lastNotifiedAt + point.repeatMinutes * 60_000L)
    return ReminderDecision(point.entryArmed, due && (point.mode == ReminderMode.TIME || (now <= point.end() && inside == true)))
}

fun nextReminderCheck(point: ReminderPoint, now: Long): Long? {
    if (!point.enabled || point.stopReason.isNotEmpty() || point.mode == ReminderMode.PLACE) return null
    if (point.mode == ReminderMode.BOTH && now > point.end()) return null
    val due = maxOf(point.start(), if (point.lastNotifiedAt == 0L) point.start() else point.lastNotifiedAt + point.repeatMinutes * 60_000L)
    // If location/notification permissions are unavailable, retry without a tight alarm loop.
    val next = if (due > now) due else now + point.repeatMinutes * 60_000L
    return if (point.mode == ReminderMode.BOTH) minOf(next, point.end() + 1) else next
}
