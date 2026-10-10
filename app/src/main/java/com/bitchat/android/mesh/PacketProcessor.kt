package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.RoutedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import com.bitchat.android.util.AppConstants
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Processes incoming packets and routes them to appropriate handlers
 * 
 * Per-peer packet serialization using Kotlin coroutine actors
 * Prevents race condition where multiple threads process packets
 * from the same peer simultaneously, causing session management conflicts.
 */
class PacketProcessor(
    private val myPeerID: String,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
    companion object {
        private const val TAG = "PacketProcessor"
        private const val DROP_LOG_INTERVAL_MS = 5_000L
        /** A single packet occupying a stripe longer than this is logged (type only, no IDs). */
        internal const val STRIPE_WATCHDOG_MS = 2_000L
        private const val WATCHDOG_TICK_MS = 1_000L
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Helper function to format peer ID with nickname for logging
    private fun formatPeerForLog(peerID: String): String {
        val nickname = delegate?.getPeerNickname(peerID)
        return if (nickname != null) "$peerID ($nickname)" else peerID
    }
    
    // Packet relay manager for centralized relay decisions
    private val packetRelayManager = PacketRelayManager(myPeerID)
    
    // Coroutines
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // A queued packet remembers its link key so per-stripe link occupancy is released exactly once
    // (on dequeue, DROP_OLDEST eviction, cancel, or failed send).
    private class Queued(val routed: RoutedPacket, val linkKey: String?, val stripe: Int) {
        val released = AtomicBoolean(false)
    }

    // Fixed pool of bounded stripes. Each stripe has exactly one consumer, so packets that hash to
    // the same stripe (same peerID) are processed sequentially in arrival order. peerID is an
    // unauthenticated header field, so memory must never scale with the number of distinct peerIDs.
    private val stripes: List<Channel<Queued>> = List(AppConstants.Mesh.STRIPES) {
        Channel(
            capacity = AppConstants.Mesh.STRIPE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { q ->
                releaseSlot(q)
                recordDrop("stripe overflow")
            }
        )
    }
    private val stripeJobs: List<Job>
    private val watchdogJob: Job

    // Per-stripe in-flight packet, read by the watchdog. Races with the worker only affect logging.
    private class StripeActivity {
        @Volatile var startedNanos = 0L
        @Volatile var type: String = ""
        @Volatile var stallLogged = false
    }
    private val stripeActivity = List(AppConstants.Mesh.STRIPES) { StripeActivity() }

    // Per-stripe queued-packet count per link key; entries removed at 0, so bounded by queue capacity.
    // Each map is guarded by its own monitor.
    private val stripeLinkCounts: List<HashMap<String, Int>> = List(AppConstants.Mesh.STRIPES) { HashMap() }

    @Volatile private var closed = false
    private val droppedPackets = AtomicLong(0)
    private val lastDropLogMs = AtomicLong(0)

    // Token buckets. Per-link key = ingressLinkID ?: relayAddress, LRU-bounded; plus one process-wide
    // bucket. All guarded by linkBuckets' monitor. Reconnects get a fresh link key (fresh bucket), so
    // new buckets start small and the global bucket caps the aggregate.
    private class Bucket(var tokens: Double, var lastNanos: Long)
    private val linkBuckets = object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>?): Boolean =
            size > AppConstants.Mesh.MAX_LINK_BUCKETS
    }
    private val globalBucket = Bucket(AppConstants.Mesh.GLOBAL_BURST.toDouble(), clockNanos())

    internal val droppedPacketCount: Long get() = droppedPackets.get()
    internal val stripeCount: Int get() = stripes.size
    internal val linkBucketCount: Int get() = synchronized(linkBuckets) { linkBuckets.size }

    init {
        // Set up the packet relay manager delegate immediately
        setupRelayManager()
        stripeJobs = stripes.mapIndexed { index, channel ->
            val activity = stripeActivity[index]
            processorScope.launch {
                for (queued in channel) {
                    releaseSlot(queued)
                    val started = System.nanoTime()
                    activity.type = packetTypeName(queued.routed.packet.type)
                    activity.stallLogged = false
                    activity.startedNanos = started
                    try {
                        handleReceivedPacket(queued.routed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Packet handling failed: ${e.message}")
                    } finally {
                        activity.startedNanos = 0L
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                        if (elapsedMs > STRIPE_WATCHDOG_MS) {
                            Log.w(TAG, "Slow packet on stripe: type=${activity.type} took ${elapsedMs}ms")
                        }
                    }
                }
            }
        }
        // Logs a packet that is still stuck on a stripe, so a permanent stall leaves evidence too.
        watchdogJob = processorScope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val now = System.nanoTime()
                stripeActivity.forEach { activity ->
                    val started = activity.startedNanos
                    if (started != 0L && !activity.stallLogged) {
                        val elapsedMs = (now - started) / 1_000_000L
                        if (elapsedMs > STRIPE_WATCHDOG_MS) {
                            activity.stallLogged = true
                            Log.w(TAG, "Stripe stalled: type=${activity.type} running for ${elapsedMs}ms")
                        }
                    }
                }
            }
        }
    }

    private fun packetTypeName(type: UByte): String =
        MessageType.fromValue(type)?.name ?: "0x" + type.toString(16)

    private fun recordDrop(reason: String) {
        if (closed) return
        droppedPackets.incrementAndGet()
        val now = System.currentTimeMillis()
        val last = lastDropLogMs.get()
        if (now - last >= DROP_LOG_INTERVAL_MS && lastDropLogMs.compareAndSet(last, now)) {
            Log.d(TAG, "Dropping packets ($reason); total dropped=${droppedPackets.get()}")
        }
    }

    private fun releaseSlot(q: Queued) {
        val key = q.linkKey ?: return
        if (!q.released.compareAndSet(false, true)) return
        val counts = stripeLinkCounts[q.stripe]
        synchronized(counts) {
            val n = (counts[key] ?: return) - 1
            if (n <= 0) counts.remove(key) else counts[key] = n
        }
    }

    /** Takes a slot if the link is under its per-stripe quota. */
    private fun acquireSlot(stripe: Int, key: String): Boolean {
        val counts = stripeLinkCounts[stripe]
        synchronized(counts) {
            val n = counts[key] ?: 0
            if (n >= AppConstants.Mesh.STRIPE_LINK_QUOTA) return false
            counts[key] = n + 1
            return true
        }
    }

    private fun refill(b: Bucket, now: Long, burst: Double, ratePerSec: Int) {
        // Clamp so a backwards clock can neither add negative tokens nor explode on recovery.
        val elapsed = (now - b.lastNanos).coerceAtLeast(0L)
        b.tokens = minOf(burst, b.tokens + elapsed / 1e9 * ratePerSec)
        b.lastNanos = now
    }

    /** Per-link then global token bucket. Returns null if admitted, else the drop reason. */
    private fun admit(linkKey: String): String? {
        val now = clockNanos()
        synchronized(linkBuckets) {
            val bucket = linkBuckets.getOrPut(linkKey) {
                Bucket(AppConstants.Mesh.LINK_INITIAL_TOKENS.toDouble(), now)
            }
            refill(bucket, now, AppConstants.Mesh.LINK_BURST.toDouble(), AppConstants.Mesh.LINK_RATE_PER_SEC)
            if (bucket.tokens < 1.0) return "link rate limit"
            refill(globalBucket, now, AppConstants.Mesh.GLOBAL_BURST.toDouble(), AppConstants.Mesh.GLOBAL_RATE_PER_SEC)
            if (globalBucket.tokens < 1.0) return "global rate limit"
            bucket.tokens -= 1.0
            globalBucket.tokens -= 1.0
            return null
        }
    }

    /**
     * Process received packet - main entry point for all incoming packets.
     * Rate-limits per physical link, then enqueues synchronously onto a bounded stripe
     * (drop-oldest) so per-peer arrival order is preserved.
     */
    fun processPacket(routed: RoutedPacket) {
        val peerID = routed.peerID

        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping")
            return
        }

        // Link identity comes from the transport, never from packet contents. No key => local injection.
        val linkKey = routed.ingressLinkID ?: routed.relayAddress
        if (linkKey != null) {
            val reason = admit(linkKey)
            if (reason != null) {
                recordDrop(reason)
                return
            }
        }

        val index = (peerID.hashCode() and Int.MAX_VALUE) % stripes.size
        // A link cannot occupy more than its quota of one stripe, so a peerID-forging flood on one
        // link cannot evict another link's queued packets via DROP_OLDEST.
        if (linkKey != null && !acquireSlot(index, linkKey)) {
            recordDrop("link stripe quota")
            return
        }
        val queued = Queued(routed, linkKey, index)
        if (stripes[index].trySend(queued).isFailure) {
            releaseSlot(queued)
            recordDrop("stripe closed")
        }
    }
    
    /**
     * Set up the packet relay manager with its delegate
     */
    fun setupRelayManager() {
        packetRelayManager.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int {
                return delegate?.getNetworkSize() ?: 1
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return delegate?.getBroadcastRecipient() ?: ByteArray(0)
            }
            
            override fun broadcastPacket(routed: RoutedPacket) {
                delegate?.relayPacket(routed)
            }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return delegate?.sendToPeer(peerID, routed) ?: false
            }
        }
    }
    
    /**
     * Handle received packet - core protocol logic (exact same as iOS)
     */
    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Basic validation and security checks
        if (!delegate?.validatePacketSecurity(packet, peerID)!!) {
            return
        }

        var validPacket = true
        val messageType = MessageType.fromValue(packet.type)
        // Verbose logging to debug manager (and chat via ChatViewModel observer)
        try {
            val mt = messageType?.name ?: packet.type.toString()
            val routeDevice = routed.relayAddress
            val nick = delegate?.getPeerNickname(peerID)
            debugManager?.logIncomingPacket(peerID, nick, mt, routeDevice)
        } catch (_: Exception) { }
        
        
        // Handle public packet types (no address check needed)
        when (messageType) {
            MessageType.ANNOUNCE -> validPacket = handleAnnounce(routed)
            MessageType.MESSAGE -> handleMessage(routed)
            MessageType.FILE_TRANSFER -> handleMessage(routed) // treat same routing path; parsing happens in handler
            MessageType.VOICE_FRAME -> validPacket = delegate?.handleVoiceFrame(routed) ?: false
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT -> handleFragment(routed)
            MessageType.REQUEST_SYNC -> handleRequestSync(routed)
            else -> {
                // Handle private packet types (address check required)
                if (packetRelayManager.isPacketAddressedToMe(packet)) {
                    when (messageType) {
                        MessageType.NOISE_HANDSHAKE -> validPacket = handleNoiseHandshake(routed)
                        MessageType.NOISE_ENCRYPTED -> validPacket = handleNoiseEncrypted(routed)
                        MessageType.FILE_TRANSFER -> handleMessage(routed)
                        else -> {
                            validPacket = false
                            Log.w(TAG, "Unknown message type: ${packet.type}")
                        }
                    }
                } else {
                    // Not addressed to us; only relay handling below applies
                }
            }
        }
        
        // Update last seen timestamp
        if (validPacket) {
            delegate?.updatePeerLastSeen(peerID)
            
            // CENTRALIZED RELAY LOGIC: Handle relay decisions for all packets not addressed to us
            packetRelayManager.handlePacketRelay(routed)
        }
    }
    
    /**
     * Handle Noise handshake message - SIMPLIFIED iOS-compatible version
     */
    private suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        return delegate?.handleNoiseHandshake(routed) ?: false
    }
    
    /**
     * Handle Noise encrypted transport message
     * Returns false when decryption fails so undecryptable packets do not prove liveness.
     */
    private suspend fun handleNoiseEncrypted(routed: RoutedPacket): Boolean {
        return delegate?.handleNoiseEncrypted(routed) ?: false
    }
    
    /**
     * Handle announce message
     */
    private suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        return delegate?.handleAnnounce(routed) ?: false
    }
    
    /**
     * Handle regular message
     */
    private suspend fun handleMessage(routed: RoutedPacket) {
        delegate?.handleMessage(routed)
    }
    
    /**
     * Handle leave message
     */
    private suspend fun handleLeave(routed: RoutedPacket) {
        delegate?.handleLeave(routed)
    }
    
    /**
     * Handle message fragments
     */
    private suspend fun handleFragment(routed: RoutedPacket) {
        val reassembledPacket = delegate?.handleFragment(routed.packet)
        if (reassembledPacket != null) {
            // Intentionally bypasses the ingress rate limit/queue: every fragment already paid the
            // per-link and global limits in processPacket.
            handleReceivedPacket(
                RoutedPacket(
                    packet = reassembledPacket,
                    peerID = routed.peerID,
                    relayAddress = routed.relayAddress,
                    ingressLinkID = routed.ingressLinkID
                )
            )
        }
        
        // Fragment relay is now handled by centralized PacketRelayManager
    }

    /**
     * Handle REQUEST_SYNC packets (public, TTL=1)
     */
    private suspend fun handleRequestSync(routed: RoutedPacket) {
        delegate?.handleRequestSync(routed)
    }
    
    /**
     * Handle delivery acknowledgment
     */
