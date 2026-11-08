package app.vesper.player.data.account

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Session(
    val uid: String,
    val email: String,
    val name: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)

/** The signed-in session on this device (one at a time). */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("floya_account", Context.MODE_PRIVATE)

    fun session(): Session? {
        val uid = prefs.getString("uid", null) ?: return null
        return Session(
            uid = uid,
            email = prefs.getString("email", "").orEmpty(),
            name = prefs.getString("name", "").orEmpty(),
            idToken = prefs.getString("id_token", "").orEmpty(),
            refreshToken = prefs.getString("refresh_token", "").orEmpty(),
            expiresAt = prefs.getLong("expires_at", 0),
        )
    }

    fun save(s: Session) {
        prefs.edit()
            .putString("uid", s.uid).putString("email", s.email).putString("name", s.name)
            .putString("id_token", s.idToken).putString("refresh_token", s.refreshToken).putLong("expires_at", s.expiresAt)
            .commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }
}

/**
 * Registration and sign-in. Only the e-mail, an optional display name and the password (sent
 * straight to Firebase, never stored) are involved; what gets synced is described in CloudSync.
 */
class AccountManager(private val context: Context) {
    private val store = AccountStore(context)
    private val _session = MutableStateFlow(store.session()?.takeIf { it.uid == Profile.id })
    /** Signed-in account of the running profile (null in guest mode). */
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val tokenLock = Mutex()

    val configured: Boolean get() = Firebase.configured

    /** True if the phone has playlists/favourites/history in guest mode that could go to a new account. */
    fun guestHasData(): Boolean = Profile.isGuest && Profile.hasLocalData(context, Profile.GUEST)

    /** Creates an account. When [takeGuestData] is true, what's on the phone now becomes this account's. */
    suspend fun register(email: String, password: String, name: String, takeGuestData: Boolean) = withContext(Dispatchers.IO) {
        val e = validate(email, password)
        val a = Firebase.signUp(e, password)
        val n = name.trim()
        if (n.isNotEmpty()) runCatching { Firebase.setName(a.idToken, n) }
        finishSignIn(a, n.ifEmpty { e.substringBefore('@') }, takeGuestData)
    }

    /** Signs in. [takeGuestData] merges what's on the phone now into the account (when it has no copy here yet). */
    suspend fun signIn(email: String, password: String, takeGuestData: Boolean) = withContext(Dispatchers.IO) {
        val e = validate(email, password)
        val a = Firebase.signIn(e, password)
        val n = runCatching { Firebase.displayName(a.idToken) }.getOrNull() ?: e.substringBefore('@')
        finishSignIn(a, n, takeGuestData)
    }

    /** True when this account already has its own copy on this phone (no need to take guest data). */
    fun hasCopyOnPhone(uid: String) = Profile.hasLocalData(context, uid)

    private fun finishSignIn(a: Firebase.Auth, name: String, takeGuestData: Boolean) {
        store.save(Session(a.uid, a.email, name, a.idToken, a.refreshToken, a.expiresAt))
        if (takeGuestData && Profile.isGuest && !Profile.hasLocalData(context, a.uid)) Profile.scheduleAdopt(context, a.uid)
    }

    suspend fun sendPasswordReset(email: String) = withContext(Dispatchers.IO) {
        val e = email.trim()
        if (!EMAIL.matches(e)) throw AccountException("Введите почту, на которую зарегистрирован аккаунт")
        Firebase.sendPasswordReset(e)
    }

    suspend fun rename(name: String) = withContext(Dispatchers.IO) {
        val n = name.trim().take(40)
        if (n.isEmpty()) throw AccountException("Имя не может быть пустым")
        Firebase.setName(token(), n)
        val s = _session.value ?: return@withContext
        val updated = s.copy(name = n)
        store.save(updated)
        _session.value = updated
    }

    suspend fun changePassword(password: String) = withContext(Dispatchers.IO) {
        if (password.length < 6) throw AccountException("Пароль должен быть не короче 6 символов")
        val a = Firebase.changePassword(token(), password)
        val s = _session.value ?: return@withContext
        if (a != null) {
            val updated = s.copy(idToken = a.idToken, refreshToken = a.refreshToken, expiresAt = a.expiresAt)
            store.save(updated)
            _session.value = updated
        }
    }

    /** Signs out; the app restarts in guest mode. [wipe] also deletes this account's copy from the phone. */
    fun signOut(wipe: Boolean) {
        val s = _session.value
        store.clear()
        if (wipe && s != null) Profile.scheduleWipe(context, s.uid)
    }

    /** Deletes the cloud copy and the account itself, then signs out and wipes the phone copy. */
    suspend fun deleteAccount(cloud: CloudSync) = withContext(Dispatchers.IO) {
        val t = token()
        val s = _session.value ?: return@withContext
        cloud.deleteRemote(t, s.uid)
        Firebase.deleteAccount(t)
        signOut(wipe = true)
    }

    /** A valid ID token, refreshed when it's about to expire. */
    suspend fun token(): String = tokenLock.withLock {
        val s = _session.value ?: throw AccountException("Вы не вошли в аккаунт")
        if (s.idToken.isNotBlank() && s.expiresAt - System.currentTimeMillis() > 120_000) return@withLock s.idToken
        val a = withContext(Dispatchers.IO) { Firebase.refresh(s.refreshToken) }
        val updated = s.copy(idToken = a.idToken, refreshToken = a.refreshToken, expiresAt = a.expiresAt)
        store.save(updated)
        _session.value = updated
        a.idToken
    }

    private fun validate(email: String, password: String): String {
        if (!configured) throw AccountException("Облачные аккаунты не настроены в этой сборке")
        val e = email.trim().lowercase()
        if (!EMAIL.matches(e)) throw AccountException("Почта указана неправильно")
        if (password.length < 6) throw AccountException("Пароль должен быть не короче 6 символов")
        return e
    }

    companion object {
        private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""")
    }
}
