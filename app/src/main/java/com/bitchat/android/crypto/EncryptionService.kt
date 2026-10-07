package com.bitchat.android.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.identity.IdentityHealth
import com.bitchat.android.identity.IdentityIssue
import com.bitchat.android.identity.IdentityIssueReason
import com.bitchat.android.identity.IdentityKeyKind
import com.bitchat.android.noise.NoiseEncryptionService
import com.bitchat.android.noise.NoiseHandshakeProcessingResult
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.edit

/**
 * Encryption service that now uses NoiseEncryptionService internally
 * Maintains the same public API for backward compatibility
 * 
 * This is the main interface for all encryption/decryption operations in bitchat.
 * It now uses the Noise protocol for secure transport encryption with proper session management.
 */
open class EncryptionService(private val context: Context) {
    
    companion object {
        private const val TAG = "EncryptionService"
        private const val ED25519_PRIVATE_KEY_PREF = "ed25519_signing_private_key"
        private const val OLD_PREFS_NAME = "bitchat_crypto"
        private const val SECURE_PREFS_NAME = "bitchat_crypto_secure"
        private const val BACKUP_SUFFIX = "_unreadable_backup"
        private const val BACKUP_TIME_SUFFIX = "_unreadable_backup_at"
    }
    
    // Core Noise encryption service
    private val noiseService: NoiseEncryptionService by lazy { NoiseEncryptionService(context) }
    
    // Session tracking for established connections
    private val establishedSessions = ConcurrentHashMap<String, String>() // peerID -> fingerprint
    
    // Ed25519 signing keys (separate from Noise static keys)
    private lateinit var ed25519PrivateKey: Ed25519PrivateKeyParameters
    private lateinit var ed25519PublicKey: Ed25519PublicKeyParameters
    
    // Callbacks for UI state updates
    var onSessionEstablished: ((String) -> Unit)? = null // peerID
    var onSessionLost: ((String) -> Unit)? = null // peerID
    var onHandshakeRequired: ((String) -> Unit)? = null // peerID
    private lateinit var prefs: SharedPreferences
    
    init {
        initialize()
    }

    private fun setUpEncryptedPrefs() {
        prefs = createSecurePrefs()
    }

    /** Overridable so tests can inject plain or failing preferences. */
    protected open fun createSecurePrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Overridable so tests can inject the legacy plaintext preferences. */
    protected open fun legacyPrefs(): SharedPreferences =
        context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Initialization logic moved to method to allow overriding in tests
     */
    protected open fun initialize() {
        setUpEncryptedPrefs()
        // Initialize or load Ed25519 signing keys
        val keyPair = loadOrCreateEd25519KeyPair()
        ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
        ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters
        
        Log.d(TAG, "✅ Ed25519 signing keys initialized")
        
        // Set up NoiseEncryptionService callbacks
        noiseService.onPeerAuthenticated = { peerID, fingerprint ->
            Log.d(TAG, "✅ Noise session established with $peerID, fingerprint: ${fingerprint.take(16)}...")
            establishedSessions[peerID] = fingerprint
            onSessionEstablished?.invoke(peerID)
        }
        
        noiseService.onHandshakeRequired = { peerID ->
            Log.d(TAG, "🤝 Handshake required for $peerID")
            onHandshakeRequired?.invoke(peerID)
        }
    }
    
    // MARK: - Public API (Maintains backward compatibility)
    
    /**
     * Get our static public key data (32 bytes for Noise)
     * This replaces the old 96-byte combined key format
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return noiseService.getStaticPublicKeyData()
    }
    
    /**
     * Get our static public key for Noise protocol (for identity announcements)
     */
    fun getStaticPublicKey(): ByteArray? {
        return noiseService.getStaticPublicKeyData()
    }
    
    /**
     * Get our signing public key for Ed25519 signatures (for identity announcements)
     */
    fun getSigningPublicKey(): ByteArray? {
        return ed25519PublicKey.encoded
    }
    
    /**
     * Sign data using our Ed25519 signing key (for identity announcements)
     */
    fun signData(data: ByteArray): ByteArray? {
        return try {
            val signer = Ed25519Signer()
            signer.init(true, ed25519PrivateKey)
            signer.update(data, 0, data.size)
            val signature = signer.generateSignature()
            Log.d(TAG, "✅ Generated Ed25519 signature (${signature.size} bytes)")
            signature
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to sign data with Ed25519: ${e.message}")
            null
        }
    }
    
