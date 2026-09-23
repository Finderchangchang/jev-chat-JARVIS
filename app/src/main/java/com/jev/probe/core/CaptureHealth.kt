package com.jev.probe.core

/** Process-local only: a killed process must never retain a healthy status. */
object CaptureHealth {
    enum class State { UNAUTHORIZED, DISCONNECTED, CONNECTED }
    private var owner: Any? = null
    var keepAliveFailure: String? = null
        private set

    @Synchronized fun connected(service: Any) { owner = service; keepAliveFailure = null }
    @Synchronized fun disconnected(service: Any) {
        if (owner === service) { owner = null; keepAliveFailure = null }
    }
    @Synchronized fun state(authorized: Boolean): State = when {
        !authorized -> State.UNAUTHORIZED
        owner == null -> State.DISCONNECTED
        else -> State.CONNECTED
    }
    @Synchronized fun keepAliveFailed(reason: String) { keepAliveFailure = reason }
    @Synchronized fun keepAliveStarted() { keepAliveFailure = null }
}
