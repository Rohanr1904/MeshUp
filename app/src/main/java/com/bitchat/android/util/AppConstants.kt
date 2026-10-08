package com.bitchat.android.util

import java.util.UUID

/**
 * Centralized application-wide constants.
 */
object AppConstants {
    // Packet time-to-live (hops)
    val MESSAGE_TTL_HOPS: UByte = 7u     // Default TTL for regular packets
    val SYNC_TTL_HOPS: UByte = 0u        // TTL for neighbor-only sync packets

    object Mesh {
        // Peer lifecycle
        const val STALE_PEER_TIMEOUT_MS: Long = 180_000L // 3 minutes
        const val PEER_CLEANUP_INTERVAL_MS: Long = 60_000L
        const val PEER_DISCONNECT_GRACE_MS: Long = 10_000L

        // BLE connection tracking
        const val CONNECTION_RETRY_DELAY_MS: Long = 5_000L
        const val MAX_CONNECTION_ATTEMPTS: Int = 3
        const val CONNECTION_CLEANUP_DELAY_MS: Long = 500L
        const val CONNECTION_CLEANUP_INTERVAL_MS: Long = 30_000L
        const val BROADCAST_CLEANUP_DELAY_MS: Long = 500L

        // Inbound packet bounding (R-5.2). Deliberately generous so file/voice fragment bursts are
        // not throttled; to be tuned on physical devices (Decision 009).
        const val STRIPES: Int = 16
        const val STRIPE_CAPACITY: Int = 256
        const val LINK_RATE_PER_SEC: Int = 200
        const val LINK_BURST: Int = 400
        const val MAX_LINK_BUCKETS: Int = 256
        const val LINK_INITIAL_TOKENS: Int = 50 // new link (e.g. reconnect) starts small, refills to LINK_BURST
        const val STRIPE_LINK_QUOTA: Int = STRIPE_CAPACITY / 4 // max queued packets per link per stripe
        const val GLOBAL_RATE_PER_SEC: Int = 500 // process-wide cap; tune on devices as well
        const val GLOBAL_BURST: Int = 1000

        object Gatt {
            val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
            val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
            val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        }
    }

    object Sync {
        const val CLEANUP_INTERVAL_MS: Long = 60_000L
    }

    object Fragmentation {
        const val FRAGMENT_SIZE_THRESHOLD: Int = 512
        const val MAX_FRAGMENT_SIZE: Int = 469
        const val FRAGMENT_TIMEOUT_MS: Long = 30_000L
        const val CLEANUP_INTERVAL_MS: Long = 10_000L
        const val MAX_FRAGMENTS_PER_ID: Int = 256
        const val MAX_FRAGMENT_TOTAL_BYTES: Int = 1_048_576
        const val MAX_ACTIVE_FRAGMENT_SETS: Int = 64
        const val MAX_GLOBAL_FRAGMENT_TOTAL_BYTES: Long = 4L * 1_048_576L
    }

    object Security {
        const val MESSAGE_TIMEOUT_MS: Long = 300_000L
        const val CLEANUP_INTERVAL_MS: Long = 300_000L
        const val MAX_PROCESSED_MESSAGES: Int = 10_000
        const val MAX_PROCESSED_KEY_EXCHANGES: Int = 1_000
        const val KEY_EXCHANGE_DEDUP_TIMEOUT_MS: Long = 60_000L
    }

    object Noise {
        const val REKEY_TIME_LIMIT_MS: Long = 3_600_000L // 1 hour
        const val REKEY_MESSAGE_LIMIT_ENCRYPTION: Long = 1_000L // per session, encryption service policy
        const val REKEY_MESSAGE_LIMIT_SESSION: Long = 10_000L // session-level ceiling
        const val MAX_PAYLOAD_SIZE_BYTES: Int = 256
        const val HIGH_NONCE_WARNING_THRESHOLD: Long = 1_000_000_000L
    }

    object Verification {
        const val QR_MAX_AGE_SECONDS: Long = 300L // 5 minutes
    }

    object Protocol {
        const val COMPRESSION_THRESHOLD_BYTES: Int = 100
        const val MAX_PAYLOAD_LENGTH: Int = 10_485_760
    }

    object StoreForward {
        const val MESSAGE_CACHE_TIMEOUT_MS: Long = 43_200_000L // 12h
        const val MAX_CACHED_MESSAGES: Int = 100
        const val MAX_CACHED_MESSAGES_FAVORITES: Int = 1_000
        const val CLEANUP_INTERVAL_MS: Long = 600_000L
    }

