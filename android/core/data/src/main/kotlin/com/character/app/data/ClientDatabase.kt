package com.character.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// D18: versioned local projections, separate from server tables. JSON stays inside the data module.
@Entity(tableName = "snapshots", primaryKeys = ["scope", "kind", "id"])
data class Snapshot(val scope: String, val kind: String, val id: String, val payload: String, val sortKey: String)

@Entity(tableName = "outbox", primaryKeys = ["scope", "id"], indices = [Index(value = ["scope", "conversationId"], unique = true)])
data class Outbox(val scope: String, val id: String, val conversationId: String, val requestKey: String, val text: String,
                  val error: String? = null, val blocked: Boolean = false)

@Entity(tableName = "conversation_requests", primaryKeys = ["scope", "characterId"])
data class ConversationRequest(val scope: String, val characterId: String, val requestKey: String)

@Entity(tableName = "drafts", primaryKeys = ["scope", "conversationId"])
data class DraftRow(val scope: String, val conversationId: String, val revision: String, val text: String)

@Dao
interface ClientDao {
    @Query("SELECT * FROM drafts WHERE scope = :scope AND conversationId = :conversationId")
    suspend fun draft(scope: String, conversationId: String): DraftRow?
    @Upsert suspend fun saveDraft(row: DraftRow)
    @Query("DELETE FROM drafts WHERE scope = :scope AND conversationId = :conversationId")
    suspend fun clearDraft(scope: String, conversationId: String)
    @Query("DELETE FROM drafts WHERE scope = :scope AND conversationId = :conversationId AND revision = :revision")
    suspend fun consumeDraft(scope: String, conversationId: String, revision: String)
    @Query("SELECT * FROM snapshots WHERE scope = :scope AND kind = :kind ORDER BY sortKey DESC, id DESC")
    fun observe(scope: String, kind: String): Flow<List<Snapshot>>
    @Query("SELECT * FROM snapshots WHERE scope = :scope AND kind = :kind ORDER BY sortKey DESC, id DESC")
    suspend fun list(scope: String, kind: String): List<Snapshot>
    @Query("SELECT * FROM snapshots WHERE scope = :scope AND kind = :kind AND id = :id")
    suspend fun get(scope: String, kind: String, id: String): Snapshot?
    @Upsert suspend fun put(rows: List<Snapshot>)
    @Query("DELETE FROM snapshots WHERE scope = :scope AND kind = :kind")
    suspend fun clearKind(scope: String, kind: String)
    @Query("DELETE FROM snapshots WHERE scope = :scope AND kind = :kind AND id = :id")
    suspend fun deleteSnapshot(scope: String, kind: String, id: String)
    @Query("SELECT * FROM outbox WHERE scope = :scope AND conversationId = :conversationId")
    fun observePending(scope: String, conversationId: String): Flow<List<Outbox>>
    @Query("SELECT * FROM outbox WHERE scope = :scope AND conversationId = :conversationId")
    suspend fun pending(scope: String, conversationId: String): List<Outbox>
    @Query("SELECT * FROM outbox WHERE scope = :scope AND id = :id")
    suspend fun pendingById(scope: String, id: String): Outbox?
    @Insert suspend fun insertPending(row: Outbox)
    @Update suspend fun updatePending(row: Outbox)
    @Query("DELETE FROM outbox WHERE scope = :scope AND id = :id")
    suspend fun deletePending(scope: String, id: String)
    @Query("SELECT * FROM conversation_requests WHERE scope = :scope AND characterId = :characterId")
    suspend fun conversationRequest(scope: String, characterId: String): ConversationRequest?
    @Insert suspend fun putConversationRequest(row: ConversationRequest)
    @Query("DELETE FROM conversation_requests WHERE scope = :scope AND characterId = :characterId")
    suspend fun deleteConversationRequest(scope: String, characterId: String)
}

// D26: additive migration generated from checked-in schemas; never discard pending requests on upgrade.
@Database(entities = [Snapshot::class, Outbox::class, ConversationRequest::class, DraftRow::class],
    version = 2, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class ClientDatabase : RoomDatabase() { abstract fun dao(): ClientDao }
