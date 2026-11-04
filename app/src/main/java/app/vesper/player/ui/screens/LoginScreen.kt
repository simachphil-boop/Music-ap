package app.vesper.player.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.vesper.player.data.download.CookieSite
import app.vesper.player.data.download.CookieStore
import app.vesper.player.ui.LocalViewModel
import app.vesper.player.ui.components.OverlineText
import app.vesper.player.ui.components.PressableIcon
import app.vesper.player.ui.theme.Vesper
import app.vesper.player.ui.theme.themedLabel

/** Mobile Chrome UA without the WebView "wv" marker — Google refuses sign-in from embedded browsers otherwise. */
private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/** Sign in to a site inside the app; the session cookies are then used for downloads. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(site: CookieSite, onBack: () -> Unit) {
    val vm = LocalViewModel.current
    val c = Vesper.colors
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onBack()
    }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PressableIcon(Icons.Rounded.ArrowBack, "Назад", onClick = onBack)
            Column(Modifier.weight(1f)) {
                OverlineText("Вход для загрузок", color = c.ornament)
                Text(site.title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            }
            Text(
                themedLabel("Готово"),
                style = MaterialTheme.typography.labelLarge,
                color = c.onAccent,
                modifier = Modifier
                    .clip(Vesper.shapes.chip)
                    .background(c.accent)
                    .clickable {
                        val saved = CookieStore.exportFromWebView(context, site)
                        vm.message(
                            if (saved > 0) "Вход в ${site.title} сохранён — можно скачивать"
                            else "Не вижу входа в ${site.title}. Войдите в аккаунт и нажмите «Готово»",
                        )
                        if (saved > 0) onBack()
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        Text(
            "Войдите в аккаунт как обычно, затем нажмите «Готово». Данные входа остаются только на этом телефоне.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = c.accentBright,
                trackColor = c.progressTrack,
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = BROWSER_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    loadUrl(site.loginUrl)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
