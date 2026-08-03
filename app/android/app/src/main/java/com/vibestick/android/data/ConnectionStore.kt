package com.vibestick.android.data

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

interface BridgeConnectionStore {
    fun selectedBridge(): BridgeCandidate?

    fun saveSelectedBridge(candidate: BridgeCandidate)

    fun clearSelectedBridge()

    fun token(): String

    fun saveToken(token: String)

    fun lastAlertEventId(): String

    fun saveLastAlertEventId(eventId: String)

    fun pendingEnterFingerprint(): String

    fun savePendingEnterFingerprint(fingerprint: String)
}

class ConnectionStore private constructor(
    private val preferences: SharedPreferences,
) : BridgeConnectionStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
    )

    override fun selectedBridge(): BridgeCandidate? {
        val host = preferences.getString(keyHost, "").orEmpty().trim()
        if (host.isEmpty()) return null
        val port = preferences.getInt(keyPort, BridgeProtocol.defaultHttpPort)
            .takeIf { it in 1..65_535 }
            ?: BridgeProtocol.defaultHttpPort
        return BridgeCandidate(
            host = host,
            port = port,
            name = preferences.getString(keyName, host).orEmpty().ifBlank { host },
            version = preferences.getString(keyVersion, "").orEmpty(),
        )
    }

    override fun saveSelectedBridge(candidate: BridgeCandidate) {
        preferences.edit()
            .putString(keyHost, candidate.host)
            .putInt(keyPort, candidate.port)
            .putString(keyName, candidate.name)
            .putString(keyVersion, candidate.version)
            .apply()
    }

    override fun clearSelectedBridge() {
        preferences.edit()
            .remove(keyHost)
            .remove(keyPort)
            .remove(keyName)
            .remove(keyVersion)
            .apply()
    }

    override fun token(): String {
        preferences.getString(keyToken, "").orEmpty().trim()
            .takeIf(String::isNotEmpty)
            ?.let { return it }

        return synchronized(preferences) {
            preferences.getString(keyDeviceToken, "").orEmpty().trim()
                .takeIf(String::isNotEmpty)
                ?: generateDeviceToken().also { generated ->
                    preferences.edit().putString(keyDeviceToken, generated).commit()
                }
        }
    }

    override fun saveToken(token: String) {
        preferences.edit().putString(keyToken, token.trim()).apply()
    }

    override fun lastAlertEventId(): String =
        preferences.getString(keyLastAlertEventId, "").orEmpty()

    override fun saveLastAlertEventId(eventId: String) {
        preferences.edit().putString(keyLastAlertEventId, eventId).apply()
    }

    override fun pendingEnterFingerprint(): String =
        preferences.getString(keyPendingEnterFingerprint, "").orEmpty()

    override fun savePendingEnterFingerprint(fingerprint: String) {
        preferences.edit().putString(keyPendingEnterFingerprint, fingerprint).apply()
    }

    private companion object {
        const val preferencesName = "vibestick_connection"
        const val keyHost = "bridge_host"
        const val keyPort = "bridge_port"
        const val keyName = "bridge_name"
        const val keyVersion = "bridge_version"
        const val keyToken = "bridge_token"
        const val keyDeviceToken = "device_token"
        const val keyLastAlertEventId = "last_alert_event_id"
        const val keyPendingEnterFingerprint = "pending_enter_fingerprint"

        fun generateDeviceToken(): String {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
