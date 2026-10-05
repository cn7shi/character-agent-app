package com.character.app.data

import com.character.app.domain.*
import com.character.app.network.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

enum class ConnectionMode { REALTIME, POLLING }

/** Composition root: a future protocol registers an adapter here without changing the repository/UI. */
object BackendFactory {
    fun connect(connection: BackendConnection, mode: ConnectionMode): BackendAdapter {
        val transport = HttpTransport(connection)
        return ReliableBackend(HttpBackend(transport, if (mode == ConnectionMode.REALTIME) transport else null, transport::close))
    }
}

class HttpBackend(private val transport: HttpRequests, private val eventTransport: HttpEvents? = null,
                  private val release: () -> Unit = {}) : BackendAdapter {
    override val supportsProfile = true
    override suspend fun profile() = decode { JsonCodec.profile(JSONObject(transport.request("GET", "/v1/me/profile"))) }
    override suspend fun saveProfile(profile: UserProfile) = decode {
        val body = JsonCodec.encode(profile).apply { remove("version"); put("expected_version", profile.version) }
        JsonCodec.profile(JSONObject(transport.request("PUT", "/v1/me/profile", body.toString())))
    }
    override suspend fun resetProfile(version: Int) = decode {
        JsonCodec.profile(JSONObject(transport.request("POST", "/v1/me/profile/reset", JSONObject().put("expected_version", version).toString())))
    }
    override val supportsCharacterManagement = true
    override suspend fun characterDefinition(id: String) = decode { JsonCodec.characterDraft(JSONObject(transport.request("GET", "/v1/characters/$id/definition"))) }
    override suspend fun saveCharacter(draft: CharacterDraft) = decode {
        val body = JsonCodec.encode(draft).apply { remove("id"); remove("version"); put("expected_version", draft.version) }
        JsonCodec.characterDraft(JSONObject(transport.request("PUT", "/v1/characters/${draft.id}", body.toString())))
    }
    override suspend fun deleteCharacter(id: String) { decode {
        if (!JSONObject(transport.request("DELETE", "/v1/characters/$id")).getBoolean("deleted")) throw BackendFailure(FailureKind.PROTOCOL)
    } }
    override val supportsEvents get() = eventTransport != null
    override fun close() = release()
    private inline fun <T> decode(read: () -> T): T = try { read() }
        catch (_: JSONException) { throw BackendFailure(FailureKind.PROTOCOL) }

    private suspend fun <T> page(path: String, cursor: String?, read: (JSONObject) -> T): Page<T> =
        decode { JsonCodec.page(transport.request("GET", path, query = mapOf("limit" to "100") + (cursor?.let { mapOf("cursor" to it) } ?: emptyMap())), read) }
    override suspend fun characters(cursor: String?) = page("/v1/characters", cursor, JsonCodec::character)
    override suspend fun conversations(cursor: String?) = page("/v1/conversations", cursor, JsonCodec::conversation)
    override suspend fun messages(conversationId: String, cursor: String?) = page("/v1/conversations/$conversationId/messages", cursor, JsonCodec::message)
    override suspend fun notes(cursor: String?) = page("/v1/notes", cursor, JsonCodec::note)
    override suspend fun createConversation(characterId: String, requestKey: String) = decode { JsonCodec.conversation(JSONObject(
        transport.request("POST", "/v1/conversations", JSONObject().put("character_id", characterId).toString(), requestKey))) }
    override suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String) = decode { JsonCodec.run(JSONObject(
        transport.request("POST", "/v1/conversations/$conversationId/runs", JSONObject().put("client_message_id", messageId).put("text", text).toString(), requestKey))) }
    override suspend fun run(runId: String) = decode { JsonCodec.run(JSONObject(transport.request("GET", "/v1/runs/$runId"))) }
    override suspend fun cancel(runId: String) = decode { JsonCodec.run(JSONObject(transport.request("POST", "/v1/runs/$runId/cancel", "{}"))) }

    override fun events(runId: String, afterSequence: Long): Flow<RunEvent> {
        val source = eventTransport ?: return flow { throw BackendFailure(FailureKind.UNSUPPORTED) }
        return source.events("/v1/runs/$runId/events", afterSequence).map { frame -> decode {
            val envelope = JSONObject(frame.data)
            val sequence = envelope.getLong("sequence")
            val type = envelope.getString("type")
            if (!frame.id.matches(Regex("[0-9]{1,18}")) || frame.id.toLongOrNull() != sequence ||
                envelope.getString("run_id") != runId || frame.type != type || sequence < 1)
                throw BackendFailure(FailureKind.PROTOCOL)
            val data = envelope.getJSONObject("data")
            val kind = when (type) {
                "run.accepted" -> EventKind.ACCEPTED
                "run.started" -> EventKind.STARTED
                "message.updated" -> EventKind.MESSAGE
                "run.cancel_requested" -> EventKind.CANCEL_REQUESTED
                "run.completed" -> EventKind.COMPLETED
                "run.failed" -> EventKind.FAILED
                "run.cancelled" -> EventKind.CANCELLED
                "tool.started", "tool.completed", "tool.failed" -> EventKind.TOOL
                else -> EventKind.UNKNOWN
            }
            val message = when (kind) {
                EventKind.MESSAGE -> JsonCodec.message(data.getJSONObject("message"))
                EventKind.COMPLETED, EventKind.FAILED, EventKind.CANCELLED -> JsonCodec.message(data.getJSONObject("output_message"))
                else -> null
            }
            RunEvent(runId, sequence, kind, message, data.optJSONObject("error")?.optString("message"))
        } }
    }
}
