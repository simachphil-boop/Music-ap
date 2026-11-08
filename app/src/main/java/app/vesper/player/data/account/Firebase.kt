package app.vesper.player.data.account

import android.util.Base64
import app.vesper.player.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AccountException(message: String, val network: Boolean = false) : Exception(message)

/**
 * Minimal Firebase client over its public REST API (no Google Play Services, no SDK):
 * e-mail/password accounts (Identity Toolkit) and the user's own documents in Firestore.
 * The web API key only identifies the project; data is protected by Firestore rules that let
 * each signed-in user read and write `users/{uid}/…` and nothing else.
 */
object Firebase {
    val apiKey: String get() = BuildConfig.FIREBASE_API_KEY
    val projectId: String get() = BuildConfig.FIREBASE_PROJECT_ID
    val configured: Boolean get() = apiKey.isNotBlank() && projectId.isNotBlank()

    data class Auth(val uid: String, val email: String, val idToken: String, val refreshToken: String, val expiresAt: Long)

    // ---- accounts ------------------------------------------------------------------------------

    fun signUp(email: String, password: String): Auth =
        auth(identity("accounts:signUp", JSONObject().put("email", email).put("password", password).put("returnSecureToken", true)))

    fun signIn(email: String, password: String): Auth =
        auth(identity("accounts:signInWithPassword", JSONObject().put("email", email).put("password", password).put("returnSecureToken", true)))

    fun setName(idToken: String, name: String) {
        identity("accounts:update", JSONObject().put("idToken", idToken).put("displayName", name).put("returnSecureToken", false))
    }

    fun displayName(idToken: String): String? =
        identity("accounts:lookup", JSONObject().put("idToken", idToken)).optJSONArray("users")?.optJSONObject(0)
            ?.optString("displayName")?.ifBlank { null }

    fun sendPasswordReset(email: String) {
        identity("accounts:sendOobCode", JSONObject().put("requestType", "PASSWORD_RESET").put("email", email))
    }

    fun changePassword(idToken: String, password: String): Auth? =
        identity("accounts:update", JSONObject().put("idToken", idToken).put("password", password).put("returnSecureToken", true))
            .takeIf { it.has("idToken") }?.let(::auth)

    fun deleteAccount(idToken: String) {
        identity("accounts:delete", JSONObject().put("idToken", idToken))
    }

    fun refresh(refreshToken: String): Auth {
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
        val o = request("POST", "https://securetoken.googleapis.com/v1/token?key=$apiKey", body, "application/x-www-form-urlencoded", null)
        return Auth(
            uid = o.optString("user_id"),
            email = "",
            idToken = o.getString("id_token"),
            refreshToken = o.optString("refresh_token", refreshToken),
            expiresAt = System.currentTimeMillis() + (o.optString("expires_in").toLongOrNull() ?: 3600L) * 1000L,
        )
    }

    private fun identity(method: String, body: JSONObject): JSONObject =
        request("POST", "https://identitytoolkit.googleapis.com/v1/$method?key=$apiKey", body.toString(), "application/json", null)

    private fun auth(o: JSONObject) = Auth(
        uid = o.getString("localId"),
        email = o.optString("email"),
        idToken = o.getString("idToken"),
        refreshToken = o.getString("refreshToken"),
        expiresAt = System.currentTimeMillis() + (o.optString("expiresIn").toLongOrNull() ?: 3600L) * 1000L,
    )

    // ---- Firestore: users/{uid}/sync/{doc} -----------------------------------------------------

    private fun docUrl(uid: String, doc: String) =
        "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/users/$uid/sync/$doc"

