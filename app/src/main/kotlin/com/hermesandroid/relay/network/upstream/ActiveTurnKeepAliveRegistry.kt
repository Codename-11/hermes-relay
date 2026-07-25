package com.hermesandroid.relay.network.upstream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local ownership of background protection for work the user already
 * started. Keys are connection/profile/session scoped, so detached sibling
 * sessions retain independent leases and one completion cannot release
 * another session's protection.
 */
object ActiveTurnKeepAliveRegistry {
    data class Snapshot(
        val activeTurnCount: Int = 0,
        val waitingSessionCount: Int = 0,
    ) {
        val required: Boolean get() = activeTurnCount > 0
    }

    private val lock = Any()
    private val leases = linkedMapOf<String, Boolean>()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun acquire(key: String) {
        synchronized(lock) {
            leases[key] = leases[key] ?: false
            publishLocked()
        }
    }

    fun setWaiting(key: String, waiting: Boolean) {
        synchronized(lock) {
            if (key in leases) {
                leases[key] = waiting
                publishLocked()
            }
        }
    }

    fun rename(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        synchronized(lock) {
            val waiting = leases.remove(oldKey) ?: return
            leases[newKey] = waiting
            publishLocked()
        }
    }

    fun release(key: String) {
        synchronized(lock) {
            if (leases.remove(key) != null) publishLocked()
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            if (leases.isNotEmpty()) {
                leases.clear()
                publishLocked()
            }
        }
    }

    internal fun resetForTest() {
        releaseAll()
    }

    private fun publishLocked() {
        _snapshot.value = Snapshot(
            activeTurnCount = leases.size,
            waitingSessionCount = leases.count { it.value },
        )
    }
}
