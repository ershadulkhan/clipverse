package app.clipt.ui

import android.Manifest
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.drawable.Icon as AndroidIcon
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.clipt.R
import app.clipt.data.Clip
import app.clipt.tile.SendClipboardTileService
import app.clipt.ui.theme.Clipt
import app.clipt.widget.CopyLatestWidget
import coil3.compose.AsyncImage

// =====================================================================================
// Home
// =====================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    state: UiState,
    deviceId: String,
    onOpenSettings: () -> Unit,
) {
    val c = Clipt.colors
    val context = LocalContext.current
    var lightbox by remember { mutableStateOf<Clip?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        vm.sendImages(uris)
    }

    Column(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CliptLogo(28.dp)
            Spacer(Modifier.width(10.dp))
            Text("clipverse", style = MaterialTheme.typography.titleLarge, color = c.ink)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.error == null) c.ok else c.accent))
            Spacer(Modifier.weight(1f))
            SyncChip(on = state.syncEnabled, onToggle = { vm.setSyncEnabled(!state.syncEnabled) })
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = c.ink2) }
        }

        // Search
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(42.dp)
                .clip(CircleShape).background(c.card).border(1.dp, c.line, CircleShape).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = c.ink3)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) Text("Search clips", color = c.ink3, style = MaterialTheme.typography.bodyMedium)
                BasicTextField(
                    value = state.query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.query.isNotEmpty()) {
                Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp).clickable { vm.setQuery("") }, tint = c.ink3)
            }
        }

        // Filters
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Filter.entries) { f -> Pill(f.label, state.filter == f, { vm.setFilter(f) }) }
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            val visible = state.visible
            val listState = rememberLazyListState()
            // New clips are inserted above the current first card; if the user is at (or near)
            // the top, follow them instead of keeping the old card anchored.
            val newestId = visible.firstOrNull()?.id
            LaunchedEffect(newestId) {
                if (newestId != null && listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.error != null && state.clips.isEmpty()) {
                    item { EmptyState("Couldn't reach your server", state.error) }
                } else if (visible.isEmpty() && !state.refreshing) {
                    item {
                        if (state.clips.isEmpty()) EmptyState("No clips yet", "Share anything to Clipverse, select text → “Send to Clipverse”, or tap Paste & send below.")
                        else EmptyState("Nothing matches", "Try another filter or search.")
                    }
                }
                items(visible, key = { it.id }) { clip ->
                    ClipCard(
                        clip = clip,
                        isMine = clip.deviceId == deviceId,
                        imageUrl = vm.imageUrl(clip),
                        authHeader = vm.authHeader(),
                        onCopy = { done -> vm.copy(clip, done) },
                        onShare = { vm.share(clip) { context.startActivity(it) } },
                        onPin = { vm.togglePin(clip) },
                        onDelete = { vm.delete(clip) },
                        onOpenImage = { lightbox = clip },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        // Composer
        Surface(
            color = c.card,
            border = BorderStroke(1.dp, c.line),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding().imePadding().fillMaxWidth(),
        ) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.Image, "Send photos", tint = c.ink2)
                }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    if (draft.isEmpty()) Text("Type a note…", color = c.ink3, style = MaterialTheme.typography.bodyMedium)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (draft.isBlank()) {
                    ActionButton("Paste & send", Icons.Rounded.ContentPaste, onClick = vm::sendClipboard)
                } else {
                    ActionButton("Send", Icons.AutoMirrored.Rounded.Send, onClick = { vm.sendText(draft); draft = "" })
                }
            }
        }
    }

    lightbox?.let { clip ->
        Lightbox(
            url = vm.imageUrl(clip), auth = vm.authHeader(), clip = clip,
            onDownload = { vm.download(clip) },
            onShare = { vm.share(clip) { context.startActivity(it) } },
            onDismiss = { lightbox = null },
        )
    }
}

/** Small "Sync on / Paused" switch for the top bar. */
@Composable
private fun SyncChip(on: Boolean, onToggle: () -> Unit) {
    val c = Clipt.colors
    Row(
        Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.chip).clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(width = 24.dp, height = 14.dp).clip(CircleShape).background(if (on) c.accent else c.line)) {
            Box(Modifier.padding(2.dp).size(10.dp).clip(CircleShape).background(Color.White).align(if (on) Alignment.CenterEnd else Alignment.CenterStart))
        }
        Text(if (on) "Sync on" else "Paused", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (on) c.accent else c.ink3)
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    val c = Clipt.colors
    Column(Modifier.fillMaxWidth().padding(top = 64.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CliptLogo(44.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.ink3, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Lightbox(url: String, auth: String, clip: Clip, onDownload: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF0000000)).clickable(onClick = onDismiss)) {
            AsyncImage(
                model = clipImageRequest(url, auth, clip.id),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "Download", tint = Color.White) }
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            }
        }
    }
}