    /** Fields of a document, or null if it doesn't exist. Values: Long, String or ByteArray. */
    fun getDoc(idToken: String, uid: String, doc: String): Map<String, Any>? {
        val o = try {
            request("GET", docUrl(uid, doc), null, null, idToken)
        } catch (e: NotFound) {
            return null
        }
        val fields = o.optJSONObject("fields") ?: return emptyMap()
        val out = HashMap<String, Any>()
        fields.keys().forEach { k ->
            val v = fields.getJSONObject(k)
            when {
                v.has("integerValue") -> out[k] = v.getString("integerValue").toLong()
                v.has("stringValue") -> out[k] = v.getString("stringValue")
                v.has("bytesValue") -> out[k] = Base64.decode(v.getString("bytesValue"), Base64.DEFAULT)
            }
        }
        return out
    }

    fun putDoc(idToken: String, uid: String, doc: String, fields: Map<String, Any>) {
        val f = JSONObject()
        fields.forEach { (k, v) ->
            f.put(
                k,
                when (v) {
                    is Long, is Int -> JSONObject().put("integerValue", v.toString())
                    is ByteArray -> JSONObject().put("bytesValue", Base64.encodeToString(v, Base64.NO_WRAP))
                    else -> JSONObject().put("stringValue", v.toString())
                },
            )
        }
        try {
            request("PATCH", docUrl(uid, doc), JSONObject().put("fields", f).toString(), "application/json", idToken)
        } catch (e: NotFound) {
            throw AccountException("В облачном проекте не создана база Firestore")
        }
    }

    fun deleteDoc(idToken: String, uid: String, doc: String) {
        try {
            request("DELETE", docUrl(uid, doc), null, null, idToken)
        } catch (_: NotFound) {
        }
    }

    // ---- HTTP ----------------------------------------------------------------------------------

    private class NotFound : Exception()

    private fun request(method: String, url: String, body: String?, type: String?, bearer: String?): JSONObject {
        if (!configured) throw AccountException("Облачные аккаунты не настроены в этой сборке")
        val c = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (method == "PATCH") "POST" else method
                if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("X-Firebase-Locale", "ru")
                bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "$type; charset=utf-8")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
        } catch (e: IOException) {
            throw AccountException("Нет связи с сервером. Проверьте интернет", network = true)
        }
        try {
            val code = try { c.responseCode } catch (e: IOException) { throw AccountException("Нет связи с сервером. Проверьте интернет", network = true) }
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 404) throw NotFound()
            if (code !in 200..299) throw AccountException(explain(code, text))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    internal fun explain(code: Int, text: String): String {
        val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault("")
        val head = msg.substringBefore(" ").substringBefore(":").trim()
        return when (head) {
            "EMAIL_EXISTS" -> "Аккаунт с этой почтой уже есть — войдите"
            "EMAIL_NOT_FOUND", "INVALID_LOGIN_CREDENTIALS", "INVALID_PASSWORD" -> "Неверная почта или пароль"
            "INVALID_EMAIL" -> "Почта указана неправильно"
            "WEAK_PASSWORD" -> "Пароль слишком простой — нужно хотя бы 6 символов"
            "MISSING_PASSWORD" -> "Введите пароль"
            "TOO_MANY_ATTEMPTS_TRY_LATER" -> "Слишком много попыток. Подождите несколько минут"
            "USER_DISABLED" -> "Этот аккаунт отключён"
            "TOKEN_EXPIRED", "INVALID_ID_TOKEN", "USER_NOT_FOUND", "INVALID_REFRESH_TOKEN", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN" ->
                "Сессия устарела — войдите заново"
            "OPERATION_NOT_ALLOWED", "CONFIGURATION_NOT_FOUND" -> "Вход по почте не включён в облачном проекте"
            "PERMISSION_DENIED" -> "Сервер не разрешил доступ к данным (проверьте правила Firestore)"
            else -> when {
                code == 403 -> "Сервер не разрешил доступ к данным"
                code >= 500 -> "Сервер временно недоступен, попробуйте позже"
                msg.isNotBlank() -> "Ошибка сервера: $msg"
                else -> "Ошибка сервера ($code)"
            }
        }
    }
}
