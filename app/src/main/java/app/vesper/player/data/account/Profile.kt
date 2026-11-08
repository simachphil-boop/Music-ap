package app.vesper.player.data.account

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Whose data the app shows right now. Each account has its own database, playlist folders,
 * skins, search/download history, film collection and sign-in cookies on this device, so one
 * person never sees another person's playlists. Without an account the app works as before
 * ("guest" = the original files).
 *
 * The profile is fixed for the lifetime of the process: switching accounts restarts the app,
 * which is the only way to be sure no screen or service keeps the previous person's data.
 */
object Profile {
    const val GUEST = "guest"
    private const val DEVICE_PREFS = "floya_device"

    /** Shared-preferences files that hold personal data and are kept per account. */
    val PERSONAL_PREFS = listOf(
        "floya_playlist_folders", "floya_playlist_skins", "floya_assistant", "floya_search",
        "floya_downloader", "floya_wave", "floya_sync", "floya_cloud_pending",
    )
    /** Files in filesDir that hold personal data. */
    val PERSONAL_FILES = listOf("movies.json", "cookies.txt")

    @Volatile var id: String = GUEST
        private set

    val isGuest: Boolean get() = id == GUEST

    private fun suffix(profile: String) = if (profile == GUEST) "" else "_" + profile.filter { it.isLetterOrDigit() }

    fun dbName(profile: String = id) = "vesper${suffix(profile)}.db"
    fun prefs(base: String, profile: String = id) = base + suffix(profile)
    fun file(context: Context, name: String, profile: String = id): File {
        val dot = name.lastIndexOf('.')
        val n = if (dot < 0) name + suffix(profile) else name.substring(0, dot) + suffix(profile) + name.substring(dot)
        return File(context.filesDir, n)
    }

    fun hasLocalData(context: Context, profile: String) = context.getDatabasePath(dbName(profile)).exists()

    /** Called once from Application.onCreate before anything opens the database. */
    fun init(context: Context) {
        val dev = context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        val account = AccountStore(context).session()
        val adopt = dev.getString("adopt", null)
        val wipe = dev.getString("wipe", null)
        if (adopt != null) {
            if (account?.uid == adopt) moveGuestTo(context, adopt)
            dev.edit().remove("adopt").commit()
        }
        if (wipe != null && wipe != account?.uid) {
            wipeLocal(context, wipe)
            dev.edit().remove("wipe").commit()
        }
        id = account?.uid ?: GUEST
    }

    /** On the next start, give the account everything that is now in guest mode. */
    fun scheduleAdopt(context: Context, uid: String) {
        context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE).edit().putString("adopt", uid).commit()
    }

    /** On the next start (after sign-out), delete this account's copy from the phone. */
    fun scheduleWipe(context: Context, uid: String) {
        context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE).edit().putString("wipe", uid).commit()
    }

    fun deviceId(context: Context): String {
        val dev = context.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        return dev.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            dev.edit().putString("device_id", it).apply()
        }
    }

    private fun prefsFile(context: Context, name: String) = File(File(context.applicationInfo.dataDir, "shared_prefs"), "$name.xml")

    private fun moveGuestTo(context: Context, uid: String) {
        if (hasLocalData(context, uid)) return
        val src = context.getDatabasePath(dbName(GUEST))
        if (src.exists()) {
            for (ext in listOf("", "-wal", "-shm", "-journal")) {
                val from = File(src.path + ext)
                if (from.exists()) from.renameTo(File(context.getDatabasePath(dbName(uid)).path + ext))
            }
        }
        PERSONAL_PREFS.forEach { base ->
            val from = prefsFile(context, base)
            if (from.exists()) from.renameTo(prefsFile(context, prefs(base, uid)))
        }
        PERSONAL_FILES.forEach { n ->
            val from = file(context, n, GUEST)
            if (from.exists()) from.renameTo(file(context, n, uid))
        }
    }

    private fun wipeLocal(context: Context, uid: String) {
        if (uid == GUEST) return
        val db = context.getDatabasePath(dbName(uid))
        for (ext in listOf("", "-wal", "-shm", "-journal")) File(db.path + ext).delete()
        PERSONAL_PREFS.forEach { prefsFile(context, prefs(it, uid)).delete() }
        PERSONAL_FILES.forEach { file(context, it, uid).delete() }
    }

    /** Restarts the app so it opens with the newly selected profile. */
    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) context.startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
        Runtime.getRuntime().exit(0)
    }
}
