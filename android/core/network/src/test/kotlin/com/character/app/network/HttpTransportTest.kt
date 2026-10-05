package com.character.app.network

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HttpTransportTest {
    @Test fun sendsStableRequestKeyAndNeverFollowsRedirects() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val credential = UUID.randomUUID().toString()
            val connection = BackendConnection(server.url("/").toString().replace("localhost", "127.0.0.1"), credential)
            val transport = HttpTransport(connection)
            server.enqueue(MockResponse().setBody("{}"))
            val key = UUID.randomUUID().toString()
            assertEquals("{}", transport.request("POST", "/v1/conversations", "{}", key))
            val request = server.takeRequest()
            assertTrue(request.getHeader("Authorization") == "Bearer $credential")
            assertEquals(key, request.getHeader("Idempotency-Key"))
            assertFalse(connection.toString().contains(credential))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
            try { transport.request("GET", "/v1/notes"); fail("Redirect should be rejected") }
            catch (error: RemoteFailure) { assertEquals(307, error.status) }
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun cancellationReleasesWaitingCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Longer than the cancellation deadline, shorter than MockWebServer's shutdown grace period.
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(2, TimeUnit.SECONDS))
            val transport = HttpTransport(BackendConnection(server.url("/").toString().replace("localhost", "127.0.0.1"), UUID.randomUUID().toString()))
            val task = launch(Dispatchers.IO) { transport.request("GET", "/v1/notes") }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(1500) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
        }
    }
    @Test fun connectionScopeIsolatesCredentialsAndRejectsUnsafeAddress() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        assertNotEquals(BackendConnection("https://example.com/", first).scope, BackendConnection("https://example.com/", second).scope)
        for (url in listOf("http://example.com/", "https://example.com/?x=1", "https://user:pass@example.com/", "https://example.com/path")) {
            assertThrows(IllegalArgumentException::class.java) { BackendConnection(url, first) }
        }
    }
}
