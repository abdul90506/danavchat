package com.danav.chat.data

import android.content.Context
import android.content.SharedPreferences
import com.danav.chat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : Exception(message)

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("danav", Context.MODE_PRIVATE)
    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()
    var meJson: String?
        get() = sp.getString("me", null)
        set(v) = sp.edit().putString("me", v).apply()
    var lastSync: Long
        get() = sp.getLong("lastSync", 0)
        set(v) = sp.edit().putLong("lastSync", v).apply()
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()
    var fcmSent: String?
        get() = sp.getString("fcmSent", null)
        set(v) = sp.edit().putString("fcmSent", v).apply()
    fun clear() = sp.edit().clear().apply()
}

object Api {
    val base: String = BuildConfig.API_BASE.trimEnd('/')
    lateinit var prefs: Prefs

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private suspend fun exec(req: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!r.isSuccessful) throw ApiException(r.code, j.optString("error", "Request failed (${r.code})"))
            j
        }
    }

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url(base + path)
        prefs.token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    suspend fun get(path: String) = exec(builder(path).get().build())
    suspend fun post(path: String, body: JSONObject = JSONObject()) =
        exec(builder(path).post(body.toString().toRequestBody(JSON_TYPE)).build())
    suspend fun put(path: String, body: JSONObject) =
        exec(builder(path).put(body.toString().toRequestBody(JSON_TYPE)).build())
    suspend fun delete(path: String) = exec(builder(path).delete().build())

    /** Blocking variant for BroadcastReceivers / FCM service threads */
    fun postBlocking(path: String, body: JSONObject): Boolean = runCatching {
        http.newCall(builder(path).post(body.toString().toRequestBody(JSON_TYPE)).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun wsUrl(): String = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?token=" + (prefs.token ?: "")

    /** Upload a file directly to Cloudinary with a server-signed request. */
    suspend fun uploadToCloudinary(file: File, resourceType: String, mime: String, onProgress: (Float) -> Unit): JSONObject {
        val sig = post("/media/sign")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("api_key", sig.getString("apiKey"))
            .addFormDataPart("timestamp", sig.getLong("timestamp").toString())
            .addFormDataPart("folder", sig.getString("folder"))
            .addFormDataPart("signature", sig.getString("signature"))
            .addFormDataPart("file", file.name, ProgressBody(file, mime.toMediaType(), onProgress))
            .build()
        val req = Request.Builder()
            .url("https://api.cloudinary.com/v1_1/${sig.getString("cloudName")}/$resourceType/upload")
            .post(body).build()
        return withContext(Dispatchers.IO) {
            http.newCall(req).await().use { r ->
                val t = r.body?.string().orEmpty()
                val j = runCatching { JSONObject(t) }.getOrElse { JSONObject() }
                if (!r.isSuccessful) throw ApiException(r.code, j.optJSONObject("error")?.optString("message") ?: "Upload failed")
                j
            }
        }
    }

    private class ProgressBody(val file: File, val type: MediaType, val onProgress: (Float) -> Unit) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1)
            var sent = 0L
            file.source().use { src ->
                val buf = okio.Buffer()
                while (true) {
                    val n = src.read(buf, 64 * 1024)
                    if (n == -1L) break
                    sink.write(buf, n)
                    sent += n
                    onProgress(sent.toFloat() / total)
                }
            }
        }
    }
}
