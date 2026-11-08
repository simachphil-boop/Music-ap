package app.vesper.player.data.download

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/** Sites the user can sign in to for downloads that need an account. */
enum class CookieSite(
    val title: String,
    val loginUrl: String,
    /** URLs whose cookies are exported. */
    val cookieUrls: List<String>,
    /** Domain written to cookies.txt (leading dot = include subdomains). */
    val domain: String,
) {
    YOUTUBE(
        "YouTube",
        "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F",
        listOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com"),
        ".youtube.com",
    ),
    VK("ВКонтакте", "https://m.vk.com/login", listOf("https://vk.com", "https://m.vk.com"), ".vk.com"),
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/", listOf("https://www.instagram.com"), ".instagram.com"),
    TIKTOK("TikTok", "https://www.tiktok.com/login", listOf("https://www.tiktok.com"), ".tiktok.com"),
}

/**
 * Keeps a Netscape-format cookies.txt for yt-dlp (`--cookies`). Cookies come from the
 * in-app sign-in WebView or from a user-imported file and never leave the device.
 */
object CookieStore {
    private const val HEADER = "# Netscape HTTP Cookie File"

    fun file(context: Context): File = app.vesper.player.data.account.Profile.file(context, "cookies.txt")

    fun hasAny(context: Context): Boolean = file(context).let { it.exists() && it.length() > HEADER.length + 2 }

    fun signedInSites(context: Context): Set<CookieSite> {
        val text = file(context).takeIf { it.exists() }?.readText().orEmpty()
        return CookieSite.entries.filter { site -> text.lineSequence().any { it.startsWith(site.domain + "\t") } }.toSet()
    }

    /** Copies cookies for [site] from the WebView cookie jar. Returns how many were saved. */
    fun exportFromWebView(context: Context, site: CookieSite): Int {
        val manager = CookieManager.getInstance()
        manager.flush()
        val pairs = LinkedHashMap<String, String>()
        for (url in site.cookieUrls) {
            manager.getCookie(url)?.split(";")?.forEach { raw ->
                val i = raw.indexOf('=')
                if (i > 0) pairs[raw.substring(0, i).trim()] = raw.substring(i + 1).trim()
            }
        }
        if (pairs.isEmpty()) return 0
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = pairs.map { (name, value) -> listOf(site.domain, "TRUE", "/", "TRUE", expiry.toString(), name, value).joinToString("\t") }
        write(context, keepOthers(context, setOf(site.domain)) + lines)
        return pairs.size
    }

    /** Imports a cookies.txt exported from a desktop browser (e.g. "Get cookies.txt" extension). */
    fun importFile(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return 0
        val entries = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) && it.split('\t').size >= 7 }
            .toList()
        if (entries.isEmpty()) return 0
        val domains = entries.map { it.removePrefix("#HttpOnly_").substringBefore('\t') }.toSet()
        write(context, keepOthers(context, domains) + entries)
        return entries.size
    }

    fun clear(context: Context) {
        file(context).delete()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private fun keepOthers(context: Context, domains: Set<String>): List<String> =
        file(context).takeIf { it.exists() }?.readLines().orEmpty()
            .filter { it.isNotBlank() && !it.startsWith(HEADER) }
            .filter { line -> line.removePrefix("#HttpOnly_").substringBefore('\t') !in domains }

    private fun write(context: Context, lines: List<String>) {
        file(context).writeText((listOf(HEADER, "") + lines).joinToString("\n") + "\n")
    }
}
