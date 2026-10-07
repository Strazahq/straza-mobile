package dev.straza.approver.push

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.straza.approver.shared.net.FcmAppConfig
import java.io.File
import org.json.JSONObject

/**
 * Runtime init of the default FirebaseApp from the deployment's public config,
 * which arrives in the enroll response. A store build also carries the Straza
 * relay's project from a `google-services.json` present at build time, and a
 * deployment with its own project replaces it. `onNewToken` only fires for
 * the default app.
 *
 * A push can be what creates the process, so the config is persisted and
 * re-applied from [StrazaFcmInitProvider]. The persisted copy is plaintext
 * JSON, not Keystore-wrapped: every value is a public identifier, and it is
 * read at process creation on main, where Keystore I/O is slow and ANR-prone.
 * The encrypted vault holds the authoritative copy.
 */
object FcmRuntime {

    /**
     * Aligns the default FirebaseApp with the active pairing's config.
     * Idempotent and main-thread safe (no network; token movement is async).
     *  - Same identity already initialised: no-op.
     *  - Different project: best-effort delete of the old token, delete the
     *    FirebaseApp, re-init. A fresh token arrives through `onNewToken`.
     *  - Null config (no FCM): tear down the app this class created. An app
     *    from a baked google-services.json (no mirror file) is left alone.
     */
    fun apply(context: Context, config: FcmAppConfig?) {
        val store = FcmConfigStore(context)
        val previous = store.read()
        if (config == previous) {
            // Nothing changed, but the app may not exist yet in this process.
            ensureInitialized(context)
            return
        }

        if (config == null) {
            // previous != null here, so this class owned a runtime app and the
            // active pairing no longer has FCM.
            store.clear()
            hardDeleteDefaultApp(context)
            // A baked google-services.json, if present, may reclaim the slot.
            runCatching { FirebaseApp.initializeApp(context) }
            return
        }

        store.write(config)
        val existing = defaultApp(context)
        if (existing != null) {
            val opts = existing.options
            if (opts.projectId == config.projectId && opts.applicationId == config.appId) {
                return // already the right project; token and routes stand
            }
            hardDeleteDefaultApp(context)
        }
        initialize(context, config)
    }

    /**
     * Re-creates the default app from the persisted mirror when none exists.
     * Does not throw: a corrupt mirror must mean "FCM unavailable", not a
     * crash loop at process start.
     */
    fun ensureInitialized(context: Context) {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) return
            val config = FcmConfigStore(context).read() ?: return
            initialize(context, config)
        } catch (_: Exception) {
            // Includes the double-init race: initializeApp throws
            // IllegalStateException if another thread won.
        }
    }

    private fun initialize(context: Context, config: FcmAppConfig) {
        try {
            FirebaseApp.initializeApp(
                context.applicationContext,
                FirebaseOptions.Builder()
                    .setProjectId(config.projectId)
                    .setApplicationId(config.appId)
                    .setApiKey(config.apiKey)
                    .setGcmSenderId(config.senderId)
                    .build(),
            )
        } catch (_: IllegalStateException) {
            // Lost a benign race with another initializer. The next apply()
            // reconciles the app identity.
        }
    }

    private fun defaultApp(context: Context): FirebaseApp? =
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }

    /**
     * Deletes the token first (best-effort and async, it needs the app alive)
     * and then the app. If the token delete loses the race with `app.delete()`
     * the old token stays in the old project, where it can only receive that
     * deployment's pushes until its server prunes the route.
     */
    private fun hardDeleteDefaultApp(context: Context) {
        val app = defaultApp(context) ?: return
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
        runCatching { app.delete() }
    }
}

/**
 * The cold-start mirror of the active pairing's [FcmAppConfig]. Unreadable or
 * partial content decodes to null, which means no FCM.
 */
internal class FcmConfigStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun read(): FcmAppConfig? {
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            if (obj.optInt(K_VERSION) != VERSION) return null
            val projectId = obj.optString(K_PROJECT_ID, "")
            val appId = obj.optString(K_APP_ID, "")
            val apiKey = obj.optString(K_API_KEY, "")
            val senderId = obj.optString(K_SENDER_ID, "")
            if (projectId.isEmpty() || appId.isEmpty() || apiKey.isEmpty() || senderId.isEmpty()) {
                null
            } else {
                FcmAppConfig(projectId, appId, apiKey, senderId)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun write(config: FcmAppConfig) {
        val obj = JSONObject()
            .put(K_VERSION, VERSION)
            .put(K_PROJECT_ID, config.projectId)
            .put(K_APP_ID, config.appId)
            .put(K_API_KEY, config.apiKey)
            .put(K_SENDER_ID, config.senderId)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(obj.toString())
        check(temp.renameTo(file)) { "could not persist the fcm app config" }
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "fcm-app-config.json"
        const val VERSION = 1
        const val K_VERSION = "v"
        const val K_PROJECT_ID = "project_id"
        const val K_APP_ID = "app_id"
        const val K_API_KEY = "api_key"
        const val K_SENDER_ID = "sender_id"
    }
}

/**
 * Rebuilds the default FirebaseApp from the persisted mirror before
 * `Application.onCreate`, and so before any FCM component can dispatch a
 * message into this process. This is the pattern Firebase recommends for
 * runtime config (firebase-android-sdk#66). Not exported: it serves no data.
 */
class StrazaFcmInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { FcmRuntime.ensureInitialized(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
