package com.character.app.network

import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Validated backend connection descriptor.
 *
 * Intentionally not a data class: generated toString/copy must never accidentally expose the credential.
 */
class BackendConnection(
    baseUrl: String,
    private val credential: String,
) {
    internal val origin: HttpUrl = baseUrl.trim().toHttpUrl().also { url ->
        require(
            url.encodedPath == "/" &&
                url.query == null &&
                url.fragment == null &&
                url.username.isEmpty() &&
                url.password.isEmpty()
        ) { "Base URL must be an origin root without path, query or auth" }

        require(url.isHttps || url.host in LOCAL_DEV_HOSTS) {
            "Only HTTPS or local loopback addresses (127.0.0.1, 10.0.2.2) are allowed"
        }

        require(credential.isNotBlank() && credential.none { c -> c.isWhitespace() || c.code < 32 }) {
            "Credential must not contain whitespace or control characters"
        }
    }

    val baseUrl: String
        get() = origin.toString()

    /**
     * Cache and database identity scope, independent of realtime/polling preference.
     */
    val scope: String = MessageDigest.getInstance("SHA-256")
        .digest("$origin\n$credential".toByteArray())
        .joinToString("") { "%02x".format(it) }

    internal fun authorization(): String = "Bearer $credential"

    override fun toString(): String = "BackendConnection(redacted)"

    private companion object {
        private val LOCAL_DEV_HOSTS = setOf("127.0.0.1", "10.0.2.2")
    }
}
