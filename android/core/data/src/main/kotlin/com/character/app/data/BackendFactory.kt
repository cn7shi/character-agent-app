package com.character.app.data

import com.character.app.domain.BackendAdapter
import com.character.app.domain.ReliableBackend
import com.character.app.network.BackendConnection
import com.character.app.network.HttpTransport

enum class ConnectionMode {
    REALTIME,
    POLLING,
}

/**
 * Composition root: a future protocol registers an adapter here without changing the repository/UI.
 */
object BackendFactory {
    fun connect(connection: BackendConnection, mode: ConnectionMode): BackendAdapter {
        val transport = HttpTransport(connection)
        val eventTransport = if (mode == ConnectionMode.REALTIME) transport else null
        return ReliableBackend(
            delegate = HttpBackend(
                transport = transport,
                eventTransport = eventTransport,
                release = transport::close,
            )
        )
    }
}
