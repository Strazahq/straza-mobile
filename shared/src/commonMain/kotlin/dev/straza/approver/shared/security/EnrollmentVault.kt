package dev.straza.approver.shared.security

import dev.straza.approver.shared.net.FcmAppConfig
import dev.straza.approver.shared.protocol.SpkiPin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Multi-deployment enrollment state and its serialization. Everything here is
 * non-secret except the [Enrollment.deviceToken] each record carries. The
 * codec does not log, and callers keep the plaintext inside the encrypted store.
 */

/**
 * Stable key for a deployment: the server's `project.id` when it set one,
 * otherwise a value derived from the first server's host, so re-scanning the
 * same backend updates that deployment. Usable before an [Enrollment] exists
 * (from the scanned QR), so the signing key can be aliased by its deployment.
 */
fun projectKeyFor(projectId: String?, servers: List<String>): String =
    projectId?.takeIf { it.isNotBlank() }
        ?: "host:" + (servers.firstOrNull()?.let(::serverHost) ?: "unknown")

fun projectKey(enrollment: Enrollment): String =
    projectKeyFor(enrollment.projectId, enrollment.servers)

/** Label for a deployment: the local rename, else the server's name, the host, the device name. */
fun Enrollment.displayName(): String =
    localLabel?.takeIf { it.isNotBlank() }
        ?: projectName?.takeIf { it.isNotBlank() }
        ?: servers.firstOrNull()?.let(::serverHost)
        ?: deviceName

/**
 * Host of a URL, without a URL parser: parsers disagree across platforms, and
 * both platforms must derive the same deployment key. Strips scheme, path,
 * userinfo and port, and unwraps a bracketed IPv6 literal. Does not return
 * blank, since the value keys enrollments and hardware signing keys.
 */
internal fun serverHost(url: String): String {
    val authority = url.substringAfter("://", url).substringBefore('/')
    // Userinfo may contain ':' but no unencoded '@' (RFC 3986), so the host
    // begins after the last '@'.
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith('[')) {
        // A bracketed IPv6 literal owns every ':' inside it. The bare address
        // cannot collide with a hostname, because a DNS name contains no ':'.
        // A missing ']' degrades to the remainder.
        hostPort.substring(1).substringBefore(']')
    } else {
        hostPort.substringBefore(':')
    }
    return host.ifBlank { url }
}

/** An immutable set of enrollments, one per deployment, plus which one is active. */
data class EnrollmentVault(
    val deployments: List<Enrollment> = emptyList(),
    val activeProjectId: String? = null,
) {
    val isEmpty: Boolean get() = deployments.isEmpty()

    /** The active deployment, or the first as a fallback, or null when empty. */
    fun active(): Enrollment? =
        deployments.firstOrNull { projectKey(it) == activeProjectId }
            ?: deployments.firstOrNull()

    fun find(key: String): Enrollment? = deployments.firstOrNull { projectKey(it) == key }

    /** Adds the enrollment, or replaces the one with the same key. It becomes active. */
    fun upsert(enrollment: Enrollment): EnrollmentVault {
        val key = projectKey(enrollment)
        val others = deployments.filterNot { projectKey(it) == key }
        return copy(deployments = others + enrollment, activeProjectId = key)
    }

    /**
     * Replaces an existing record in place, keeping order and the active
     * choice. A key with no record is a no-op, so a token renewal that lands
     * after its deployment was retired does not resurrect it.
     */
    fun update(enrollment: Enrollment): EnrollmentVault {
        val key = projectKey(enrollment)
        if (deployments.none { projectKey(it) == key }) return this
        return copy(deployments = deployments.map { if (projectKey(it) == key) enrollment else it })
    }

    /** Drops a deployment. If it was active, the first remaining one becomes active. */
    fun remove(key: String): EnrollmentVault {
        val kept = deployments.filterNot { projectKey(it) == key }
        val active = if (activeProjectId == key) kept.firstOrNull()?.let(::projectKey) else activeProjectId
        return copy(deployments = kept, activeProjectId = active)
    }

    /** Switches the active deployment. A no-op if the key is unknown. */
    fun withActive(key: String): EnrollmentVault =
        if (deployments.any { projectKey(it) == key }) copy(activeProjectId = key) else this
}

/**
 * Encodes a vault to a string and back, and migrates the older single-record
 * format (a bare [Legacy] object) into a one-deployment vault. Anything
 * unreadable (corrupt bytes, an unknown version) decodes to an empty vault.
 * A record whose pin does not parse is dropped; the other deployments stay.
 */
object EnrollmentVaultCodec {
    const val VERSION = 2

    private val json = Json { ignoreUnknownKeys = true }

    // `v` has no default, so a legacy object (which has no `v`) fails to decode
    // as Doc and falls through to the migration path.
    @Serializable
    private data class Doc(val v: Int, val active: String? = null, val deployments: List<Rec> = emptyList())

    @Serializable
    private data class Rec(
        val approverDeviceId: String,
        val deviceToken: String,
        val servers: List<String>,
        val pin: String? = null,
        val deviceName: String,
        val projectId: String = "",
        val projectName: String? = null,
        val localLabel: String? = null,
        val keyRef: String = "",
        // The fields below default to null, so a vault written before them
        // still decodes and an older app reading a newer vault ignores them.
        val tokenExpiresAt: Long? = null,
        // WebPush identity and BYO-Firebase config from the enroll 201. Null
        // means the pairing has no such push lane.
        val vapidPublicKey: String? = null,
        val fcm: FcmAppConfig? = null,
    )

    /** The older single-record on-disk shape. */
    @Serializable
    private data class Legacy(
        val approverDeviceId: String,
        val deviceToken: String,
        val servers: List<String>,
        val pin: String? = null,
        val deviceName: String,
    )

    fun encode(vault: EnrollmentVault): String =
        json.encodeToString(
            Doc(
                v = VERSION,
                active = vault.activeProjectId,
                deployments = vault.deployments.map { it.toRec() },
            ),
        )

    fun decode(text: String?): EnrollmentVault {
        if (text.isNullOrBlank()) return EnrollmentVault()

        runCatching {
            val doc = json.decodeFromString<Doc>(text)
            if (doc.v == VERSION) {
                val ds = doc.deployments.mapNotNull { it.toEnrollmentOrNull() }
                val active = doc.active?.takeIf { a -> ds.any { projectKey(it) == a } }
                return EnrollmentVault(ds, active)
            }
        }

        // Not the current format: try to migrate a single legacy record.
        runCatching {
            json.decodeFromString<Legacy>(text).toEnrollmentOrNull()?.let {
                return EnrollmentVault(listOf(it), projectKey(it))
            }
        }

        return EnrollmentVault()
    }

    private fun Enrollment.toRec() =
        Rec(
            approverDeviceId, deviceToken, servers, pin?.encoded, deviceName,
            projectId, projectName, localLabel, keyRef, tokenExpiresAtEpochSeconds,
            vapidPublicKey, fcm,
        )

    private fun Rec.toEnrollmentOrNull(): Enrollment? {
        val parsedPin = pin?.let { SpkiPin.parse(it) ?: return null }
        return Enrollment(
            approverDeviceId, deviceToken, servers, parsedPin, deviceName,
            projectId, projectName, localLabel, keyRef, tokenExpiresAt,
            vapidPublicKey, fcm,
        )
    }

    private fun Legacy.toEnrollmentOrNull(): Enrollment? {
        val parsedPin = pin?.let { SpkiPin.parse(it) ?: return null }
        return Enrollment(approverDeviceId, deviceToken, servers, parsedPin, deviceName)
    }
}
