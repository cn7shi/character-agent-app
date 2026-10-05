package com.character.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.character.app.network.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    @Test fun nativeRepositoryTalksToFastApiAndPersistsActualNotes() = runBlocking {
        val directory = System.getenv("CHARACTER_ANDROID_TEST_DIR")
        assumeTrue("Run backend/scripts/check_android.py for the local HTTP integration check", directory != null)
        val endpoint = File(directory, "android-endpoint.txt").readText().trim()
        val users = JSONArray(File(directory, "development-users.json").readText())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "integration-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, ClientDatabase::class.java, name).build()
        val connection = BackendConnection(endpoint, users.getJSONObject(0).getString("token"))
        val api = HttpBackend(HttpTransport(connection))
        var repository = OfflineRepository(database, connection.scope, api)
        try {
            repository.refreshHome()
            assertEquals(2, repository.characters().first().size)
            val character = repository.characters().first().first { it.name.contains("林舟") }
            val conversation = repository.createConversation(character.id)
            for (text in listOf("你好", "今天有点累", "记笔记：Android 联调；缓存记录；稍后接 AI")) {
                repository.send(conversation, text)
                withTimeout(8000) {
                    while (repository.runs(conversation).first().any { !it.terminal }) { delay(100); repository.poll(conversation) }
                }
            }
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
            val otherRepository = OfflineRepository(database, other.scope, HttpBackend(HttpTransport(other)))
            otherRepository.refreshNotes()
            assertTrue(otherRepository.notes().first().isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
