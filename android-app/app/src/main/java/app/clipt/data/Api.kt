package app.clipt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * One process-wide OkHttp client: HTTP/2 + connection pooling so a burst of calls reuses one
 * TLS connection, then the idle connection is dropped quickly so the radio can sleep.
 */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Exchanges a one-time pairing code for the server URL + sync token (no token needed yet). */
suspend fun redeemPairCode(serverUrl: String, code: String): PairRedeem = withContext(Dispatchers.IO) {
    val body = buildJsonObject { put("code", code) }.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url(serverUrl.trimEnd('/') + "/pair/redeem").post(body).build()
    Http.client.newCall(request).execute().use { res ->
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) {
            val msg = runCatching { Prefs.json.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull() ?: "HTTP ${res.code}"
            throw ApiException(res.code, msg)
        }
        Prefs.json.decodeFromString(PairRedeem.serializer(), text)
    }
}

class Api(private val prefs: Prefs, private val baseUrl: String = prefs.serverUrl, private val token: String = prefs.token) {

    private val jsonType = "application/json".toMediaType()

    private fun req(path: String) = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .header("Authorization", "Bearer $token")

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        Http.client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = when (res.code) {
                    401 -> "Wrong sync token"
                    else -> runCatching { Prefs.json.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()
                        ?: "HTTP ${res.code}"
                }
                throw ApiException(res.code, msg)
            }
            Prefs.json.decodeFromString(serializer, body)
        }
    }

    fun imageUrl(id: String) = "${baseUrl.trimEnd('/')}/v1/clips/$id/image"
    fun authHeader() = "Bearer $token"

    /** Creates a one-time code another device can type to join (valid 10 minutes). */
    suspend fun createPairCode(): PairCode = call(req("/v1/pair-codes").post(ByteArray(0).toRequestBody(null)).build(), PairCode.serializer())

    suspend fun config(): ServerConfig = call(req("/v1/config").get().build(), ServerConfig.serializer())

    suspend fun list(type: String? = null, pinned: Boolean = false, query: String? = null, limit: Int = 100): ClipList {
        val url = (baseUrl.trimEnd('/') + "/v1/clips").toHttpUrl().newBuilder().apply {
            type?.let { addQueryParameter("type", it) }
            if (pinned) addQueryParameter("pinned", "1")
            query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
            addQueryParameter("limit", limit.toString())
        }.build()
        return call(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build(), ClipList.serializer())
    }

    suspend fun latest(excludeSelf: Boolean = true): Clip? {
        val q = if (excludeSelf) "?exclude_device=${prefs.deviceId}" else ""
        return call(req("/v1/clips/latest$q").get().build(), ClipEnvelope.serializer()).clip
    }

    suspend fun get(id: String): Clip? = call(req("/v1/clips/$id").get().build(), ClipEnvelope.serializer()).clip

    suspend fun sendText(text: String): Clip {
        val body = buildJsonObject {
            put("content", text)
            put("device_id", prefs.deviceId)
            put("device_name", prefs.deviceName)
            put("device_platform", "android")
        }.toString().toRequestBody(jsonType)
        return call(req("/v1/clips").post(body).build(), ClipEnvelope.serializer()).clip!!
    }

    suspend fun sendImage(file: File, mime: String): Clip {
        val body: RequestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .addFormDataPart("device_id", prefs.deviceId)
            .addFormDataPart("device_name", prefs.deviceName)
            .addFormDataPart("device_platform", "android")
            .build()
        return call(req("/v1/clips/image").post(body).build(), ClipEnvelope.serializer()).clip!!
    }

    suspend fun setPinned(id: String, pinned: Boolean): Clip? {
        val body = buildJsonObject { put("is_pinned", pinned) }.toString().toRequestBody(jsonType)
        return call(req("/v1/clips/$id").patch(body).build(), ClipEnvelope.serializer()).clip
    }

    suspend fun delete(id: String) {
        call(req("/v1/clips/$id").delete().build(), ErrorBody.serializer())
    }

    /** [fcmToken]: null = leave unchanged, "" = remove push token, otherwise set it. */
    suspend fun registerDevice(fcmToken: String? = null) {
        val body = buildJsonObject {
            put("id", prefs.deviceId)
            put("name", prefs.deviceName)
            put("platform", "android")
            if (fcmToken != null) put("fcm_token", fcmToken)
        }.toString().toRequestBody(jsonType)
        call(req("/v1/devices").post(body).build(), ErrorBody.serializer())
    }

    /** Downloads an image clip's bytes into [dest]. */
    suspend fun downloadImage(id: String, dest: File): File = withContext(Dispatchers.IO) {
        Http.client.newCall(req("/v1/clips/$id/image").get().build()).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "Image HTTP ${res.code}")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
        }
        dest
    }
}