// =====================================================================================
// Pairing (first run)
// =====================================================================================

@Composable
fun PairingScreen(onScan: () -> Unit, onPairCode: (String) -> Unit, onPairManual: (String, String) -> Unit) {
    val c = Clipt.colors
    var manual by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        CliptLogo(64.dp)
        Spacer(Modifier.height(24.dp))
        Text("Copy here.\nPaste anywhere.", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Clipverse syncs text and images between this phone and your computers through your own Cloudflare account.",
            style = MaterialTheme.typography.bodyMedium, color = c.ink2, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        ActionButton(
            "Scan QR to pair", Icons.Rounded.QrCodeScanner, onClick = onScan,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "On your computer: Clipverse extension → Settings → Pair another device. Works on any network.",
            style = MaterialTheme.typography.labelMedium, color = c.ink3, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Text("Or type the code", style = MaterialTheme.typography.labelLarge, color = c.ink2)
        Spacer(Modifier.height(8.dp))
        CliptField(code, { code = it }, "Pairing code, e.g. yourname-K7Q4MX")
        Spacer(Modifier.height(10.dp))
        ActionButton("Pair", Icons.AutoMirrored.Rounded.Send, onClick = { if (code.isNotBlank()) onPairCode(code) }, filled = code.isNotBlank())
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { manual = !manual }) {
            Text(if (manual) "Hide server details" else "First device? Enter server details", color = c.ink2)
        }
        if (manual) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CliptField(url, { url = it }, "Server URL (https://…workers.dev)")
                CliptField(token, { token = it }, "Sync token", secret = true)
                ActionButton("Connect", Icons.AutoMirrored.Rounded.Send, onClick = { onPairManual(url, token) },
                    filled = url.isNotBlank() && token.isNotBlank())
            }
        }
    }
}

@Composable
fun CliptField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    val c = Clipt.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedLabelColor = c.accent,
            cursorColor = c.accent, focusedContainerColor = c.card, unfocusedContainerColor = c.card,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// =====================================================================================
