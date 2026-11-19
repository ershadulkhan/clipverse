package app.clipt.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Clip(
    val id: String,
    val type: String,
    val content: String? = null,
    val mime: String? = null,
    val size: Long = 0,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_platform") val devicePlatform: String,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
) {
    val isImage get() = type == "image"
}

@Serializable
data class ClipList(
    val clips: List<Clip>,
    @SerialName("server_time") val serverTime: Long = 0,
)

@Serializable
data class ClipEnvelope(val clip: Clip? = null)

@Serializable
data class FcmConfig(
    @SerialName("project_id") val projectId: String,
    @SerialName("app_id") val appId: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("sender_id") val senderId: String,
)

@Serializable
data class ServerConfig(
    val ok: Boolean = false,
    @SerialName("retention_days") val retentionDays: Int = 30,
    @SerialName("max_upload_mb") val maxUploadMb: Int = 25,
    val fcm: FcmConfig? = null,
)

@Serializable
data class PairCode(val code: String, @SerialName("expires_at") val expiresAt: Long)

@Serializable
data class PairRedeem(@SerialName("server_url") val serverUrl: String, val token: String)

@Serializable
data class ErrorBody(val error: String? = null)
