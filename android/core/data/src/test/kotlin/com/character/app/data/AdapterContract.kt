package com.character.app.data

import com.character.app.domain.*
import java.util.UUID
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*

/** Executed unchanged against the in-memory adapter and both real HTTP connection modes. */
internal suspend fun verifyAdapterContract(adapter: BackendAdapter, characterId: String) {
    val conversationKey = UUID.randomUUID().toString()
    val conversation = adapter.createConversation(characterId, conversationKey)
    assertEquals(conversation.id, adapter.createConversation(characterId, conversationKey).id)
    val messageId = UUID.randomUUID().toString()
    val requestKey = UUID.randomUUID().toString()
    var run = adapter.createRun(conversation.id, messageId, requestKey, "你好")
    val duplicate = adapter.createRun(conversation.id, messageId, requestKey, "你好")
    assertEquals(run.id, duplicate.id)
    assertEquals(run.input.id, duplicate.input.id)
    try { adapter.createRun(conversation.id, messageId, requestKey, "changed"); fail("Same key cannot change payload") }
    catch (failure: BackendFailure) { assertEquals(FailureKind.CONFLICT, failure.kind) }
    withTimeout(8000) { RunMonitor(adapter).follow({ run }, { run = it }) }
    assertTrue(run.terminal)
    assertTrue(run.output.text.isNotBlank())
    assertEquals(run.id, adapter.run(run.id).id)
    assertEquals(run.status, adapter.cancel(run.id).status) // Terminal cancel is idempotent.
    val messages = adapter.messages(conversation.id, null).items
    assertEquals(2, messages.size)
    assertEquals(2, messages.map { it.id }.toSet().size)
}
