package dev.straza.approver.push

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.straza.approver.shared.net.PushKind
import dev.straza.approver.shared.push.PushConsent
import dev.straza.approver.shared.push.PushRoute
import dev.straza.approver.shared.push.PushRouteStatus
import dev.straza.approver.shared.push.PushTransportPref
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the push state: transport preference, chosen distributor, the
 * route the server currently holds (needed to DELETE the old route on
 * rotation), the last registration status, and when a push last arrived.
 *
 * Stored as Keystore-wrapped AES-GCM under its own key alias and file. A
 * UnifiedPush endpoint is a capability URL and so is an FCM token, though a
 * leak buys spurious wake-ups at worst. An unreadable record reads as
 * defaults. All access is synchronized.
 */
class PushRouteStore(context: Context) {

    data class Record(
        /**
         * The push opt-in answer. A record written before the opt-in existed
         * reads back as UNDECIDED, so an updated install is asked once.
         */
        val consent: PushConsent = PushConsent.UNDECIDED,
        val pref: PushTransportPref = PushTransportPref.AUTO,
        val distributorId: String? = null,
        val route: PushRoute? = null,
        /**
         * A fallback-distributor endpoint registered while the primary was
         * down. Held in its own slot so it can be deleted when the primary
         * returns without becoming [route].
         */
        val temporaryRoute: PushRoute? = null,
        val status: PushRouteStatus = PushRouteStatus.None,
        val lastDeliveryEpochSeconds: Long? = null,
    )

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun read(): Record = synchronized(LOCK) { readLocked() }

    fun write(record: Record) = synchronized(LOCK) { writeLocked(record) }

    fun stampDelivery(epochSeconds: Long) = synchronized(LOCK) {
        writeLocked(readLocked().copy(lastDeliveryEpochSeconds = epochSeconds))
    }

    /**
     * The distributor revoked the registration while no controller was alive
     * to DELETE the server route. Drops the stored route and the temporary
     * slot; the server prunes the dead route on its first failed delivery.
     */
    fun noteUnifiedPushGone() = synchronized(LOCK) {
        val record = readLocked()
        val dropPrimary = record.route?.kind == PushKind.UNIFIEDPUSH
        if (dropPrimary || record.temporaryRoute != null) {
            writeLocked(
                record.copy(
                    route = if (dropPrimary) null else record.route,
                    temporaryRoute = null,
                    // Another transport's standing route keeps its status.
                    status = if (dropPrimary) PushRouteStatus.None else record.status,
                ),
            )
        }
    }

    /**
     * A distributor REGISTER failed while no controller was up. Recorded only
     * when no route is standing: a failed re-attempt beside a live
     * registration must not mark a working phone as unreachable.
     */
    fun noteRegistrationFailed(why: String) = synchronized(LOCK) {
        val record = readLocked()
        if (record.route == null) {
            writeLocked(record.copy(status = PushRouteStatus.Unreached(why)))
        }
    }

    /**
     * The active pairing is going away. Drops routes and status but keeps the
     * transport preference, which belongs to the device, not to one pairing.
     */
    fun clearRoute() = synchronized(LOCK) {
        writeLocked(
            readLocked().copy(route = null, temporaryRoute = null, status = PushRouteStatus.None),
        )
    }

