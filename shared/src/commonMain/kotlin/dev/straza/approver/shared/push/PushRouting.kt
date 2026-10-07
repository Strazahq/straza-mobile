package dev.straza.approver.shared.push

import dev.straza.approver.shared.net.ApiResult
import dev.straza.approver.shared.net.ApprovalApi
import dev.straza.approver.shared.net.PushKind

/**
 * The platform-free logic of the push-route lifecycle (registration, rotation,
 * teardown, user preference), shared by the FCM and UnifiedPush lanes.
 */

/**
 * A push route as the server stores it: transport kind and address, plus, for
 * an encrypted (spec-3) UnifiedPush registration, the Web Push subscription
 * keys (RFC 8291 §3.2, unpadded base64url).
 *
 * The keys come as a pair or not at all: the server answers 400 to a lone
 * half. They also identify the route server-side, so a key rotation on an
 * unchanged URL is a different route. [p256dh] is the subscription's public
 * key and [auth] a 16-byte secret shared with the sender (RFC 8291). Neither
 * lets anyone read pushes; both persist only inside the Keystore-wrapped
 * route store.
 */
data class PushRoute(
    val kind: PushKind,
    val tokenOrEndpoint: String,
    val p256dh: String? = null,
    val auth: String? = null,
) {
    init {
        require((p256dh == null) == (auth == null)) { "p256dh and auth travel together" }
    }

    val hasKeys: Boolean get() = p256dh != null

    /** The same route on the keyless lane: the fallback when the server
     *  refuses subscription keys (no `webpush.vapidKeyFile` configured). */
    fun withoutKeys(): PushRoute = if (hasKeys) copy(p256dh = null, auth = null) else this
}

/**
 * The user's transport choice on the Notifications screen. [AUTO] picks the
 * best transport the build and device offer, with polling as the floor.
 */
enum class PushTransportPref { AUTO, FCM, UNIFIEDPUSH, POLL_ONLY }

/** What the preference resolves to on this device. */
enum class PushTransport { FCM, UNIFIEDPUSH, POLL_ONLY }

/**
 * The user's answer to "may this app use push at all?", the opt-in gate above
 * every transport. [UNDECIDED] and [DECLINED] both resolve to polling; they
 * differ only in whether the app still owes the user the first-start ask.
 * Until the answer is [GRANTED], no route is registered anywhere (server,
 * relay, distributor) and no OS notification-permission prompt fires.
 */
enum class PushConsent { UNDECIDED, GRANTED, DECLINED }

/**
 * Whether Google push (FCM) can serve this build on this phone, and if not,
 * why. Play services being present is not enough: without a Firebase
 * configuration in the APK, `FirebaseApp.getInstance()` throws.
 */
enum class FcmStatus {

    /** In the build, Play services present, Firebase configured: FCM can run. */
    READY,

    /** Not compiled into this APK: the foss flavor (F-Droid). */
    NOT_IN_BUILD,

    /** Compiled in, but this phone has no usable Play services (de-Googled). */
    NO_PLAY_SERVICES,

    /**
     * Compiled in and Play services are present, but no `google-services.json`
     * was present at build time, so the google-services plugin was not applied
     * and no default FirebaseApp exists at runtime. A supported build state:
     * CI and clean checkouts build both flavors without secrets.
     */
    NO_FIREBASE_CONFIG,

    ;

    /** Whether FCM can deliver here. The resolver asks only this. */
    val usable: Boolean get() = this == READY
}

object PushPrefs {

    /** One string, shared by the settings screen and the persisted push state. */
    const val NO_FIREBASE_CONFIG_TEXT: String =
        "push unavailable: this build has no Firebase configuration"

    /**
     * Resolves the preference against what is available. An explicit choice
     * that is unavailable falls to [PushTransport.POLL_ONLY], not to the other
     * push transport: substituting a relay the user did not pick would move
     * their notification metadata onto infrastructure they may have avoided.
     */
    fun effective(
        pref: PushTransportPref,
        fcmAvailable: Boolean,
        hasDistributor: Boolean,
    ): PushTransport = when (pref) {
        PushTransportPref.AUTO -> when {
            fcmAvailable -> PushTransport.FCM
            hasDistributor -> PushTransport.UNIFIEDPUSH
            else -> PushTransport.POLL_ONLY
        }
        PushTransportPref.FCM ->
            if (fcmAvailable) PushTransport.FCM else PushTransport.POLL_ONLY
        PushTransportPref.UNIFIEDPUSH ->
            if (hasDistributor) PushTransport.UNIFIEDPUSH else PushTransport.POLL_ONLY
        PushTransportPref.POLL_ONLY -> PushTransport.POLL_ONLY
    }