// Settings
// =====================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, state: UiState, onDismiss: () -> Unit) {
    val c = Clipt.colors
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf(state.deviceName) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setPush(true) else vm.message("Notifications are off for Clipverse")
    }

    ModalBottomSheet(onDismissRequest = { vm.setDeviceName(name); onDismiss() }, sheetState = sheetState, containerColor = c.canvas) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = c.ink)

            SettingsCard {
                Text("Connected to", style = MaterialTheme.typography.labelMedium, color = c.ink3)
                Text(state.serverUrl.removePrefix("https://"), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                Spacer(Modifier.height(10.dp))
                CliptField(name, { name = it }, "This device’s name")
            }

            SettingsCard {
                Text("Pair another device", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("Type this code in Clipverse on another phone or computer. Works on any network.",
                    style = MaterialTheme.typography.labelMedium, color = c.ink3)
                Spacer(Modifier.height(10.dp))
                var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
                LaunchedEffect(state.pairCode) {
                    while (true) { now = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(1000) }
                }
                val left = state.pairCodeExpires - now
                if (state.pairCode != null && left > 0) {
                    Text(state.pairCode, style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = c.ink, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.chip).padding(horizontal = 14.dp, vertical = 10.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("One use · expires in ${left / 60}:${(left % 60).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelMedium, color = c.ink3)
                } else {
                    ActionButton("Show pairing code", Icons.Rounded.QrCodeScanner, onClick = vm::createPairCode)
                }
            }

            SettingsCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED").forEach { (id, label) ->
                        Pill(label, state.themeMode == id, { vm.setThemeMode(id) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("AMOLED uses pure black, which saves battery on OLED screens.", style = MaterialTheme.typography.labelMedium, color = c.ink3)
            }

            SettingsCard {
                Text("Image quality", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text("Applies to photos you send from this phone.", style = MaterialTheme.typography.labelMedium, color = c.ink3)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Fast cellular (WebP)", state.fastImages, { vm.setFastImages(true) })
                    Pill("Original quality", !state.fastImages, { vm.setFastImages(false) })
                }
            }

            SettingsCard {
                Text("Automatic sync", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    title = "Sync",
                    body = "Master switch. Off pauses all automatic syncing on this phone; you can still send by hand.",
                    checked = state.syncEnabled,
                    enabled = true,
                    onChange = vm::setSyncEnabled,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Auto-paste from computer",
                    body = if (state.pushEnabled) "Text you copy on your computer is put on this phone's clipboard — just paste."
                    else "Needs “Instant push from desktop” below.",
                    checked = state.autoReceive,
                    enabled = true,
                    onChange = vm::setAutoReceive,
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "Auto-send what I copy",
                    body = "Runs in the background with a small notification. Uses a little battery.",
                    checked = state.autoSend,
                    enabled = true,
                    onChange = { on ->
                        vm.setAutoSend(on)
                        // The overlay permission is only for the no-Shizuku fallback.
                        if (on && !state.shizukuInstalled && !state.hasOverlay) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                    },
                )
                if (state.autoSend && state.shizukuInstalled && !state.shizukuRunning) {
                    Spacer(Modifier.height(8.dp))
                    SetupLine(ok = false, text = "Shizuku isn't running — open the Shizuku app and start it (needed after every restart)", onClick = null)
                } else if (state.autoSend && state.shizukuRunning) {
                    Spacer(Modifier.height(8.dp))
                    SetupLine(
                        ok = state.shizukuAllowed,
                        text = if (state.shizukuAllowed) "Using Shizuku — copies are detected instantly, no Clipverse background service"
                        else "Shizuku: tap to allow Clipverse",
                        onClick = if (state.shizukuAllowed) null else vm::requestShizuku,
                    )
                    state.watcherStatus?.let { SetupLine(ok = it.startsWith("listening"), text = "Watcher: $it", onClick = null) }
                    Text("After restarting your phone, start Shizuku again, then open Clipverse once.",
                        style = MaterialTheme.typography.labelSmall, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
                } else if (state.autoSend) {
                    Spacer(Modifier.height(8.dp))
                    SetupLine(
                        ok = state.hasOverlay,
                        text = if (state.hasOverlay) "Display over other apps: allowed" else "Display over other apps: tap to allow",
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        },
                    )
                    SetupLine(
                        ok = state.hasLogAccess,
                        text = if (state.hasLogAccess) "Log access: granted (doesn't work on phones that mute system logs, e.g. vivo — use Shizuku there)"
                        else "Log access: needs a one-time step from a computer — adb shell pm grant ${context.packageName} android.permission.READ_LOGS",
                        onClick = null,
                    )
                }
            }

            SettingsCard {
                ToggleRow(
                    title = "Instant push from desktop",
                    body = if (state.pushAvailable) "Get a notification when your computer sends a clip. Uses Google’s existing push connection — no extra battery."
                    else "Not configured on your server. See README → Optional push.",
                    checked = state.pushEnabled,
                    enabled = state.pushAvailable,
                    onChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else vm.setPush(on)
                    },
                )
            }

            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = c.ok, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            !state.autoSend -> "Zero-Background Mode Active"
                            state.shizukuRunning -> "Low-Battery Auto-Send (Shizuku)"
                            else -> "Auto-Send Background Service On"
                        },
                        style = MaterialTheme.typography.titleMedium, color = c.ink,
                    )
                }
                Spacer(Modifier.height(6.dp))
                listOf(
                    "No background service, polling, wake locks or alarms",
                    "Runs only when you share, copy, tap the tile/widget or open the app",
                    "Images are shrunk on-device before upload to save mobile data",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = c.ink2) }
            }

            SettingsCard {
                Text("Shortcuts", style = MaterialTheme.typography.titleMedium, color = c.ink)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Add tile", Icons.Rounded.ContentPaste, filled = false, onClick = {
                        val sbm = context.getSystemService(StatusBarManager::class.java)
                        sbm.requestAddTileService(
                            ComponentName(context, SendClipboardTileService::class.java),
                            context.getString(R.string.tile_label),
                            AndroidIcon.createWithResource(context, R.drawable.ic_tile),
                            context.mainExecutor,
                        ) { }
                    })
                    ActionButton("Add widget", Icons.Rounded.Widgets, filled = false, onClick = {
                        val awm = context.getSystemService(AppWidgetManager::class.java)
                        if (awm.isRequestPinAppWidgetSupported) {
                            awm.requestPinAppWidget(ComponentName(context, CopyLatestWidget::class.java), null, null)
                        } else vm.message("Long-press your home screen → Widgets → Clipverse")
                    })
                }
                Spacer(Modifier.height(8.dp))
                Text("Tip: select text in any app → ⋮ → “Send to Clipverse”.", style = MaterialTheme.typography.labelMedium, color = c.ink3)
            }

            TextButton(onClick = { if (confirmUnpair) { vm.unpair(); onDismiss() } else confirmUnpair = true }) {
                Text(if (confirmUnpair) "Tap again to unpair this phone" else "Unpair this phone", color = c.accent)
            }
        }
    }
}

@Composable
private fun SetupLine(ok: Boolean, text: String, onClick: (() -> Unit)?) {
    val c = Clipt.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(if (ok) "✓" else "!", color = if (ok) c.ok else c.accent, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (ok) c.ink3 else c.ink2)
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    val c = Clipt.colors
    Surface(color = c.card, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, c.line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Clipt.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) c.ink else c.ink3)
            Text(body, style = MaterialTheme.typography.labelMedium, color = c.ink3)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White, uncheckedTrackColor = c.line,
                uncheckedBorderColor = c.line, uncheckedThumbColor = Color.White),
        )
    }
}

