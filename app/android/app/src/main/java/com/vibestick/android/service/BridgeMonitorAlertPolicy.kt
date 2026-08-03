package com.vibestick.android.service

import com.vibestick.android.data.Alert
import com.vibestick.android.data.AlertDeduplicator

internal class BridgeMonitorAlertPolicy(lastDeliveredEventId: String) {
    private val deduplicator = AlertDeduplicator(lastDeliveredEventId)

    fun shouldNotify(alert: Alert): Boolean = deduplicator.shouldNotify(alert)

    fun markDelivered(alert: Alert) = deduplicator.markDelivered(alert)

    fun markDeliveryFailed(alert: Alert) = deduplicator.markDeliveryFailed(alert)
}
