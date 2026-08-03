package com.vibestick.android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vibestick.android.VibeStickApplication
import com.vibestick.android.data.BridgeCallResult
import com.vibestick.android.data.BridgeFailure
import com.vibestick.android.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val connectedPollMillis = 2_000L

internal fun retryDelayMillis(consecutiveNetworkFailures: Int): Long =
    when (consecutiveNetworkFailures) {
        1 -> 5_000L
        2 -> 10_000L
        else -> 30_000L
    }

internal fun shouldRediscover(consecutiveNetworkFailures: Int): Boolean =
    consecutiveNetworkFailures >= 3

class BridgeMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private lateinit var app: VibeStickApplication

    override fun onCreate() {
        super.onCreate()
        app = application as VibeStickApplication
        app.notificationFactory.createChannels()
        showForegroundNotification(null)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch { monitorBridge() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorBridge() {
        val store = app.connectionStore
        val alertPolicy = BridgeMonitorAlertPolicy(store.lastAlertEventId())
        var networkFailures = 0

        while (currentCoroutineContext().isActive) {
            when (val result = app.repository.refreshState()) {
                is BridgeCallResult.Success -> {
                    networkFailures = 0
                    val state = result.value
                    showForegroundNotification(state.computerName)
                    val alert = state.alert
                    if (alertPolicy.shouldNotify(alert)) {
                        if (app.notificationFactory.showTaskAlert(alert)) {
                            alertPolicy.markDelivered(alert)
                            store.saveLastAlertEventId(alert.eventId)
                        } else {
                            alertPolicy.markDeliveryFailed(alert)
                        }
                    }
                    delay(connectedPollMillis)
                }
                is BridgeCallResult.Failure -> {
                    val delayMillis = if (result.failure is BridgeFailure.Network) {
                        networkFailures += 1
                        if (shouldRediscover(networkFailures)) {
                            app.repository.discover()
                            if (app.repository.connection.value is ConnectionState.Connected) {
                                networkFailures = 0
                                showForegroundNotification(
                                    app.repository.bridgeState.value?.computerName,
                                )
                                delay(connectedPollMillis)
                                continue
                            }
                        }
                        retryDelayMillis(networkFailures)
                    } else {
                        networkFailures = 0
                        5_000L
                    }
                    showForegroundNotification(null)
                    delay(delayMillis)
                }
            }
        }
    }

    private fun showForegroundNotification(computerName: String?) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            monitorNotificationId,
            app.notificationFactory.foregroundNotification(computerName),
            serviceType,
        )
    }

    companion object {
        private const val actionStart = "com.vibestick.android.action.START_MONITOR"

        fun start(context: Context) {
            val intent = Intent(context, BridgeMonitorService::class.java).setAction(actionStart)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeMonitorService::class.java))
        }
    }
}
