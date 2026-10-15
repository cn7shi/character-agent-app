package com.character.app.data

import com.character.app.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal object JsonCodec {

    fun profile(o: JSONObject): UserProfile {
        return UserProfile(
            nickname = o.getString("nickname"),
            bio = o.getString("bio"),
            version = o.getInt("version"),
        )
    }

    fun encode(v: UserProfile): JSONObject {
        return JSONObject()
            .put("nickname", v.nickname)
            .put("bio", v.bio)
            .put("version", v.version)
    }

    fun profileChange(o: JSONObject): ProfileChange {
        return ProfileChange(
            value = profile(o.getJSONObject("value")),
            reset = o.getBoolean("reset"),
        )
    }

    fun encode(v: ProfileChange): JSONObject {
        return JSONObject()
            .put("value", encode(v.value))
            .put("reset", v.reset)
    }

    fun character(o: JSONObject): Character {
        return Character(
            id = o.getString("id"),
            name = o.getString("name"),
            summary = o.getString("summary"),
            version = o.getInt("version"),
            editable = o.optBoolean("editable", false),
        )
    }

    fun encode(v: Character): JSONObject {
        return JSONObject()
            .put("id", v.id)
            .put("name", v.name)
            .put("summary", v.summary)
            .put("version", v.version)
            .put("editable", v.editable)
    }

    fun characterDraft(o: JSONObject): CharacterDraft {
        return CharacterDraft(
            id = o.getString("id"),
            name = o.getString("name"),
            summary = o.getString("summary"),
            systemPrompt = o.getString("system_prompt"),
            notesEnabled = o.getBoolean("notes_enabled"),
            version = o.getInt("version"),
        )
    }

    fun encode(v: CharacterDraft): JSONObject {
        return JSONObject()
            .put("id", v.id)
            .put("name", v.name)
            .put("summary", v.summary)
            .put("system_prompt", v.systemPrompt)
            .put("notes_enabled", v.notesEnabled)
            .put("version", v.version)
    }

    fun conversation(o: JSONObject): Conversation {
        return Conversation(
            id = o.getString("id"),
            characterId = o.getString("character_id"),
            title = o.getString("title"),
            updatedAt = o.getString("updated_at"),
            version = o.optInt("version", 1),
        )
    }

    fun encode(v: Conversation): JSONObject {
        return JSONObject()
            .put("id", v.id)
            .put("character_id", v.characterId)
            .put("title", v.title)
            .put("updated_at", v.updatedAt)
            .put("version", v.version)
    }

    fun message(o: JSONObject): Message {
        val blocksArray = o.getJSONArray("blocks")
        val blocks = (0 until blocksArray.length()).map { index ->
            val blockObj = blocksArray.getJSONObject(index)
            MessageBlock(
                kind = blockObj.getString("type"),
                text = blockObj.getString("text"),
            )
        }
        return Message(
            id = o.getString("id"),
            conversationId = o.getString("conversation_id"),
            runId = o.getString("run_id"),
            sequence = o.getLong("sequence"),
            role = o.getString("role"),
            blocks = blocks,
            status = o.getString("status"),
            updatedAt = o.getString("updated_at"),
        )
    }

    fun encode(v: Message): JSONObject {
        val blocksArray = JSONArray().apply {
            v.blocks.forEach { block ->
                put(
                    JSONObject()
                        .put("type", block.kind)
                        .put("text", block.text)
                )
            }
        }
        return JSONObject()
            .put("id", v.id)
            .put("conversation_id", v.conversationId)
            .put("run_id", v.runId)
            .put("sequence", v.sequence)
            .put("role", v.role)
            .put("blocks", blocksArray)
            .put("status", v.status)
            .put("updated_at", v.updatedAt)
    }

    fun run(o: JSONObject): Run {
        return Run(
            id = o.getString("id"),
            conversationId = o.getString("conversation_id"),
            status = o.getString("status"),
            sequence = o.getLong("last_event_sequence"),
            input = message(o.getJSONObject("input_message")),
            output = message(o.getJSONObject("output_message")),
            simulated = o.getBoolean("is_simulated"),
            error = o.optJSONObject("error")?.optString("message"),
        )
    }

    fun encode(v: Run): JSONObject {
        val errorObj = v.error?.let { JSONObject().put("message", it) } ?: JSONObject.NULL
        return JSONObject()
            .put("id", v.id)
            .put("conversation_id", v.conversationId)
            .put("status", v.status)
            .put("last_event_sequence", v.sequence)
            .put("input_message", encode(v.input))
            .put("output_message", encode(v.output))
            .put("is_simulated", v.simulated)
            .put("error", errorObj)
    }

    fun note(o: JSONObject): Note {
        return Note(
            id = o.getString("id"),
            conversationId = o.getString("conversation_id"),
            title = o.getString("title"),
            content = o.getString("content"),
            createdAt = o.getString("created_at"),
        )
    }

    fun encode(v: Note): JSONObject {
        return JSONObject()
            .put("id", v.id)
            .put("conversation_id", v.conversationId)
            .put("title", v.title)
            .put("content", v.content)
            .put("created_at", v.createdAt)
    }

    fun <T> page(text: String, read: (JSONObject) -> T): Page<T> {
        val o = JSONObject(text)
        val itemsArray = o.getJSONArray("items")
        val items = (0 until itemsArray.length()).map { read(itemsArray.getJSONObject(it)) }
        val nextCursor = if (o.isNull("next_cursor")) null else o.getString("next_cursor")
        return Page(items = items, nextCursor = nextCursor)
    }
}
