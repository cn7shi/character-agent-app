package com.character.app.data

import androidx.room.withTransaction
import com.character.app.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** D30: cache and unknown writes share the connection scope, independently of authentication data. */
internal class ProfileCache(private val db: ClientDatabase, private val scope: String, private val remote: BackendAdapter) {
    private val dao = db.dao()
    private val gate = Mutex()
    fun observe() = dao.observe(scope, "profile").map { rows -> rows.singleOrNull()?.let { JsonCodec.profile(JSONObject(it.payload)) } }
    fun observePending() = dao.observe(scope, "profile_write").map { rows -> rows.singleOrNull()?.let { JsonCodec.profileChange(JSONObject(it.payload)) } }
    private suspend fun pending() = dao.get(scope, "profile_write", "pending")?.let { JsonCodec.profileChange(JSONObject(it.payload)) }
    private suspend fun store(value: UserProfile) {
        if (value.version < 0) throw BackendFailure(FailureKind.PROTOCOL)
        val cached = dao.get(scope, "profile", "me")?.let { JsonCodec.profile(JSONObject(it.payload)) }
        // A delayed response from another session must not overwrite a newer local snapshot.
        if (cached != null && cached.version > value.version) return
        if (cached != null && cached.version == value.version && cached != value) throw BackendFailure(FailureKind.PROTOCOL)
        dao.put(listOf(Snapshot(scope, "profile", "me", JsonCodec.encode(value).toString(), "")))
    }
    suspend fun refresh() = gate.withLock {
        val value = remote.profile()
        db.withTransaction { store(value) }
    }
    suspend fun save(value: UserProfile) = gate.withLock {
        val normalized = value.copy(nickname = value.nickname.trim(), bio = value.bio.trim())
        require(normalized.version >= 0 && normalized.nickname.codePointCount(0, normalized.nickname.length) in 1..50 &&
            normalized.bio.codePointCount(0, normalized.bio.length) <= 500)
        submit(ProfileChange(normalized))
    }
    suspend fun reset(version: Int) = gate.withLock {
        require(version >= 0)
        submit(ProfileChange(UserProfile(version = version), reset = true))
    }
    suspend fun retry() = gate.withLock { pending()?.let { submit(it) }; Unit }
    suspend fun reload(): UserProfile = gate.withLock {
        val abandoned = pending()
        val value = remote.profile()
        db.withTransaction {
            store(value)
            if (pending() == abandoned) dao.clearKind(scope, "profile_write")
        }
        value
    }
    private suspend fun submit(change: ProfileChange) {
        db.withTransaction {
            check(pending().let { it == null || it == change })
            dao.put(listOf(Snapshot(scope, "profile_write", "pending", JsonCodec.encode(change).toString(), "")))
        }
        val saved = if (change.reset) remote.resetProfile(change.value.version) else remote.saveProfile(change.value)
        if (saved != change.value.copy(version = change.value.version + 1)) throw BackendFailure(FailureKind.PROTOCOL)
        db.withTransaction {
            store(saved)
            if (pending() == change) dao.clearKind(scope, "profile_write")
        }
    }
}
