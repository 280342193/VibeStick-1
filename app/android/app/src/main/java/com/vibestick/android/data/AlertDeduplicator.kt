package com.vibestick.android.data

class AlertDeduplicator(lastDeliveredEventId: String) {
    var deliveredEventId: String = lastDeliveredEventId
        private set

    private var pendingEventId: String = ""

    fun shouldNotify(alert: Alert): Boolean {
        if (!alert.isTerminal) return false
        if (alert.eventId == deliveredEventId || alert.eventId == pendingEventId) return false
        pendingEventId = alert.eventId
        return true
    }

    fun markDelivered(alert: Alert) {
        if (alert.eventId.isBlank()) return
        deliveredEventId = alert.eventId
        if (pendingEventId == alert.eventId) pendingEventId = ""
    }

    fun markDeliveryFailed(alert: Alert) {
        if (pendingEventId == alert.eventId) pendingEventId = ""
    }
}