    object Power {
        const val CRITICAL_BATTERY_PERCENT: Int = 10
        const val LOW_BATTERY_PERCENT: Int = 20
        const val MEDIUM_BATTERY_PERCENT: Int = 50
        const val SCAN_ON_DURATION_NORMAL_MS: Long = 8_000L
        const val SCAN_OFF_DURATION_NORMAL_MS: Long = 2_000L
        const val SCAN_ON_DURATION_POWER_SAVE_MS: Long = 2_000L
        const val SCAN_OFF_DURATION_POWER_SAVE_MS: Long = 28_000L
        const val SCAN_ON_DURATION_ULTRA_LOW_MS: Long = 1_000L
        const val SCAN_OFF_DURATION_ULTRA_LOW_MS: Long = 29_000L
        const val MAX_CONNECTIONS_NORMAL: Int = 8
        const val MAX_CONNECTIONS_POWER_SAVE: Int = 8
        const val MAX_CONNECTIONS_ULTRA_LOW: Int = 8
    }

    object Nostr {
        // Relay backoff
        const val INITIAL_BACKOFF_INTERVAL_MS: Long = 1_000L
        const val MAX_BACKOFF_INTERVAL_MS: Long = 300_000L
        const val BACKOFF_MULTIPLIER: Double = 2.0
        const val MAX_RECONNECT_ATTEMPTS: Int = 10

        // Transport
        const val READ_ACK_INTERVAL_MS: Long = 350L

        // Deduplicator
        const val DEFAULT_DEDUP_CAPACITY: Int = 10_000

        // Relay subscription validation
        const val SUBSCRIPTION_VALIDATION_INTERVAL_MS: Long = 30_000L
    }

    object Tor {
        const val DEFAULT_SOCKS_PORT: Int = 9060
        const val RESTART_DELAY_MS: Long = 2_000L
        const val INACTIVITY_TIMEOUT_MS: Long = 5_000L
        const val MAX_RETRY_ATTEMPTS: Int = 5
        const val STOP_TIMEOUT_MS: Long = 7_000L
    }

    object UI {
        const val MAX_NICKNAME_LENGTH: Int = 15
        const val BASE_FONT_SIZE_SP: Int = 14
        const val MESSAGE_DEDUP_TIMEOUT_MS: Long = 30_000L
        const val SYSTEM_EVENT_DEDUP_TIMEOUT_MS: Long = 5_000L
        const val ACTION_FORCE_FINISH: String = "com.bitchat.android.ACTION_FORCE_FINISH"
        const val PERMISSION_FORCE_FINISH: String = "com.bitchat.android.permission.FORCE_FINISH"
    }

    object Media {
        // A file is currently encoded into one protocol payload before BLE fragmentation.
        // Reserve room for maximum filename/MIME TLVs and encryption envelope overhead.
        const val MAX_FILE_SIZE_BYTES: Long = (10L * 1024 * 1024) - (132L * 1024)
    }

    object Router {
        const val OUTBOX_TICK_MS: Long = 2_000L
        // Legacy in-memory MessageRouter limits; the durable outbox uses OUTBOX_EXPIRY_MS (1 h)
        // and OUTBOX_PER_PEER_LIMIT (200) below (Decision 015).
        const val OUTBOX_MESSAGE_TTL_MS: Long = 86_400_000L // 24 hours; superseded by OUTBOX_EXPIRY_MS
        const val OUTBOX_MAX_PER_PEER: Int = 100 // superseded by OUTBOX_PER_PEER_LIMIT
        // Durable outbox (Decision 015): D1 expiry, D2 per-peer limit, D3 resend backoff.
        const val OUTBOX_EXPIRY_MS: Long = 60L * 60L * 1000L
        const val OUTBOX_PER_PEER_LIMIT: Int = 200
        const val OUTBOX_GLOBAL_LIMIT: Int = 2000
        val OUTBOX_RESEND_BACKOFF_MS: LongArray = longArrayOf(30_000L, 60_000L, 120_000L, 120_000L)
        val HANDSHAKE_RETRY_BACKOFF_MS: LongArray = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)
    }

    object Release {
        /** GitHub "owner/repo" slug hosting NearBird releases. Change here if the repo is renamed. */
        const val GITHUB_REPO: String = "Rohanr1904/MeshUp"
        /** Public source repository page (GPLv3 source offer, shown on the licences screen). */
        const val SOURCE_URL: String = "https://github.com/$GITHUB_REPO"
        const val GITHUB_API_LATEST_URL: String =
            "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        const val GITHUB_LATEST_DOWNLOAD_BASE: String =
            "https://github.com/$GITHUB_REPO/releases/latest/download/"
        /** Universal APK asset name produced by tools/reproducible-builds/sign-release.sh. */
        const val UNIVERSAL_APK_ASSET: String = "nearbird-universal.apk"
    }

    object Services {
        const val SEEN_MESSAGE_MAX_IDS: Int = 10_000
    }
}