    /**
     * The consent gate over [effective]: nothing but polling runs until the
     * user has said yes. Each platform applies it at the one place it resolves
     * its transport, so an async token rotation or distributor callback cannot
     * register past a missing or withdrawn consent.
     */
    fun gate(consent: PushConsent, resolved: PushTransport): PushTransport =
        if (consent == PushConsent.GRANTED) resolved else PushTransport.POLL_ONLY

    /** Whether the app owes the user the first-start ask: enrolled and not yet answered. */
    fun shouldAsk(consent: PushConsent, enrolled: Boolean): Boolean =
        enrolled && consent == PushConsent.UNDECIDED

    /**
     * The consent a first-start provider choice expresses: picking any
     * provider is a yes, picking polling is a no. Callers also persist the
     * choice as the transport preference, so after a decline a later
     * master-switch enable cannot register a provider the user did not pick.
     */
    fun consentFor(choice: PushTransportPref): PushConsent =
        if (choice == PushTransportPref.POLL_ONLY) PushConsent.DECLINED else PushConsent.GRANTED

    /**
     * Why this phone will not ring on the FCM lane, as a sentence, or null
     * when FCM is not what stands between the user and a push. It answers
     * only for an explicit FCM choice that cannot run, or for AUTO with no
     * distributor to fall back to. The foss flavor reports no FCM problem
     * under AUTO ([FcmStatus.NOT_IN_BUILD]).
     */
    fun fcmUnavailability(
        pref: PushTransportPref,
        fcm: FcmStatus,
        hasDistributor: Boolean,
    ): String? {
        if (fcm.usable) return null
        val deprivedByFcm = when (pref) {
            PushTransportPref.FCM -> true
            PushTransportPref.AUTO -> fcm != FcmStatus.NOT_IN_BUILD && !hasDistributor
            PushTransportPref.UNIFIEDPUSH, PushTransportPref.POLL_ONLY -> false
        }
        if (!deprivedByFcm) return null
        return when (fcm) {
            FcmStatus.NO_FIREBASE_CONFIG -> NO_FIREBASE_CONFIG_TEXT
            FcmStatus.NO_PLAY_SERVICES ->
                "push unavailable: Google Play services is not available on this phone"
            FcmStatus.NOT_IN_BUILD ->
                "push unavailable: this build does not include Google push"
            FcmStatus.READY -> null
        }
    }
}

object PushRoutePlanner {

    /**
     * What to do, in order: [register] first (a single `PUT`), then every
     * [unregister] (`DELETE`, idempotent). Registering before deleting leaves
     * no window without a route, and a failed registration leaves the previous
     * route standing until the server prunes it.
     */
    data class Plan(val register: PushRoute?, val unregister: List<PushRoute>)

    /**
     * Plans the transition from the route the server currently holds
     * ([stored], as this device last recorded it) to the route that should
     * exist ([desired]; null = poll-only or sign-out). Re-planning the same
     * route re-registers it as a keep-alive; the server dedupes.
     *
     * [storedTemporary] is a fallback-distributor route (spec 3
     * `PushEndpoint.temporary`) registered while the primary was down. Any
     * primary transition retires it. [planTemporary] registers such routes.
     */
    fun plan(stored: PushRoute?, desired: PushRoute?, storedTemporary: PushRoute? = null): Plan = when {
        desired == null -> Plan(register = null, unregister = listOfNotNull(stored, storedTemporary))
        stored == null || stored == desired ->
            Plan(register = desired, unregister = listOfNotNull(storedTemporary))
        else -> Plan(register = desired, unregister = listOfNotNull(stored, storedTemporary))
    }

    /**
     * Plans registration of a temporary endpoint (fallback distributor): the
     * new one registers and a previous temporary rotates out. The stored
     * primary route is not touched, because the primary distributor being
     * offline says nothing about its endpoint's validity.
     */
    fun planTemporary(storedTemporary: PushRoute?, desired: PushRoute): Plan =
        Plan(
            register = desired,
            unregister = listOfNotNull(storedTemporary.takeIf { it != desired }),
        )
}

/**
 * Registration with the keyless fallback (openapi 0.40.0). A deployment with
 * no `webpush.vapidKeyFile` refuses a keyed `unifiedpush` registration with a
 * 400. The enroll-time VAPID key normally predicts this, but server
 * configuration can change after enrollment. So a 400 on a keyed unifiedpush
 * PUT gets one retry without keys. If the 400 came from the host allowlist,
 * the keyless retry gets the same 400 and the server's text is shown.
 */
