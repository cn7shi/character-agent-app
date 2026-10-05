package com.character.app.data

import com.character.app.domain.*
import com.character.app.network.Transport
import org.json.JSONObject

class HttpBackend(private val transport: Transport) : BackendGateway {
    private suspend fun <T> page(path: String, cursor: String?, read: (JSONObject) -> T): Page<T> =
        JsonCodec.page(transport.request("GET", path, query = mapOf("limit" to "100") + (cursor?.let { mapOf("cursor" to it) } ?: emptyMap())), read)
    override suspend fun characters(cursor: String?) = page("/v1/characters", cursor, JsonCodec::character)
    override suspend fun conversations(cursor: String?) = page("/v1/conversations", cursor, JsonCodec::conversation)
    override suspend fun messages(conversationId: String, cursor: String?) = page("/v1/conversations/$conversationId/messages", cursor, JsonCodec::message)
    override suspend fun notes(cursor: String?) = page("/v1/notes", cursor, JsonCodec::note)
    override suspend fun createConversation(characterId: String, requestKey: String) = JsonCodec.conversation(JSONObject(
        transport.request("POST", "/v1/conversations", JSONObject().put("character_id", characterId).toString(), requestKey)))
    override suspend fun createRun(conversationId: String, messageId: String, requestKey: String, text: String) = JsonCodec.run(JSONObject(
        transport.request("POST", "/v1/conversations/$conversationId/runs", JSONObject().put("client_message_id", messageId).put("text", text).toString(), requestKey)))
    override suspend fun run(runId: String) = JsonCodec.run(JSONObject(transport.request("GET", "/v1/runs/$runId")))
    override suspend fun cancel(runId: String) = JsonCodec.run(JSONObject(transport.request("POST", "/v1/runs/$runId/cancel", "{}")))
}
