package com.character.app.data

import com.character.app.domain.*
import com.character.app.network.HttpEvents
import com.character.app.network.HttpRequests
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONException
import org.json.JSONObject

/**
 * HTTP implementation of [BackendAdapter] interacting with the API v1 service.
 */
class HttpBackend(
    private val transport: HttpRequests,
    private val eventTransport: HttpEvents? = null,
    private val release: () -> Unit = {},
) : BackendAdapter {

    override val supportsProfile: Boolean = true

    override suspend fun profile(): UserProfile = decode {
        val raw = transport.request("GET", "/v1/me/profile")
        JsonCodec.profile(JSONObject(raw))
    }

    override suspend fun saveProfile(profile: UserProfile): UserProfile = decode {
        val body = JsonCodec.encode(profile).apply {
            remove("version")
            put("expected_version", profile.version)
        }
        val raw = transport.request("PUT", "/v1/me/profile", body.toString())
        JsonCodec.profile(JSONObject(raw))
    }

    override suspend fun resetProfile(version: Int): UserProfile = decode {
        val body = JSONObject().put("expected_version", version)
        val raw = transport.request("POST", "/v1/me/profile/reset", body.toString())
        JsonCodec.profile(JSONObject(raw))
    }

    override val supportsCharacterManagement: Boolean = true

    override suspend fun characterDefinition(id: String): CharacterDraft = decode {
        val raw = transport.request("GET", "/v1/characters/$id/definition")
        JsonCodec.characterDraft(JSONObject(raw))
    }

    override suspend fun saveCharacter(draft: CharacterDraft): CharacterDraft = decode {
        val body = JsonCodec.encode(draft).apply {
            remove("id")
            remove("version")
            put("expected_version", draft.version)
        }
        val raw = transport.request("PUT", "/v1/characters/${draft.id}", body.toString())
        JsonCodec.characterDraft(JSONObject(raw))
    }

    override suspend fun deleteCharacter(id: String) {
        decode {
            val raw = transport.request("DELETE", "/v1/characters/$id")
            val deleted = JSONObject(raw).getBoolean("deleted")
            if (!deleted) {
                throw BackendFailure(FailureKind.PROTOCOL)
            }
        }
    }

    override val supportsEvents: Boolean
        get() = eventTransport != null

    override fun close() {
        release()
    }

    private inline fun <T> decode(read: () -> T): T {
        return try {
            read()
        } catch (_: JSONException) {
            throw BackendFailure(FailureKind.PROTOCOL)
        }
    }

    private suspend fun <T> page(
        path: String,
        cursor: String?,
        read: (JSONObject) -> T,
    ): Page<T> = decode {
        val query = mutableMapOf("limit" to "100")
        if (cursor != null) {
            query["cursor"] = cursor
        }
        val raw = transport.request("GET", path, query = query)
        JsonCodec.page(raw, read)
    }

    override suspend fun characters(cursor: String?): Page<Character> {
        return page("/v1/characters", cursor, JsonCodec::character)
    }

    override suspend fun conversations(cursor: String?): Page<Conversation> {
        return page("/v1/conversations", cursor, JsonCodec::conversation)
    }

    override suspend fun messages(conversationId: String, cursor: String?): Page<Message> {
        return page("/v1/conversations/$conversationId/messages", cursor, JsonCodec::message)
    }

    override suspend fun notes(cursor: String?): Page<Note> {
        return page("/v1/notes", cursor, JsonCodec::note)
    }

    override suspend fun createConversation(
        characterId: String,
        requestKey: String,
    ): Conversation = decode {
        val body = JSONObject().put("character_id", characterId).toString()
        val raw = transport.request("POST", "/v1/conversations", body, requestKey)
        JsonCodec.conversation(JSONObject(raw))
    }

    override suspend fun renameConversation(
        conversationId: String,
        title: String,
        expectedVersion: Int,
    ): Conversation = decode {
        val body = JSONObject()
            .put("title", title)
            .put("expected_version", expectedVersion)
            .toString()
        val raw = transport.request("PATCH", "/v1/conversations/$conversationId", body)
        JsonCodec.conversation(JSONObject(raw))
    }

    override suspend fun createRun(
        conversationId: String,
        messageId: String,
        requestKey: String,
        text: String,
    ): Run = decode {
        val body = JSONObject()
            .put("client_message_id", messageId)
            .put("text", text)
            .toString()
        val raw = transport.request("POST", "/v1/conversations/$conversationId/runs", body, requestKey)
        JsonCodec.run(JSONObject(raw))
    }

    override suspend fun run(runId: String): Run = decode {
        val raw = transport.request("GET", "/v1/runs/$runId")
        JsonCodec.run(JSONObject(raw))
    }

    override suspend fun cancel(runId: String): Run = decode {
        val raw = transport.request("POST", "/v1/runs/$runId/cancel", "{}")
        JsonCodec.run(JSONObject(raw))
    }

    override fun events(runId: String, afterSequence: Long): Flow<RunEvent> {
        val source = eventTransport ?: return flow {
            throw BackendFailure(FailureKind.UNSUPPORTED)
        }
        return source.events("/v1/runs/$runId/events", afterSequence).map { frame ->
            decode {
                val envelope = JSONObject(frame.data)
                val sequence = envelope.getLong("sequence")
                val type = envelope.getString("type")

                val validFrameId = frame.id.matches(FRAME_ID_REGEX) && frame.id.toLongOrNull() == sequence
                if (!validFrameId || envelope.getString("run_id") != runId || frame.type != type || sequence < 1) {
                    throw BackendFailure(FailureKind.PROTOCOL)
                }

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
                    EventKind.COMPLETED, EventKind.FAILED, EventKind.CANCELLED -> {
                        JsonCodec.message(data.getJSONObject("output_message"))
                    }
                    else -> null
                }

                RunEvent(
                    runId = runId,
                    sequence = sequence,
                    kind = kind,
                    message = message,
                    error = data.optJSONObject("error")?.optString("message"),
                )
            }
        }
    }

    private companion object {
        private val FRAME_ID_REGEX = Regex("[0-9]{1,18}")
    }
}