    /** Full teardown on unenrollment. A new pairing starts from AUTO. */
    fun clear() = synchronized(LOCK) {
        file.delete()
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // Already gone.
        }
    }

    // Persistence

    private fun readLocked(): Record {
        val bytes = readBytes() ?: return Record()
        return try {
            val obj = JSONObject(bytes.decodeToString())
            val route = routeOf(obj, K_ROUTE_KIND, K_ROUTE_VALUE, K_ROUTE_P256DH, K_ROUTE_AUTH)
            Record(
                consent = PushConsent.entries.firstOrNull { it.name == obj.optString(K_CONSENT) }
                    ?: PushConsent.UNDECIDED,
                pref = PushTransportPref.entries.firstOrNull { it.name == obj.optString(K_PREF) }
                    ?: PushTransportPref.AUTO,
                distributorId = obj.optString(K_DISTRIBUTOR, "").ifEmpty { null },
                route = route,
                temporaryRoute = routeOf(obj, K_TEMP_KIND, K_TEMP_VALUE, K_TEMP_P256DH, K_TEMP_AUTH),
                status = statusOf(obj, route),
                lastDeliveryEpochSeconds = obj.optLong(K_LAST_DELIVERY, -1L).takeIf { it > 0 },
            )
        } catch (_: Exception) {
            Record()
        }
    }

    /**
     * One persisted route, or null. Half a key set (a truncated write) degrades
     * to keyless, since PushRoute requires both keys or neither.
     */
    private fun routeOf(
        obj: JSONObject,
        kindKey: String,
        valueKey: String,
        p256dhKey: String,
        authKey: String,
    ): PushRoute? {
        val kind = PushKind.entries.firstOrNull { it.wire == obj.optString(kindKey, "") } ?: return null
        val value = obj.optString(valueKey, "").ifEmpty { return null }
        val p256dh = obj.optString(p256dhKey, "").ifEmpty { null }
        val auth = obj.optString(authKey, "").ifEmpty { null }
        return if (p256dh != null && auth != null) {
            PushRoute(kind, value, p256dh, auth)
        } else {
            PushRoute(kind, value)
        }
    }

    private fun putRoute(
        obj: JSONObject,
        route: PushRoute,
        kindKey: String,
        valueKey: String,
        p256dhKey: String,
        authKey: String,
    ) {
        obj.put(kindKey, route.kind.wire)
        obj.put(valueKey, route.tokenOrEndpoint)
        route.p256dh?.let { obj.put(p256dhKey, it) }
        route.auth?.let { obj.put(authKey, it) }
    }

    private fun writeLocked(record: Record) {
        val obj = JSONObject()
        obj.put(K_CONSENT, record.consent.name)
        obj.put(K_PREF, record.pref.name)
        record.distributorId?.let { obj.put(K_DISTRIBUTOR, it) }
        record.route?.let { putRoute(obj, it, K_ROUTE_KIND, K_ROUTE_VALUE, K_ROUTE_P256DH, K_ROUTE_AUTH) }
        record.temporaryRoute?.let { putRoute(obj, it, K_TEMP_KIND, K_TEMP_VALUE, K_TEMP_P256DH, K_TEMP_AUTH) }
        when (val s = record.status) {
            PushRouteStatus.None -> obj.put(K_STATUS, S_NONE)
            is PushRouteStatus.Registered -> obj.put(K_STATUS, S_REGISTERED)
            is PushRouteStatus.Rejected -> {
                obj.put(K_STATUS, S_REJECTED)
                obj.put(K_STATUS_TEXT, s.serverMessage)
            }
            is PushRouteStatus.Unreached -> {
                obj.put(K_STATUS, S_UNREACHED)
                obj.put(K_STATUS_TEXT, s.why)
            }
            is PushRouteStatus.Unavailable -> {
                obj.put(K_STATUS, S_UNAVAILABLE)
                obj.put(K_STATUS_TEXT, s.why)
            }
        }
        record.lastDeliveryEpochSeconds?.let { obj.put(K_LAST_DELIVERY, it) }
        writeBytes(obj.toString().encodeToByteArray())
    }

    private fun statusOf(obj: JSONObject, route: PushRoute?): PushRouteStatus =
        when (obj.optString(K_STATUS, S_NONE)) {
            S_REGISTERED -> route?.let { PushRouteStatus.Registered(it) } ?: PushRouteStatus.None
            S_REJECTED -> route?.let { PushRouteStatus.Rejected(it, obj.optString(K_STATUS_TEXT, "rejected")) }
                ?: PushRouteStatus.None
            S_UNREACHED -> PushRouteStatus.Unreached(obj.optString(K_STATUS_TEXT, "not registered"))
            // Re-evaluated on the next foreground, but reading it back keeps
            // the settings screen correct after a cold start.
            S_UNAVAILABLE -> PushRouteStatus.Unavailable(
                obj.optString(K_STATUS_TEXT, "push unavailable on this build"),
            )
            else -> PushRouteStatus.None
        }

    // Crypto: the enrollment store's pattern, with its own alias and file

    private fun readBytes(): ByteArray? {
        if (!file.exists()) return null
        return try {
            val blob = file.readBytes()
            if (blob.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    existingKey() ?: return null,
                    GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
                )
            }
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeBytes(bytes: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        }
        val ciphertext = cipher.doFinal(bytes)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeBytes(cipher.iv + ciphertext)
        check(temp.renameTo(file)) { "could not persist the push route record" }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = try {
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (_: Exception) {
        null
    }

    private fun createKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    private companion object {
        val LOCK = Any()
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.straza.approver.pushroute.v1"
        const val FILE_NAME = "push-route.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        const val K_CONSENT = "consent"
        const val K_PREF = "pref"
        const val K_DISTRIBUTOR = "distributor"
        const val K_ROUTE_KIND = "route_kind"
        const val K_ROUTE_VALUE = "route_value"
        const val K_ROUTE_P256DH = "route_p256dh"
        const val K_ROUTE_AUTH = "route_auth"
        const val K_TEMP_KIND = "temp_kind"
        const val K_TEMP_VALUE = "temp_value"
        const val K_TEMP_P256DH = "temp_p256dh"
        const val K_TEMP_AUTH = "temp_auth"
        const val K_STATUS = "status"
        const val K_STATUS_TEXT = "status_text"
        const val K_LAST_DELIVERY = "last_delivery"

        const val S_NONE = "none"
        const val S_REGISTERED = "registered"
        const val S_REJECTED = "rejected"
        const val S_UNREACHED = "unreached"
        const val S_UNAVAILABLE = "unavailable"
    }
}
