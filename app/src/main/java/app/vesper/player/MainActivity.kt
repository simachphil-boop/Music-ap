package app.vesper.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.ui.AppActions
import app.vesper.player.ui.MainViewModel
import app.vesper.player.ui.VesperRoot
import app.vesper.player.ui.theme.ThemeRegistry
import app.vesper.player.ui.theme.VesperThemeProvider
import app.vesper.player.video.VideoPlayerActivity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_PLAYER = "app.vesper.player.action.OPEN_PLAYER"
        private const val PREFS = "vesper_runtime"
        private const val KEY_ASKED = "permissions_asked"
    }

    private val vm: MainViewModel by viewModels()
    private var openPlayerSignal by mutableIntStateOf(0)
    private var pendingImageCallback: ((Uri?) -> Unit)? = null
    private var lastPermissionState: Pair<Boolean, Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lastPermissionState = vm.hasAudioPermission() to vm.hasVideoPermission()
        vm.onPermissionsChanged()
        if (!vm.hasAudioPermission() && !vm.hasVideoPermission()) {
            vm.message("Без доступа можно импортировать файлы вручную через «Добавить»")
        }
    }

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importDocuments(uris)
    }

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.importTree(uri)
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingImageCallback?.invoke(uri)
        pendingImageCallback = null
    }

    private val actions by lazy {
        AppActions(
            importAudio = { safeLaunch { audioPicker.launch(arrayOf("audio/*")) } },
            importVideo = { safeLaunch { audioPicker.launch(arrayOf("video/*")) } },
            importFolder = { safeLaunch { treePicker.launch(null) } },
            requestPermissions = { requestMediaPermissions() },
            openAppSettings = { openAppSettings() },
            openVideos = { list, index -> openVideos(list, index) },
            pickImage = { callback ->
                pendingImageCallback = callback
                safeLaunch {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !vm.settingsLoaded.value }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val theme = ThemeRegistry.byId(settings.themeId)
            VesperThemeProvider(theme) {
                VesperRoot(vm = vm, actions = actions, openPlayerSignal = openPlayerSignal)
            }
        }

        if (savedInstanceState == null) {
            handleIntent(intent)
            lifecycleScope.launch {
                val s = appContainer.settings.current()
                val hasAny = vm.hasAudioPermission() || vm.hasVideoPermission()
                if (!s.onboardingDone) {
                    vm.setOnboardingDone()
                    if (hasAny) vm.rescan(silent = true) else requestMediaPermissions()
                } else if (s.autoRescan && hasAny) {
                    vm.rescan(silent = true)
                }
            }
        }
        lastPermissionState = vm.hasAudioPermission() to vm.hasVideoPermission()
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been changed from system settings while we were away.
        val now = vm.hasAudioPermission() to vm.hasVideoPermission()
        if (lastPermissionState != null && now != lastPermissionState) {
            lastPermissionState = now
            vm.onPermissionsChanged()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_OPEN_PLAYER -> openPlayerSignal++
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                val type = intent.type ?: contentResolver.getType(data).orEmpty()
                if (type.startsWith("video/")) {
                    startActivity(Intent(this, VideoPlayerActivity::class.java).setData(data).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } else {
                    vm.openExternalAudio(data)
                    openPlayerSignal++
                }
            }
        }
    }

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requestMediaPermissions() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val perms = mediaPermissions()
        val denied = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            vm.onPermissionsChanged()
            return
        }
        val askedBefore = prefs.getBoolean(KEY_ASKED, false)
        val blocked = askedBefore && denied.none { shouldShowRequestPermissionRationale(it) } &&
            !vm.hasAudioPermission() && !vm.hasVideoPermission()
        if (blocked) {
            // The system won't show the dialog again — send the user to app settings.
            vm.message("Включите доступ к музыке и видео в настройках приложения")
            openAppSettings()
            return
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        permissionLauncher.launch(denied.toTypedArray())
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun openVideos(list: List<VideoEntity>, index: Int) {
        if (list.isEmpty()) return
        // Keep the intent small: pass ids, the player loads the rest from the database.
        val window = 300
        val start = (index - window / 2).coerceAtLeast(0)
        val slice = list.subList(start, minOf(list.size, start + window))
        val ids = slice.map { it.id }.toLongArray()
        startActivity(VideoPlayerActivity.intent(this, ids, index - start))
    }

    private inline fun safeLaunch(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            vm.message("Не удалось открыть системный выбор файлов")
        }
    }
}
