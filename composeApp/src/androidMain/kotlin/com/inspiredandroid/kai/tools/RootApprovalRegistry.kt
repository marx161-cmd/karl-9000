package com.inspiredandroid.kai.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import kotlin.random.Random

data class RootApprovalRequest(
    val id: String,
    val createdAt: Instant,
    val reason: String,
    val command: String,
    val scope: String,
    val approved: Boolean = false,
    val denied: Boolean = false,
)

object RootApprovalRegistry {
    private val requests = mutableMapOf<String, RootApprovalRequest>()
    private val deniedUntilMs = mutableMapOf<String, Long>()
    private val _pending = MutableStateFlow<List<RootApprovalRequest>>(emptyList())
    val pending: StateFlow<List<RootApprovalRequest>> = _pending

    @Synchronized
    fun create(reason: String, command: String, scope: String): RootApprovalRequest {
        val request = RootApprovalRequest(
            id = randomId(),
            createdAt = Instant.now(),
            reason = reason,
            command = command,
            scope = scope,
        )
        requests[request.id] = request
        publish()
        return request
    }

    @Synchronized
    fun get(id: String): RootApprovalRequest? = requests[id]

    @Synchronized
    fun approve(id: String) {
        val request = requests[id] ?: return
        requests[id] = request.copy(approved = true, denied = false)
        publish()
    }

    @Synchronized
    fun deny(id: String) {
        val request = requests[id] ?: return
        deniedUntilMs[denyKey(request.reason, request.command, request.scope)] = System.currentTimeMillis() + DENY_COOLDOWN_MS
        requests[id] = request.copy(approved = false, denied = true)
        publish()
    }

    @Synchronized
    fun consume(id: String) {
        requests.remove(id)
        publish()
    }

    @Synchronized
    fun cooldownRemainingMs(reason: String, command: String, scope: String): Long {
        val key = denyKey(reason, command, scope)
        val until = deniedUntilMs[key] ?: return 0L
        val remaining = until - System.currentTimeMillis()
        if (remaining <= 0L) {
            deniedUntilMs.remove(key)
            return 0L
        }
        return remaining
    }

    private fun publish() {
        _pending.update {
            requests.values
                .filter { request -> !request.approved && !request.denied }
                .sortedBy { request -> request.createdAt }
        }
    }

    private fun denyKey(reason: String, command: String, scope: String): String = "$scope\n$reason\n$command"

    private fun randomId(): String = (1..16).joinToString("") { Random.nextInt(16).toString(16) }

    private const val DENY_COOLDOWN_MS = 5L * 60L * 1000L
}
