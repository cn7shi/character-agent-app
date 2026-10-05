package com.character.app.network

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

// Not a data class: generated toString/copy must never accidentally expose the credential.
class BackendConnection(baseUrl: String, private val credential: String) {
    internal val origin: HttpUrl = baseUrl.trim().toHttpUrl().also {
        require(it.encodedPath == "/" && it.query == null && it.fragment == null && it.username.isEmpty() && it.password.isEmpty())
        require(it.isHttps || it.host in setOf("127.0.0.1", "10.0.2.2"))
        require(credential.isNotBlank() && credential.none { c -> c.isWhitespace() || c.code < 32 })
    }
    val baseUrl: String get() = origin.toString()
    // D19: cache namespace changes when either endpoint or credential changes, avoiding profile leakage.
    val scope: String = MessageDigest.getInstance("SHA-256").digest("$origin\n$credential".toByteArray())
        .joinToString("") { "%02x".format(it) }
    internal fun authorization() = "Bearer $credential"
    override fun toString() = "BackendConnection(redacted)"
}

class RemoteFailure(val status: Int? = null) : IOException("Backend request failed") {
    val retryable get() = status == null || status == 408 || status == 429 || status >= 500
}

interface Transport {
    suspend fun request(method: String, path: String, body: String? = null,
                        requestKey: String? = null, query: Map<String, String> = emptyMap()): String
}

class HttpTransport(private val connection: BackendConnection) : Transport {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun request(method: String, path: String, body: String?, requestKey: String?, query: Map<String, String>): String {
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        val url = connection.origin.newBuilder().encodedPath(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", connection.authorization())
            .header("Accept", "application/json").apply {
                requestKey?.let { header("Idempotency-Key", it) }
                method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(RemoteFailure())
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw RemoteFailure(it.code)
                            val source = it.body?.source() ?: throw RemoteFailure()
                            source.request(4L * 1024 * 1024 + 1)
                            if (source.buffer.size > 4L * 1024 * 1024) throw RemoteFailure()
                            val value = source.readUtf8()
                            if (!continuation.isCancelled) continuation.resume(value)
                        }
                    } catch (_: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(RemoteFailure(response.code.takeUnless { it in 200..299 }))
                    }
                }
            })
        }
    }
}