object PushRegistrar {

    /** What got registered (the keyed route, or its keyless fallback) and the
     *  server's answer to that attempt. */
    data class Outcome(val registered: PushRoute, val result: ApiResult<Unit>)

    suspend fun register(api: ApprovalApi, deviceToken: String, route: PushRoute): Outcome {
        val first = api.registerPush(deviceToken, route.kind, route.tokenOrEndpoint, route.p256dh, route.auth)
        val refusedKeys = route.hasKeys &&
            route.kind == PushKind.UNIFIEDPUSH &&
            first is ApiResult.ServerError && first.status == 400
        if (!refusedKeys) return Outcome(route, first)

        val keyless = route.withoutKeys()
        val second = api.registerPush(deviceToken, keyless.kind, keyless.tokenOrEndpoint, null, null)
        return Outcome(keyless, second)
    }
}

/** An installed UnifiedPush distributor app, as the picker shows it. */
data class PushDistributor(val id: String, val label: String)

/** Everything the Notifications screen renders. Built by the platform layer. */
data class PushSettingsUi(
    val fcm: FcmStatus,
    val distributors: List<PushDistributor>,
    val chosenDistributorId: String? = null,
    val pref: PushTransportPref = PushTransportPref.AUTO,
    val effective: PushTransport = PushTransport.POLL_ONLY,
    val status: PushRouteStatus = PushRouteStatus.None,
    /** When a push last arrived on this device, epoch seconds; null = never. */
    val lastDeliveryEpochSeconds: Long? = null,
    /** The opt-in master switch. The rest of the screen applies only when granted. */
    val consent: PushConsent = PushConsent.UNDECIDED,
    /** False on iOS: APNs is the only transport, so the screen renders the
     *  master switch, posture and status but no picker. */
    val transportPickerAvailable: Boolean = true,
    /** Platform posture sentence (iOS: authorization x Focus, read live),
     *  rendered under the master switch; null = nothing to add. */
    val postureNote: String? = null,
) {
    /** False on the foss build, where the FCM row is not rendered. A play build
     *  with no Firebase configuration still renders it, disabled, with its reason. */
    val fcmInBuild: Boolean get() = fcm != FcmStatus.NOT_IN_BUILD

    /** FCM can deliver; see [FcmStatus.usable]. Otherwise it is not selectable. */
    val fcmAvailable: Boolean get() = fcm.usable
}

sealed interface PushRouteStatus {

    /** No route registered; the 15 s poll is the only channel. */
    data object None : PushRouteStatus

    data class Registered(val route: PushRoute) : PushRouteStatus

    /**
     * The server refused the route (HTTP 400), and keeps refusing until
     * someone changes the server side. For a UnifiedPush endpoint this is the
     * `approval.push.allowedPushHosts` allowlist. [serverMessage] is the
     * server's error text verbatim, because the admin-side fix starts from it.
     */
    data class Rejected(val route: PushRoute, val serverMessage: String) : PushRouteStatus

    /**
     * Registration did not happen (offline, server error, trust failure,
     * credential lapse). Transient: the next foreground or token rotation
     * retries.
     */
    data class Unreached(val why: String) : PushRouteStatus

    /**
     * The lane the user chose cannot run on this build or phone at all: no
     * Firebase configuration in the APK, or no Play services on the device.
     * Nothing retries and no admin can fix it. Only background ringing is
     * lost: deciding, enrolling and the pending list are unaffected, and
     * undecided requests still time out to a deny.
     */
    data class Unavailable(val why: String) : PushRouteStatus

    companion object {
        /** Maps a `PUT /v1/approver/push` result onto the status the UI shows. */
        fun of(route: PushRoute, result: ApiResult<Unit>): PushRouteStatus = when (result) {
            is ApiResult.Ok -> Registered(route)
            is ApiResult.ServerError ->
                if (result.status == 400) {
                    Rejected(route, result.message ?: "the server refused this push route (400)")
                } else {
                    Unreached("server error ${result.status}")
                }
            is ApiResult.Unreachable -> Unreached("cannot reach the server: ${result.reason}")
            is ApiResult.Untrusted -> Unreached("trust check failed: ${result.reason}")
            is ApiResult.Unauthorized -> Unreached("this pairing needs to be renewed first")
            // A 409 cannot happen on the push endpoint; treat it as transient.
            is ApiResult.AlreadyResolved -> Unreached("unexpected server answer")
        }
    }
}
