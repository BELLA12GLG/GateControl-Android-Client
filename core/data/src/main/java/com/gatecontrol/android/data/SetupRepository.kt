package com.gatecontrol.android.data

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupRepository @Inject constructor(private val storage: EncryptedStorage) {

    companion object {
        private const val KEY_SERVER_URL  = "server_url"
        private const val KEY_API_TOKEN   = "api_token"
        private const val KEY_PEER_ID     = "peer_id"
        private const val KEY_CONFIG_HASH = "config_hash"
        private const val KEY_WG_CONFIG   = "wg_config"
        private const val KEY_WG_ONLY     = "wg_only_mode"   // 纯 WireGuard 模式标记
    }

    fun save(serverUrl: String, apiToken: String, peerId: Int) {
        storage.commitBatch(
            KEY_SERVER_URL to serverUrl,
            KEY_API_TOKEN  to apiToken,
            KEY_PEER_ID    to peerId,
        )
    }

    fun getServerUrl(): String = storage.getString(KEY_SERVER_URL, "")

    fun getApiToken(): String = storage.getString(KEY_API_TOKEN, "")

    fun getPeerId(): Int = storage.getInt(KEY_PEER_ID, -1)

    fun saveConfigHash(hash: String) { storage.putString(KEY_CONFIG_HASH, hash) }

    fun getConfigHash(): String = storage.getString(KEY_CONFIG_HASH, "")

    fun saveWireGuardConfig(config: String) {
        storage.putString(KEY_WG_CONFIG, config)
    }

    fun getWireGuardConfig(): String = storage.getString(KEY_WG_CONFIG, "")

    fun hasWireGuardConfig(): Boolean = getWireGuardConfig().isNotEmpty()

    /** 是否以服务器模式配置（有 serverUrl + apiToken）*/
    fun isConfigured(): Boolean = getServerUrl().isNotEmpty() && getApiToken().isNotEmpty()

    /** 是否以纯 WireGuard 模式配置（只有 wg_config，没有服务器凭证）*/
    fun isWireGuardOnlyMode(): Boolean = hasWireGuardConfig() && !isConfigured()

    fun isRegistered(): Boolean = getPeerId() > 0

    /** 任意一种模式完成配置即视为已设置 */
    fun isAnyModeConfigured(): Boolean = isConfigured() || hasWireGuardConfig()

    fun clear() { storage.clear() }
}
