package com.character.app.domain

import java.io.IOException

/**
 * Categorized error types across backend operations.
 */
enum class FailureKind {
    CONNECTION,
    TIMEOUT,
    THROTTLED,
    UNAVAILABLE,
    AUTHENTICATION,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INVALID_REQUEST,
    PROTOCOL,
    UNSUPPORTED,
    CLOSED,
}

/**
 * Standard business-level backend failure model.
 * Safe metadata only: adapters must never attach raw response bodies, URLs or credentials.
 */
open class BackendFailure(
    val kind: FailureKind,
    val retryAfterMillis: Long? = null,
    val definitiveRejection: Boolean = false,
) : IOException("Backend operation failed: $kind") {

    val retryable: Boolean
        get() = kind in RETRYABLE_KINDS

    private companion object {
        private val RETRYABLE_KINDS = setOf(
            FailureKind.CONNECTION,
            FailureKind.TIMEOUT,
            FailureKind.THROTTLED,
            FailureKind.UNAVAILABLE,
        )
    }
}
