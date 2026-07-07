package com.nexent.app.data.network

import com.nexent.app.data.model.ChatRequest
import com.nexent.app.data.model.ChatStreamChunk
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

object RetrofitClient {

    @Volatile
    private var instance: NexentApiService? = null

    @Volatile
    private var currentBaseUrl: String = ""

    @Volatile
    private var currentApiKey: String = ""

    private val gson = Gson()

    fun getInstance(baseUrl: String, apiKey: String = ""): NexentApiService {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val key = apiKey.ifBlank { "nexent-6f1913254fb6a73d55d3254e" }
        if (instance == null || normalizedUrl != currentBaseUrl || key != currentApiKey) {
            synchronized(this) {
                if (instance == null || normalizedUrl != currentBaseUrl || key != currentApiKey) {
                    currentBaseUrl = normalizedUrl
                    currentApiKey = key
                    instance = buildService(normalizedUrl, key)
                }
            }
        }
        return instance!!
    }

    private fun buildService(baseUrl: String, apiKey: String): NexentApiService {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val original = chain.request()
                val request = original.newBuilder()
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NexentApiService::class.java)
    }

    /**
     * Execute a streaming chat request and emit chunks via a Flow.
     */
    fun streamChat(
        baseUrl: String,
        request: ChatRequest,
        apiKey: String = ""
    ): Flow<ChatStreamChunk> = callbackFlow {
        val key = apiKey.ifBlank { "nexent-6f1913254fb6a73d55d3254e" }
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val jsonBody = gson.toJson(request)
        val reqBody = jsonBody.toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url("${normalizedUrl}nb/v1/chat/run")
            .post(reqBody)

            .addHeader("Authorization", "Bearer $key")
            .addHeader("Idempotency-Key", "idem-${UUID.randomUUID()}")
            .build()

        try {
            val response = client.newCall(httpRequest).execute()
            val source = response.body?.source() ?: run {
                close()
                return@callbackFlow
            }

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        trySend(ChatStreamChunk(done = true))
                        break
                    }
                    try {
                        val chunk = gson.fromJson(data, ChatStreamChunk::class.java)
                        trySend(chunk)
                    } catch (_: Exception) {
                        // Skip malformed JSON lines
                    }
                }
            }
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }

        close()
    }

    /**
     * Upload attachment bytes to the server. Returns s3_url on success or null on failure.
     */
    fun uploadAttachment(
        baseUrl: String,
        filename: String,
        contentType: String,
        bytes: ByteArray,
        apiKey: String = ""
    ): String? {
        val key = apiKey.ifBlank { "nexent-6f1913254fb6a73d55d3254e" }
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val mediaType = try { contentType.toMediaType() } catch (_: Exception) { "application/octet-stream".toMediaType() }
        val fileBody = bytes.toRequestBody(mediaType)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", filename, fileBody)
            .build()

        val httpRequest = Request.Builder()
            .url("${normalizedUrl}nb/v1/chat/attachments/upload")
            .post(multipart)
            .addHeader("Authorization", "Bearer $key")
            .build()

        try {
            val response = client.newCall(httpRequest).execute()
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string() ?: return null
                return try {
                val map = gson.fromJson(bodyStr, Map::class.java)
                val files = map["files"] as? List<*>
                val firstFile = files?.firstOrNull() as? Map<*, *>
                // Prefer presigned_url or s3_url for direct access (if provided), otherwise fall back to url
                val presigned = firstFile?.get("presigned_url")?.toString()?.takeIf { it.isNotBlank() }
                val s3url = firstFile?.get("s3_url")?.toString()?.takeIf { it.isNotBlank() }
                val url = firstFile?.get("url")?.toString()?.takeIf { it.isNotBlank() }
                presigned ?: s3url ?: url
            } catch (_: Exception) {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }
}
