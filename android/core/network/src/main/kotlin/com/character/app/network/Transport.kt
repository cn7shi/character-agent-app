package com.character.app.network

import com.character.app.domain.BackendFailure
import com.character.app.domain.FailureKind
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// Not a data class: generated toString/copy must never accidentally expose the credential.
class BackendConnection(baseUrl: String, private val credential: String) {
    internal val origin: HttpUrl = baseUrl.trim().toHttpUrl().also {
        require(it.encodedPath == "/" && it.query == null && it.fragment == null && it.username.isEmpty() && it.password.isEmpty())
        require(it.isHttps || it.host in setOf("127.0.0.1", "10.0.2.2"))
        require(credential.isNotBlank() && credential.none { c -> c.isWhitespace() || c.code < 32 })
    }
    val baseUrl: String get() = origin.toString()
    // D19: cache identity is independent of realtime/polling preference.
    val scope: String = MessageDigest.getInstance("SHA-256").digest("$origin\n$credential".toByteArray())
        .joinToString("") { "%02x".format(it) }
    internal fun authorization() = "Bearer $credential"
    override fun toString() = "BackendConnection(redacted)"
}

class RemoteFailure(val status: Int? = null, retryAfterMillis: Long? = null) : BackendFailure(
    when (status) {
        null -> FailureKind.CONNECTION
        401 -> FailureKind.AUTHENTICATION
        403 -> FailureKind.FORBIDDEN
        404 -> FailureKind.NOT_FOUND
        409 -> FailureKind.CONFLICT
        400, 422 -> FailureKind.INVALID_REQUEST
        408 -> FailureKind.TIMEOUT
        429 -> FailureKind.THROTTLED
        405, 501 -> FailureKind.UNSUPPORTED
        in 500..599 -> FailureKind.UNAVAILABLE
        else -> FailureKind.PROTOCOL
    }, retryAfterMillis,
    // API-v1 rejects these before acceptance. A malformed 2xx does NOT prove rejection.
    definitiveRejection = status in setOf(400, 401, 403, 404, 409, 422)
)

/** HTTP mechanics only. Business pluggability lives in domain.BackendAdapter. */
interface HttpRequests {
    suspend fun request(method: String, path: String, body: String? = null,
                        requestKey: String? = null, query: Map<String, String> = emptyMap()): String
}
interface HttpEvents { fun events(path: String, afterSequence: Long): Flow<SseFrame> }

class HttpTransport(private val connection: BackendConnection) : HttpRequests, HttpEvents, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    // SSE lives longer than a request; idle timeout exceeds the server's 15-second heartbeat.
    private val eventClient = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()

    private fun requestBuilder(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        if (closed.get()) throw BackendFailure(FailureKind.CLOSED)
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains('?') && !path.contains('#'))
        val url = connection.origin.newBuilder().encodedPath(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return Request.Builder().url(url).header("Authorization", connection.authorization()).header("Cache-Control", "no-store")
    }
    override suspend fun request(method: String, path: String, body: String?, requestKey: String?, query: Map<String, String>): String {
        val request = requestBuilder(path, query).header("Accept", "application/json").apply {
            requestKey?.let { header("Idempotency-Key", it) }
            method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(safeFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw httpFailure(it)
                            val source = it.body?.source() ?: throw BackendFailure(FailureKind.PROTOCOL)
                            source.request(4L * 1024 * 1024 + 1)
                            if (source.buffer.size > 4L * 1024 * 1024) throw BackendFailure(FailureKind.PROTOCOL)
                            val value = source.readUtf8()
                            if (!continuation.isCancelled) continuation.resume(value)
                        }
                    } catch (error: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(safeFailure(error))
                    }
                }
            })
        }
    }

    override fun events(path: String, afterSequence: Long): Flow<SseFrame> = callbackFlow {
        require(afterSequence in 0..999_999_999_999_999_999)
        val request = requestBuilder(path).header("Accept", "text/event-stream")
            .header("Last-Event-ID", afterSequence.toString()).build()
        val call = eventClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(safeFailure(e)) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw httpFailure(it)
                        if (it.code != 200 || it.body?.contentType()?.let { type -> "${type.type}/${type.subtype}" } != "text/event-stream")
                            throw BackendFailure(FailureKind.PROTOCOL)
                        SseReader.read(checkNotNull(it.body).source()) { frame ->
                            // Bounded backpressure, no unbounded callback queue or silent event loss.
                            trySendBlocking(frame).getOrThrow()
                        }
                    }
                    close()
                } catch (error: Exception) {
                    close(if (error is IOException) safeFailure(error) else BackendFailure(FailureKind.PROTOCOL))
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(16)

    private fun safeFailure(error: IOException): BackendFailure = when {
        closed.get() -> BackendFailure(FailureKind.CLOSED)
        error is BackendFailure -> error
        error is SocketTimeoutException || error is java.io.InterruptedIOException -> BackendFailure(FailureKind.TIMEOUT)
        else -> BackendFailure(FailureKind.CONNECTION)
    }
    private fun httpFailure(response: Response) = RemoteFailure(response.code, retryAfter(response.header("Retry-After")))
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}

internal fun retryAfter(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim() ?: return null
    if (text.matches(Regex("[0-9]+"))) return text.toLongOrNull()?.let { if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 } ?: Long.MAX_VALUE
    return try { (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0) }
    catch (_: Exception) { null }
}