    /**
     * Add peer's public key and start handshake if needed
     * For backward compatibility with old key exchange packets
     */
    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray) {
        Log.d(TAG, "Legacy addPeerPublicKey called for $peerID with ${publicKeyData.size} bytes")
        
        // If this is from old key exchange format, initiate new Noise handshake
        if (!hasEstablishedSession(peerID)) {
            Log.d(TAG, "No Noise session with $peerID, initiating handshake")
            initiateHandshake(peerID)
        }
    }
    
    /**
     * Get peer's identity key (fingerprint) for favorites
     */
    fun getPeerIdentityKey(peerID: String): ByteArray? {
        val fingerprint = getPeerFingerprint(peerID) ?: return null
        return fingerprint.toByteArray()
    }
    
    /**
     * Clear persistent identity (for panic mode)
     */
    fun clearPersistentIdentity() {
        runCatching { IdentityHealth.reset(context) } // never let this block the identity wipe
        noiseService.clearPersistentIdentity()
        establishedSessions.clear()

        // The legacy plaintext prefs must not survive a wipe: it would be re-imported as the identity.
        try {
            legacyPrefs().edit().clear().commit()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not clear legacy prefs: ${e.javaClass.simpleName}")
        }
        
        // Clear Ed25519 signing key from preferences
        try {
            prefs.edit {
                remove(ED25519_PRIVATE_KEY_PREF)
                remove(ED25519_PRIVATE_KEY_PREF + BACKUP_SUFFIX)
                remove(ED25519_PRIVATE_KEY_PREF + BACKUP_TIME_SUFFIX)
            }
            Log.d(TAG, "🗑️ Cleared Ed25519 signing keys from preferences")

            // Generate new keys immediately
            val keyPair = loadOrCreateEd25519KeyPair()
            ed25519PrivateKey = keyPair.private as Ed25519PrivateKeyParameters
            ed25519PublicKey = keyPair.public as Ed25519PublicKeyParameters
            Log.d(TAG, "✅ Rotated Ed25519 signing keys in memory")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear Ed25519 keys: ${e.message}")
        }
    }
    
    /**
     * Encrypt data for a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun encrypt(data: ByteArray, peerID: String): ByteArray {
        val encrypted = noiseService.encrypt(data, peerID)
        if (encrypted == null) {
            throw Exception("Failed to encrypt for $peerID")
        }
        return encrypted
    }

    @Throws(Exception::class)
    fun encryptForSession(
        data: ByteArray,
        peerID: String,
        expectedSession: AuthenticatedNoiseSession
    ): ByteArray = noiseService.encryptForSession(data, peerID, expectedSession)
    
    /**
     * Decrypt data from a specific peer using Noise transport encryption
     */
    @Throws(Exception::class)
    fun decrypt(data: ByteArray, peerID: String): ByteArray {
        val decrypted = noiseService.decrypt(data, peerID)
        if (decrypted == null) {
            throw Exception("Failed to decrypt from $peerID")
        }
        return decrypted
    }

    @Throws(Exception::class)
    fun decryptWithSession(data: ByteArray, peerID: String): NoiseDecryptionResult {
        return noiseService.decryptWithSession(data, peerID)
            ?: throw Exception("Failed generation-bound decryption from $peerID")
    }
    
    // MARK: - Noise Protocol Interface
    
    /**
     * Check if we have an established Noise session with a peer
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return noiseService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState {
        return noiseService.getSessionState(peerID)
    }
    
    /**
     * Get encryption icon state for UI
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return hasEstablishedSession(peerID)
    }
    
    /**
     * Get peer fingerprint for favorites/blocking
     */
    fun getPeerFingerprint(peerID: String): String? {
        return noiseService.getPeerFingerprint(peerID)
    }

    /**
     * Return the remote static key authenticated by the live Noise handshake.
     * This deliberately bypasses announcement and PeerFingerprintManager
     * caches; callers making downgrade decisions must bind to live channel
     * authentication, not a self-certified identity payload.
     */
    fun getAuthenticatedRemoteStaticKey(peerID: String): ByteArray? {
        return getAuthenticatedSession(peerID)?.remoteStaticKey?.copyOf()
    }

    fun getAuthenticatedSession(peerID: String): AuthenticatedNoiseSession? =
        noiseService.getAuthenticatedSession(peerID)

    fun withAuthenticatedSession(
        peerID: String,
        expectedSession: AuthenticatedNoiseSession,
        action: () -> Boolean
    ): Boolean = noiseService.withAuthenticatedSession(peerID, expectedSession, action)
    
    /**
     * Get current peer ID for a fingerprint (for peer ID rotation)
     */
    fun getCurrentPeerID(fingerprint: String): String? {
        return noiseService.getPeerID(fingerprint)
    }
    
    /**
     * Initiate a Noise handshake with a peer
     */
    fun initiateHandshake(peerID: String, replaceEstablished: Boolean = false): ByteArray? {
        Log.d(TAG, "🤝 Initiating Noise handshake with $peerID")
        return noiseService.initiateHandshake(peerID, replaceEstablished)
    }
    
    /**
     * Process an incoming handshake message
     */
    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray? {
        Log.d(TAG, "🤝 Processing handshake message from $peerID")
        return noiseService.processHandshakeMessage(data, peerID)
    }

    /**
     * Process one Noise handshake frame while preserving whether this exact call authenticated a
     * new session. Unlike the response-only compatibility API, binding failures are propagated.
     */
    @Throws(Exception::class)
    open fun processHandshakeMessageWithResult(
        data: ByteArray,
        peerID: String
    ): NoiseHandshakeProcessingResult {
        Log.d(TAG, "🤝 Processing typed handshake message from $peerID")
        return noiseService.processHandshakeMessageWithResult(data, peerID)
    }
    
    /**
     * Remove a peer session (called when peer disconnects)
     */
    open fun removePeer(peerID: String) {
        establishedSessions.remove(peerID)
        noiseService.removePeer(peerID)
        onSessionLost?.invoke(peerID)
        Log.d(TAG, "🗑️ Removed session for $peerID")
    }
    
    /**
     * Update peer ID mapping (for peer ID rotation)
     */
    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String) {
        oldPeerID?.let { establishedSessions.remove(it) }
        establishedSessions[newPeerID] = fingerprint
        noiseService.updatePeerIDMapping(oldPeerID, newPeerID, fingerprint)
    }
    
    // MARK: - Channel Encryption
    
    /**
     * Set password for a channel (derives encryption key using Argon2id)
     */
    fun setChannelPassword(password: String, channel: String) {
        noiseService.setChannelPassword(password, channel)
    }
    
    /**
     * Encrypt message for a password-protected channel
     */
    fun encryptChannelMessage(message: String, channel: String): ByteArray? {
        return noiseService.encryptChannelMessage(message, channel)
    }
    
    /**
     * Decrypt channel message
     */
    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String? {
        return noiseService.decryptChannelMessage(encryptedData, channel)
    }
    
    /**
     * Remove channel password (when leaving channel)
     */
    fun removeChannelPassword(channel: String) {
        noiseService.removeChannelPassword(channel)
    }
    
    // MARK: - Session Management
    
    /**
     * Get all peers with established sessions
     */
    fun getEstablishedPeers(): List<String> {
        return establishedSessions.keys.toList()
    }
    
    /**
     * Get sessions that need rekeying
     */
    fun getSessionsNeedingRekey(): List<String> {
        return noiseService.getSessionsNeedingRekey()
    }
    
    /**
     * Initiate rekey for a session
     */
    fun initiateRekey(peerID: String): ByteArray? {
        Log.d(TAG, "🔄 Initiating rekey for $peerID")
        establishedSessions.remove(peerID) // Will be re-added when new session is established
        return noiseService.initiateRekey(peerID)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return noiseService.getIdentityFingerprint()
    }
    
    /**
     * Get debug information about encryption state
     */
    fun getDebugInfo(): String = buildString {
        appendLine("=== EncryptionService Debug ===")
        appendLine("Established Sessions: ${establishedSessions.size}")
        appendLine("Our Fingerprint: ${getIdentityFingerprint().take(16)}...")
        
        if (establishedSessions.isNotEmpty()) {
            appendLine("Active Encrypted Sessions:")
            establishedSessions.forEach { (peerID, fingerprint) ->
                appendLine("  $peerID -> ${fingerprint.take(16)}...")
            }
        }
        
        appendLine("")
        appendLine(noiseService.toString()) // Include NoiseService state
    }
    
    /**
     * Shutdown encryption service
     */
    fun shutdown() {
        establishedSessions.clear()
        noiseService.shutdown()
        Log.d(TAG, "🔌 EncryptionService shut down")
    }
    
    // MARK: - Ed25519 Signature Verification
    
    /**
     * Verify Ed25519 signature against data using a public key
     */
    open fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return try {
            val publicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(data, 0, data.size)
            val isValid = verifier.verifySignature(signature)
            Log.d(TAG, "✅ Ed25519 signature verification: $isValid")
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to verify Ed25519 signature: ${e.message}")
            false
        }
    }
    
    // MARK: - Private Key Management
    
    /** Result of reading the encrypted Ed25519 pref. */
    private sealed class StoredRead {
        object Absent : StoredRead()
        class Raw(val value: String) : StoredRead()
        object Unreadable : StoredRead()
    }

    private fun readEncrypted(): StoredRead = try {
        prefs.getString(ED25519_PRIVATE_KEY_PREF, null)?.let { StoredRead.Raw(it) } ?: StoredRead.Absent
    } catch (e: Exception) {
        Log.w(TAG, "⚠️ Stored Ed25519 key could not be read: ${e.javaClass.simpleName}")
        StoredRead.Unreadable
    }

    private fun readLegacy(): String? = try {
        legacyPrefs().getString(ED25519_PRIVATE_KEY_PREF, null)
    } catch (e: Exception) {
        null
    }

    private fun deleteLegacy() {
        try {
            legacyPrefs().edit().remove(ED25519_PRIVATE_KEY_PREF).commit()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not remove legacy Ed25519 key: ${e.javaClass.simpleName}")
        }
    }

    private fun parseEd25519(encoded: String): AsymmetricCipherKeyPair? = try {
        val privateKey = Ed25519PrivateKeyParameters(Base64.decode(encoded, Base64.DEFAULT), 0)
        AsymmetricCipherKeyPair(privateKey.generatePublicKey(), privateKey)
    } catch (e: Exception) {
        null
    }

    /**
     * Load the Ed25519 key pair. Absent (first run, no legacy key) silently creates one. The legacy
     * plaintext copy is deleted only after an encrypted value has loaded (or been written from it), so a
     * readable legacy key can recover an unreadable encrypted value without any reset event. A stored
     * value that cannot be read is backed up unchanged and reported before it is replaced.
     */
    private fun loadOrCreateEd25519KeyPair(): AsymmetricCipherKeyPair {
        val enc = readEncrypted()
        (enc as? StoredRead.Raw)?.let { raw -> parseEd25519(raw.value) }?.let { pair ->
            Log.d(TAG, "✅ Loaded existing Ed25519 signing key pair")
            deleteLegacy()
            return pair
        }

        val legacyRaw = readLegacy()
        val legacyPair = legacyRaw?.let { parseEd25519(it) }
        if (legacyPair != null) {
            // Recover the identity from the legacy copy; keep any unreadable encrypted value as a backup.
            if (enc is StoredRead.Raw) backupUnreadableEd25519Key(enc.value)
            val saved = try {
                prefs.edit().putString(ED25519_PRIVATE_KEY_PREF, legacyRaw).commit()
            } catch (e: Exception) {
                false
            }
            if (saved) {
                Log.d(TAG, "🔁 Migrated Ed25519 key to EncryptedSharedPreferences")
                deleteLegacy()
            }
            return legacyPair
        }

        if (enc is StoredRead.Absent && legacyRaw == null) {
            return generateAndSaveEd25519(deleteLegacyOnSave = false).first
        }

        // Something is stored but unusable.
        val backedUp = backupUnreadableEd25519Key((enc as? StoredRead.Raw)?.value ?: legacyRaw)
        IdentityHealth.report(
            IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.ED25519_SIGNING, backedUp), context
        )
        return generateAndSaveEd25519(deleteLegacyOnSave = true).first
    }

    /**
     * Keeps the FIRST backup (never overwrites an existing one). Returns true if a backup exists or was
     * written; false if there was nothing readable to copy or the write failed. Never throws.
     */
    private fun backupUnreadableEd25519Key(raw: String?): Boolean {
        return try {
            if (prefs.contains(ED25519_PRIVATE_KEY_PREF + BACKUP_SUFFIX)) return true
            if (raw == null) return false
            prefs.edit()
                .putString(ED25519_PRIVATE_KEY_PREF + BACKUP_SUFFIX, raw)
                .putLong(ED25519_PRIVATE_KEY_PREF + BACKUP_TIME_SUFFIX, System.currentTimeMillis())
                .commit()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not back up unreadable Ed25519 key: ${e.javaClass.simpleName}")
            false
        }
    }

    fun generateAndSaveEd25519KeyPair(): AsymmetricCipherKeyPair =
        generateAndSaveEd25519(deleteLegacyOnSave = false).first

    private fun generateAndSaveEd25519(deleteLegacyOnSave: Boolean): Pair<AsymmetricCipherKeyPair, Boolean> {
        val keyGen = Ed25519KeyPairGenerator()
        keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyGen.generateKeyPair()

        var persisted = false
        try {
            val privateKey = keyPair.private as Ed25519PrivateKeyParameters
            val encodedKey = Base64.encodeToString(privateKey.encoded, Base64.DEFAULT)
            persisted = prefs.edit().putString(ED25519_PRIVATE_KEY_PREF, encodedKey).commit()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to store Ed25519 private key: ${e.javaClass.simpleName}")
        }
        if (persisted) {
            Log.d(TAG, "✅ Created and stored new Ed25519 signing key pair")
            // The encrypted pref now holds a key, so the plaintext copy must not linger.
            if (deleteLegacyOnSave) deleteLegacy()
        } else {
            // Keep using the in-memory key this session, but make the failure visible.
            IdentityHealth.report(
                IdentityIssue(IdentityIssueReason.NOT_PERSISTED, IdentityKeyKind.ED25519_SIGNING), context
            )
        }
        return Pair(keyPair, persisted)
    }
}
