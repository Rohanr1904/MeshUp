package com.bitchat.android.services

/** Outcome of admitting an incoming message. Only [REJECTED] must not be acknowledged. */
enum class IncomingAdmissionResult {
    /** Newly admitted to app state. */
    ADMITTED,

    /** Already held (admitted or stored earlier); no downstream effects, but safe to ack. */
    DUPLICATE,

    /** Not held (panic wipe, missing store, failed write); the sender should resend. */
    REJECTED;

    val acknowledgeable: Boolean get() = this != REJECTED
}