//    private suspend fun handleDeliveryAck(routed: RoutedPacket) {
//        val peerID = routed.peerID ?: "unknown"
//        Log.d(TAG, "Processing delivery ACK from ${formatPeerForLog(peerID)}")
//        delegate?.handleDeliveryAck(routed)
//    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            appendLine("Stripes: ${stripes.size}, dropped packets: ${droppedPackets.get()}")
            appendLine("Link buckets: $linkBucketCount")
            appendLine("My Peer ID: $myPeerID")
        }
    }
    
    /**
     * Shutdown the processor and all peer actors
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketProcessor and ${stripes.size} stripes")

        closed = true
        stripes.forEach { it.cancel() } // discards buffered packets; recordDrop is a no-op once closed
        stripeJobs.forEach { it.cancel() }
        watchdogJob.cancel()
        
        // Shutdown the relay manager
        packetRelayManager.shutdown()
        
        // Cancel the main scope
        processorScope.cancel()
    }
}

/**
 * Delegate interface for packet processor callbacks
 */
interface PacketProcessorDelegate {
    // Security validation
    fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean
    
    // Peer management
    fun updatePeerLastSeen(peerID: String)
    fun getPeerNickname(peerID: String): String?
    
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Message type handlers
    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean
    suspend fun handleNoiseEncrypted(routed: RoutedPacket): Boolean
    suspend fun handleAnnounce(routed: RoutedPacket): Boolean
    fun handleMessage(routed: RoutedPacket)
    fun handleVoiceFrame(routed: RoutedPacket): Boolean = false
    fun handleLeave(routed: RoutedPacket)
    fun handleFragment(packet: BitchatPacket): BitchatPacket?
    fun handleRequestSync(routed: RoutedPacket)
    
    // Communication
    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}
