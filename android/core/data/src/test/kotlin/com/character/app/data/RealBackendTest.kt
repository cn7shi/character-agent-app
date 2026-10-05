package com.character.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.character.app.network.*
import com.character.app.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealBackendTest {
    private fun connection(): BackendConnection {
        val directory = System.getenv("CHARACTER_ANDROID_TEST_DIR")
        assumeTrue("Run backend/scripts/check_android.py for the local HTTP integration check", directory != null)
        val endpoint = File(directory, "android-endpoint.txt").readText().trim()
        val users = JSONArray(File(directory, "development-users.json").readText())
        return BackendConnection(endpoint, users.getJSONObject(0).getString("token"))
    }
    @Test fun bothHttpModesPassTheSameContractAsTheMemoryAdapter() = runBlocking {
        for (mode in ConnectionMode.entries) {
            BackendFactory.connect(connection(), mode).use { api ->
                assertEquals(mode == ConnectionMode.REALTIME, api.supportsEvents)
                verifyAdapterContract(api, api.characters(null).items.first().id)
            }
        }
    }
    @Test fun realEventStreamResumesAfterDisconnectAndObservesExplicitCancellation() = runBlocking {
        BackendFactory.connect(connection(), ConnectionMode.REALTIME).use { api ->
            val conversation = api.createConversation(api.characters(null).items.first().id, UUID.randomUUID().toString())
            val run = api.createRun(conversation.id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "今天有点累")
            val first = withTimeout(8000) { api.events(run.id, 0).take(2).toList() }
            assertEquals(listOf(1L, 2L), first.map { it.sequence })
            val remaining = withTimeout(8000) { api.events(run.id, first.last().sequence).toList() }
            assertTrue(remaining.isNotEmpty())
            assertTrue(remaining.all { it.sequence > first.last().sequence })
            assertEquals(EventKind.COMPLETED, remaining.last().kind)
            val next = api.createRun(conversation.id, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "你好")
            var stopped = api.cancel(next.id)
            withTimeout(8000) { RunMonitor(api).follow({ stopped }, { stopped = it }) }
            assertEquals("cancelled", stopped.status)
            assertEquals(4, api.messages(conversation.id, null).items.size)
        }
    }
    @Test fun nativeRepositoryTalksToFastApiAndPersistsActualNotes() = runBlocking {
        val directory = System.getenv("CHARACTER_ANDROID_TEST_DIR")
        assumeTrue("Run backend/scripts/check_android.py for the local HTTP integration check", directory != null)
        val endpoint = File(directory, "android-endpoint.txt").readText().trim()
        val users = JSONArray(File(directory, "development-users.json").readText())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "integration-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, ClientDatabase::class.java, name).build()
        val connection = BackendConnection(endpoint, users.getJSONObject(0).getString("token"))
        val api = BackendFactory.connect(connection, ConnectionMode.REALTIME)
        var repository = OfflineRepository(database, connection.scope, api)
        try {
            repository.refreshHome()
            assertEquals(2, repository.characters().first().size)
            val character = repository.characters().first().first { it.name.contains("林舟") }
            val conversation = repository.createConversation(character.id)
            val observing = launch { repository.observeConversation(conversation) }
            try {
                for (text in listOf("你好", "今天有点累", "记笔记：Android 联调；缓存记录；稍后接 AI")) {
                    repository.send(conversation, text)
                    withTimeout(8000) {
                        repository.runs(conversation).first { runs -> runs.isNotEmpty() && runs.all { it.terminal } }
                    }
                }
            } finally { observing.cancelAndJoin() }
            repository.refreshNotes()
            assertEquals(6, repository.messages(conversation).first().size)
            val note = repository.notes().first().single()
            assertEquals("Android 联调", note.title)
            assertTrue(note.content.contains("缓存记录"))
            assertTrue(repository.messages(conversation).first().filter { it.role == "assistant" }.all { it.text.contains("【模拟回复】") && it.status == "completed" })
            database.close()
            database = Room.databaseBuilder(context, ClientDatabase::class.java, name).build()
            repository = OfflineRepository(database, connection.scope, api)
            assertEquals(6, repository.messages(conversation).first().size)
            assertEquals(note, repository.notes().first().single())
            val other = BackendConnection(endpoint, users.getJSONObject(1).getString("token"))
            BackendFactory.connect(other, ConnectionMode.POLLING).use { otherApi ->
                val otherRepository = OfflineRepository(database, other.scope, otherApi)
                otherRepository.refreshNotes()
                assertTrue(otherRepository.notes().first().isEmpty())
            }
        } finally { api.close(); database.close(); context.deleteDatabase(name) }
    }
}
