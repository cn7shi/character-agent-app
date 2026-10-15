package com.character.app.network

import com.character.app.domain.BackendFailure
import com.character.app.domain.FailureKind
import java.io.IOException
import java.net.SocketTimeoutException
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Maps HTTP response status codes to domain [BackendFailure] classifications.
 */
class RemoteFailure(
    val status: Int? = null,
    retryAfterMillis: Long? = null,
) : BackendFailure(
    kind = mapStatusToKind(status),
    retryAfterMillis = retryAfterMillis,
    definitiveRejection = status in DEFINITIVE_REJECTION_STATUSES,
) {
    private companion object {
        private val DEFINITIVE_REJECTION_STATUSES = setOf(400, 401, 403, 404, 409, 422)

        private fun mapStatusToKind(status: Int?): FailureKind = when (status) {
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
        }
    }
}

/**
 * Raw HTTP operations mechanics contract.
 */
interface HttpRequests {
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        requestKey: String? = null,
        query: Map<String, String> = emptyMap(),
    ): String
}

/**
 * Raw SSE events subscription mechanics contract.
 */
interface HttpEvents {
    fun events(path: String, afterSequence: Long): Flow<SseFrame>
}

/**
 * OkHttp-based implementation of [HttpRequests] and [HttpEvents].
 */
class HttpTransport(
    private val connection: BackendConnection,
) : HttpRequests, HttpEvents, AutoCloseable {

    private val closed = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    // SSE lives longer than a standard request; idle timeout exceeds the server's 15-second heartbeat.
    private val eventClient = client.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private fun requestBuilder(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): Request.Builder {
        if (closed.get()) {
            throw BackendFailure(FailureKind.CLOSED)
        }
        require(path.startsWith("/v1/") && !path.contains("..") && !path.contains('?') && !path.contains('#')) {
            "Invalid request path: $path"
        }

        val url = connection.origin.newBuilder()
            .encodedPath(path)
            .apply {
                query.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()

        return Request.Builder()
            .url(url)
            .header("Authorization", connection.authorization())
            .header("Cache-Control", "no-store")
    }

    override suspend fun request(
        method: String,
        path: String,
        body: String?,
        requestKey: String?,
        query: Map<String, String>,
    ): String {
        val request = requestBuilder(path, query)
            .header("Accept", "application/json")
            .apply {
                if (requestKey != null) {
                    header("Idempotency-Key", requestKey)
                }
                val requestBody = body?.toRequestBody("application/json; charset=utf-8".toMediaType())
                method(method, requestBody)
            }
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(safeFailure(e))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                throw httpFailure(res)
                            }
                            val source = res.body?.source() ?: throw BackendFailure(FailureKind.PROTOCOL)
                            source.request(MAX_RESPONSE_BYTES + 1)
                            if (source.buffer.size > MAX_RESPONSE_BYTES) {
                                throw BackendFailure(FailureKind.PROTOCOL)
                            }
                            val value = source.readUtf8()
                            if (!continuation.isCancelled) {
                                continuation.resume(value)
                            }
                        }
                    } catch (error: IOException) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(safeFailure(error))
                        }
                    }
                }
            })
        }
    }

    override fun events(path: String, afterSequence: Long): Flow<SseFrame> = callbackFlow {
        require(afterSequence in 0..999_999_999_999_999_999) {
            "afterSequence out of bounds: $afterSequence"
        }

        val request = requestBuilder(path)
            .header("Accept", "text/event-stream")
            .header("Last-Event-ID", afterSequence.toString())
            .build()

        val call = eventClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(safeFailure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            throw httpFailure(res)
                        }
                        val contentType = res.body?.contentType()?.let { type -> "${type.type}/${type.subtype}" }
                        if (res.code != 200 || contentType != "text/event-stream") {
                            throw BackendFailure(FailureKind.PROTOCOL)
                        }
                        SseReader.read(checkNotNull(res.body).source()) { frame ->
                            // Bounded backpressure, no unbounded callback queue or silent event loss.
                            trySendBlocking(frame).getOrThrow()
                        }
                    }
                    close()
                } catch (error: Exception) {
                    val finalError = if (error is IOException) safeFailure(error) else BackendFailure(FailureKind.PROTOCOL)
                    close(finalError)
                }
            }
        })

        awaitClose {
            call.cancel()
        }
    }.buffer(16)

    private fun safeFailure(error: IOException): BackendFailure = when {
        closed.get() -> BackendFailure(FailureKind.CLOSED)
        error is BackendFailure -> error
        error is SocketTimeoutException || error is java.io.InterruptedIOException -> BackendFailure(FailureKind.TIMEOUT)
        else -> BackendFailure(FailureKind.CONNECTION)
    }

    private fun httpFailure(response: Response): RemoteFailure {
        return RemoteFailure(
            status = response.code,
            retryAfterMillis = retryAfter(response.header("Retry-After")),
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private companion object {
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
    }
}

/**
 * Parses standard HTTP Retry-After headers (seconds or RFC 1123 date).
 */
internal fun retryAfter(
    value: String?,
    nowMillis: Long = System.currentTimeMillis(),
): Long? {
    val text = value?.trim() ?: return null

    // Numeric seconds format
    if (text.matches(NUMERIC_REGEX)) {
        val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1000) {
            Long.MAX_VALUE
        } else {
            seconds * 1000
        }
    }

    // RFC 1123 Date format
    return try {
        val dateMillis = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        (dateMillis - nowMillis).coerceAtLeast(0)
    } catch (_: Exception) {
        null
    }
}

private val NUMERIC_REGEX = Regex("[0-9]+")
