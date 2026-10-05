package com.character.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.character.app.domain.MessageDraft
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftMigrationTest {
    @Test fun upgradePreservesPendingIdentityAndCachedCursor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(File(System.getProperty("character.schemas"), "com.character.app.data.ClientDatabase/1.json")
            .readText()).getJSONObject("database")
        val backend = FixtureBackend()
        val run = backend.sample("running", 4)
        // Recreate the actual checked-in v1 schema, including identity hash and unique outbox index.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                if (indices != null) for (j in 0 until indices.length()) {
                    old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO snapshots VALUES (?, ?, ?, ?, ?)", arrayOf("profile", "runs:conversation", run.id, JsonCodec.encode(run).toString(), "now"))
            old.execSQL("INSERT INTO snapshots VALUES (?, ?, ?, ?, ?)", arrayOf("profile", "messages:conversation", run.output.id, JsonCodec.encode(run.output).toString(), "00002"))
            old.execSQL("INSERT INTO outbox VALUES (?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>("profile", "pending", "other-conversation", "original-request", "未确认正文", "结果不明", 0))
            old.execSQL("INSERT INTO conversation_requests VALUES (?, ?, ?)", arrayOf("profile", "character", "original-create-request"))
            old.version = 1
        }
        try {
            val upgraded = Room.databaseBuilder(context, ClientDatabase::class.java, name).build()
            try {
                val repo = OfflineRepository(upgraded, "profile", backend)
                assertEquals(run, repo.runs("conversation").first().single())
                assertEquals(run.output, repo.messages("conversation").first().single())
                val pending = upgraded.dao().pendingById("profile", "pending")!!
                assertEquals("original-request", pending.requestKey)
                assertEquals("未确认正文", pending.text)
                assertFalse(pending.blocked)
                assertEquals("结果不明", pending.error)
                assertEquals("original-create-request", upgraded.dao().conversationRequest("profile", "character")!!.requestKey)
                assertNull(repo.draft("conversation"))
                val draft = MessageDraft("revision", "新草稿")
                repo.saveDraft("conversation", draft)
                assertEquals(draft, repo.draft("conversation"))
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }
}
