package com.character.app.network

import com.character.app.domain.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class EventTransportTest {
    private fun connection(server: MockWebServer) = BackendConnection(server.url("/").toString().replace("localhost", "127.0.0.1"), UUID.randomUUID().toString())

    @Test fun parsesBomCommentsCrLfCrAndMultilineButDropsTruncatedEvent() {
        val frames = mutableListOf<SseFrame>()
        SseReader.read(Buffer().writeUtf8("\uFEFF: heartbeat\r\nid: 2\revent: message.updated\rdata: 第一行\rdata: 第二行\r\rid: 3\ndata: unfinished"), frames::add)
        assertEquals(listOf(SseFrame("2", "message.updated", "第一行\n第二行")), frames)
    }
    @Test fun oversizedUnterminatedFrameIsRejectedBeforeUnboundedAllocation() {
        val source = Buffer().writeUtf8("data: " + "x".repeat(512 * 1024))
        val error = assertThrows(BackendFailure::class.java) { SseReader.read(source) { fail() } }
        assertEquals(FailureKind.PROTOCOL, error.kind)
        assertFalse(error.retryable)
    }
    @Test fun reconnectSendsLastEventIdAndDoesNotFollowStreamRedirect() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HttpTransport(connection(server)).use { transport ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(": heartbeat\n\nid: 7\nevent: run.completed\ndata: {}\n\n"))
                assertEquals("7", transport.events("/v1/runs/run/events", 6).single().id)
                val request = server.takeRequest()
                assertEquals("6", request.getHeader("Last-Event-ID"))
                assertEquals("text/event-stream", request.getHeader("Accept"))
                assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer "))
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
                try { transport.events("/v1/runs/run/events", 7).toList(); fail() }
                catch (error: BackendFailure) { assertEquals(FailureKind.PROTOCOL, error.kind) }
                assertEquals(2, server.requestCount)
            }
        }
    }
    @Test fun cancellingAnIdleStreamAndClosingSessionReleaseCalls() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = HttpTransport(connection(server))
            val job = launch(Dispatchers.IO) { transport.events("/v1/runs/run/events", 0).collect() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            withTimeout(1500) { job.cancelAndJoin() }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val waiting = async(Dispatchers.IO) {
                try { transport.events("/v1/runs/run/events", 0).collect(); null }
                catch (error: BackendFailure) { error.kind }
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            transport.close()
            assertEquals(FailureKind.CLOSED, withTimeout(1500) { waiting.await() })
            try { transport.request("GET", "/v1/notes"); fail() }
            catch (error: BackendFailure) { assertEquals(FailureKind.CLOSED, error.kind) }
        }
    }
    @Test fun httpBackpressureHeadersBecomeSafeRetryMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HttpTransport(connection(server)).use { transport ->
                server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2").setBody("private server diagnostic"))
                try { transport.request("GET", "/v1/notes"); fail() }
                catch (error: BackendFailure) {
                    assertEquals(FailureKind.THROTTLED, error.kind)
                    assertEquals(2000L, error.retryAfterMillis)
                    assertFalse(error.toString().contains("private server diagnostic"))
                    assertNull(error.cause)
                }
            }
        }
        assertEquals(2000L, retryAfter("Thu, 01 Jan 1970 00:00:02 GMT", 0))
        assertNull(retryAfter("invalid"))
        assertEquals(Long.MAX_VALUE, retryAfter("999999999999999999999999999999"))
    }
    @Test fun wrongStreamContentTypeFailsWithoutTreatingHtmlAsEvents() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            HttpTransport(connection(server)).use { transport ->
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("login page"))
                try { transport.events("/v1/runs/run/events", 0).collect(); fail() }
                catch (error: BackendFailure) { assertEquals(FailureKind.PROTOCOL, error.kind) }
            }
        }
    }
}
