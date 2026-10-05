package com.character.app.data

import com.character.app.domain.*
import org.json.JSONObject

internal object JsonCodec {
    fun profile(o: JSONObject) = UserProfile(o.getString("nickname"), o.getString("bio"), o.getInt("version"))
    fun encode(v: UserProfile) = JSONObject().put("nickname", v.nickname).put("bio", v.bio).put("version", v.version)
    fun profileChange(o: JSONObject) = ProfileChange(profile(o.getJSONObject("value")), o.getBoolean("reset"))
    fun encode(v: ProfileChange) = JSONObject().put("value", encode(v.value)).put("reset", v.reset)
    fun character(o: JSONObject) = Character(o.getString("id"), o.getString("name"), o.getString("summary"), o.getInt("version"), o.optBoolean("editable", false))
    fun characterDraft(o: JSONObject) = CharacterDraft(o.getString("id"), o.getString("name"), o.getString("summary"), o.getString("system_prompt"), o.getBoolean("notes_enabled"), o.getInt("version"))
    fun encode(v: CharacterDraft) = JSONObject().put("id", v.id).put("name", v.name).put("summary", v.summary)
        .put("system_prompt", v.systemPrompt).put("notes_enabled", v.notesEnabled).put("version", v.version)
    fun conversation(o: JSONObject) = Conversation(o.getString("id"), o.getString("character_id"), o.getString("title"), o.getString("updated_at"))
    fun message(o: JSONObject): Message {
        val blocks = o.getJSONArray("blocks")
        return Message(o.getString("id"), o.getString("conversation_id"), o.getString("run_id"), o.getLong("sequence"),
            o.getString("role"), (0 until blocks.length()).map { MessageBlock(blocks.getJSONObject(it).getString("type"), blocks.getJSONObject(it).getString("text")) },
            o.getString("status"), o.getString("updated_at"))
    }
    fun run(o: JSONObject) = Run(o.getString("id"), o.getString("conversation_id"), o.getString("status"), o.getLong("last_event_sequence"),
        message(o.getJSONObject("input_message")), message(o.getJSONObject("output_message")), o.getBoolean("is_simulated"),
        o.optJSONObject("error")?.optString("message"))
    fun note(o: JSONObject) = Note(o.getString("id"), o.getString("conversation_id"), o.getString("title"), o.getString("content"), o.getString("created_at"))
    fun <T> page(text: String, read: (JSONObject) -> T): Page<T> {
        val o = JSONObject(text)
        val items = o.getJSONArray("items")
        return Page((0 until items.length()).map { read(items.getJSONObject(it)) }, if (o.isNull("next_cursor")) null else o.getString("next_cursor"))
    }
    fun encode(v: Character) = JSONObject().put("id", v.id).put("name", v.name).put("summary", v.summary).put("version", v.version).put("editable", v.editable)
    fun encode(v: Conversation) = JSONObject().put("id", v.id).put("character_id", v.characterId).put("title", v.title).put("updated_at", v.updatedAt)
    fun encode(v: Message) = JSONObject().put("id", v.id).put("conversation_id", v.conversationId).put("run_id", v.runId)
        .put("sequence", v.sequence).put("role", v.role).put("blocks", org.json.JSONArray().apply { v.blocks.forEach { put(JSONObject().put("type", it.kind).put("text", it.text)) } })
        .put("status", v.status).put("updated_at", v.updatedAt)
    fun encode(v: Run) = JSONObject().put("id", v.id).put("conversation_id", v.conversationId).put("status", v.status)
        .put("last_event_sequence", v.sequence).put("input_message", encode(v.input)).put("output_message", encode(v.output))
        .put("is_simulated", v.simulated).put("error", v.error?.let { JSONObject().put("message", it) } ?: JSONObject.NULL)
    fun encode(v: Note) = JSONObject().put("id", v.id).put("conversation_id", v.conversationId).put("title", v.title).put("content", v.content).put("created_at", v.createdAt)
}
