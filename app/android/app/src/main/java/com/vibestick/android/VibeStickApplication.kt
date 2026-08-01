package com.vibestick.android

import android.app.Application
import com.vibestick.android.data.BridgeDiscovery
import com.vibestick.android.data.BridgeRepository
import com.vibestick.android.data.ConnectionStore
import com.vibestick.android.service.NotificationFactory

class VibeStickApplication : Application() {
    lateinit var connectionStore: ConnectionStore
        private set

    lateinit var repository: BridgeRepository
        private set

    lateinit var notificationFactory: NotificationFactory
        private set

    override fun onCreate() {
        super.onCreate()
        connectionStore = ConnectionStore(this)
        repository = BridgeRepository(
            scanner = BridgeDiscovery(),
            store = connectionStore,
        )
        notificationFactory = NotificationFactory(this)
        notificationFactory.createChannels()
    }
}
