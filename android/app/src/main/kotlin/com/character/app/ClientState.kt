package com.character.app

import com.character.app.domain.*

/**
 * Single source of truth for the entire client application UI state.
 */
data class ClientState(
    // --- Connection & Preferences ---
    val connected: Boolean = false,
    val url: String = "http://127.0.0.1:8000/",
    val polling: Boolean = false,
    val dark: Boolean = true,

    // --- Navigation & Global Status ---
    val tab: String = "设置",
    val busy: Boolean = false,
    val error: String? = null,

    // --- Characters Management ---
    val characters: List<Character> = emptyList(),
    val canManageCharacters: Boolean = false,
    val characterEditor: CharacterDraft? = null,
    val pendingCharacter: CharacterDraft? = null,

    // --- User Profile Management ---
    val canManageProfile: Boolean = false,
    val userProfile: UserProfile? = null,
    val profileEditor: UserProfile? = null,
    val pendingProfileChange: ProfileChange? = null,

    // --- Conversations & Notes ---
    val conversations: List<Conversation> = emptyList(),
    val selected: Conversation? = null,
    val notes: List<Note> = emptyList(),

    // --- Chat Session & Outbox ---
    val messages: List<Message> = emptyList(),
    val pending: List<PendingMessage> = emptyList(),
    val runs: List<Run> = emptyList(),

    // --- Draft Editing ---
    val draft: String = "",
    val draftState: DraftSaveState = DraftSaveState.LOADING,
)
