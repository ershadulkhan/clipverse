package app.clipt.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.clipt.R
import app.clipt.capture.ClipboardReadActivity
import app.clipt.capture.CopyLatestActivity
import app.clipt.prefs
import app.clipt.ui.theme.Clipt
import app.clipt.ui.theme.CliptTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        publishShortcuts()

        // Sync on open — the only automatic network call the app ever makes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (firstResume) firstResume = false else vm.refresh()
                vm.refreshPermissions()
                // (Re)start auto-send while we're in front (e.g. after Shizuku was restarted).
                vm.ensureAutoSendRunning()
            }
        }

        setContent {
            val themeState by vm.state.collectAsStateWithLifecycle()
            CliptTheme(themeState.themeMode) {
                val state = themeState
                val dark = app.clipt.ui.theme.isDarkMode(state.themeMode)
                LaunchedEffect(dark) {
                    // Keep status/navigation bar icons readable when the theme overrides the system.
                    val transparent = android.graphics.Color.TRANSPARENT
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    )
                }
                val event by vm.events.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                var settingsOpen by remember { mutableStateOf(false) }

                LaunchedEffect(event) {
                    event?.let { snackbar.currentSnackbarData?.dismiss(); vm.consumeMessage(); snackbar.showSnackbar(it) }
                }

                Box(Modifier.fillMaxSize()) {
                    if (state.paired) {
                        HomeScreen(vm, state, prefs.deviceId, onOpenSettings = { settingsOpen = true })
                        if (settingsOpen) SettingsSheet(vm, state, onDismiss = { settingsOpen = false })
                    } else {
                        PairingScreen(
                            onScan = ::scanQr,
                            onPairCode = { vm.pairFromLink(it) },
                            onPairManual = { url, token -> vm.pair(url, token) },
                        )
                    }
                    SnackbarHost(
                        snackbar,
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp),
                    ) { data ->
                        Snackbar(data, containerColor = Clipt.colors.ink, contentColor = Clipt.colors.canvas)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** clipt://pair?u=…&t=… opened from a QR scanned by the system camera. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "clipverse" || data.scheme == "clipt") vm.pairFromLink(data.toString())
    }

    /**
     * Google code scanner: runs in Play services' own UI, needs no CAMERA permission and
     * releases the camera the moment a code is read.
     */
    private fun scanQr() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { vm.pairFromLink(it) } }
            .addOnFailureListener { vm.message("Scanner unavailable — use “Pair without camera”") }
    }

    private fun publishShortcuts() {
        val icon = IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        val shortcuts = listOf(
            ShortcutInfoCompat.Builder(this, "copy_latest")
                .setShortLabel(getString(R.string.shortcut_copy_latest))
                .setIcon(icon)
                .setIntent(Intent(this, CopyLatestActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build(),
            ShortcutInfoCompat.Builder(this, "send_clipboard")
                .setShortLabel(getString(R.string.shortcut_send_clipboard))
                .setIcon(icon)
                .setIntent(Intent(this, ClipboardReadActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build(),
        )
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts) }
    }
}
