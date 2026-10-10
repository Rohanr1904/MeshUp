package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.runBlocking

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

/**
 * Reflects an incoming transport message into process-wide state before any downstream effects.
 *
 * Private-message admission is authoritative: a duplicate or a message rejected while panic mode
 * is wiping state must not continue to UI delegates, unread tracking, haptics, or notifications.
 * Public and channel messages retain their existing best-effort behavior if state reflection fails.
 */
internal object IncomingMessageAdmission {
    fun admitToAppState(message: BitchatMessage): Boolean =
        admit(message) == IncomingAdmissionResult.ADMITTED

    fun admit(message: BitchatMessage): IncomingAdmissionResult = try {
        when {
            message.isPrivate -> {
                val peerID = message.senderPeerID?.takeIf(String::isNotBlank)
                    ?: return IncomingAdmissionResult.REJECTED
                // Mesh transport callbacks run on their background service workers. Wait for the
                // serialized SQLite transaction so a notification can never advertise a message
                // that an immediate process death would lose.
                runBlocking {
                    AppStateStore.admitPrivateMessageDurably(peerID, message)
                }
            }

            message.channel != null -> {
                AppStateStore.addChannelMessage(message.channel, message)
                IncomingAdmissionResult.ADMITTED
            }

            else -> {
                AppStateStore.addPublicMessage(message)
                IncomingAdmissionResult.ADMITTED
            }
        }
    } catch (_: Exception) {
        // Preserve the pre-existing best-effort dispatch for public/channel messages, but never
        // bypass private-message admission when persistence or canonicalization fails.
        if (message.isPrivate) IncomingAdmissionResult.REJECTED else IncomingAdmissionResult.ADMITTED
    }
}
