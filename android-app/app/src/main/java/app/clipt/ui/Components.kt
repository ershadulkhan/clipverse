package app.clipt.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clipt.data.Clip
import app.clipt.ui.theme.Clipt
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- Formatting ----------

fun relativeTime(unix: Long, nowMs: Long = System.currentTimeMillis()): String {
    val diff = (nowMs / 1000 - unix).coerceAtLeast(0)
    return when {
        diff < 45 -> "Just now"
        diff < 3600 -> "${(diff + 30) / 60}m ago"
        diff < 86400 -> "${(diff + 1800) / 3600}h ago"
        diff < 86400 * 7 -> "${(diff + 43200) / 86400}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(unix * 1000))
    }
}

fun expiresIn(expiresAt: Long, nowMs: Long = System.currentTimeMillis()): String {
    val left = expiresAt - nowMs / 1000
    if (left <= 0) return "Expiring"
    val days = (left + 86399) / 86400
    return if (days > 1) "Auto-deletes in ${days}d" else "Auto-deletes in ${((left + 3599) / 3600).coerceAtLeast(1)}h"
}

fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1024f / 1024f)
}

// ---------- Brand ----------

private val logoBack = androidx.compose.ui.graphics.vector.PathParser().parsePathString("M19.04,10.72 L19.04,8.96 A1.76,1.76 0 0,0 17.28,7.2 L9.92,7.2 A1.76,1.76 0 0,0 8.16,8.96 L8.16,18.24 A1.76,1.76 0 0,0 9.92,20.0 L11.68,20.0").toPath()
private val logoFront = androidx.compose.ui.graphics.vector.PathParser().parsePathString("M14.72,12.0 L22.08,12.0 A1.76,1.76 0 0,1 23.84,13.76 L23.84,23.04 A1.76,1.76 0 0,1 22.08,24.8 L14.72,24.8 A1.76,1.76 0 0,1 12.96,23.04 L12.96,13.76 A1.76,1.76 0 0,1 14.72,12.0 Z").toPath()

/** Clipverse mark: a solid page in front of an outlined one (same artwork as the app icon). */
@Composable
fun CliptLogo(size: Dp = 26.dp) {
    val accent = Clipt.colors.accent
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 32f
        drawRoundRect(accent, cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.5f * k))
        scale(k, k, pivot = Offset.Zero) {
            drawPath(logoBack, Color.White, style = Stroke(width = 1.54f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawPath(logoFront, Color.White)
        }
    }
}

// ---------- Small pieces ----------

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Clipt.colors
    val bg by animateColorAsState(if (selected) c.ink else c.chip, label = "pill")
    Box(
        modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.canvas else c.ink2)
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    color: Color = Clipt.colors.accent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
) {
    val c = Clipt.colors
    val bg by animateColorAsState(if (filled) color else Color.Transparent, label = "btn")
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (filled) Modifier else Modifier.border(1.dp, c.line, CircleShape))
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (filled) Color.White else c.ink2)
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (filled) Color.White else c.ink2)
    }
}

@Composable
fun clipImageRequest(url: String, auth: String, key: String) =
    ImageRequest.Builder(LocalPlatformContext.current)
        .data(url)
        .httpHeaders(NetworkHeaders.Builder().set("Authorization", auth).build())
        .memoryCacheKey(key)
        .diskCacheKey(key)
        .build()

// ---------- Clip card ----------

@Composable
fun ClipCard(
    clip: Clip,
    isMine: Boolean,
    imageUrl: String,
    authHeader: String,
    onCopy: (onDone: () -> Unit) -> Unit,
    onShare: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Clipt.colors
    var copied by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val retryScope = rememberCoroutineScope()
    val content = clip.content.orEmpty()
    val long = content.length > 280 || content.count { it == '\n' } > 6

    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { delay(2500); confirmDelete = false } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = c.card,
        border = BorderStroke(1.dp, c.line),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(CircleShape).background(c.chip).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        if (clip.devicePlatform == "android") Icons.Rounded.PhoneAndroid else Icons.Rounded.Laptop,
                        null, Modifier.size(13.dp), tint = c.ink2,
                    )
                    Text(
                        if (isMine) "This phone" else clip.deviceName,
                        style = MaterialTheme.typography.labelMedium, color = c.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(clip.createdAt), style = MaterialTheme.typography.labelSmall, color = c.ink3)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPin, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (clip.isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (clip.isPinned) "Unpin" else "Pin",
                        tint = if (clip.isPinned) c.accent else c.ink3,
                        modifier = Modifier.size(19.dp),
                    )
                }
                if (confirmDelete) {
                    Box(
                        Modifier.padding(end = 4.dp).clip(CircleShape).background(c.accentSoft)
                            .clickable { onDelete() }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("Delete?", style = MaterialTheme.typography.labelMedium, color = c.accent, fontWeight = FontWeight.SemiBold) }
                } else {
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = c.ink3, modifier = Modifier.size(19.dp))
                    }
                }
            }

            // Body
            Column(Modifier.padding(end = 8.dp, top = 4.dp)) {
                if (clip.isImage) {
                    // A just-uploaded image can take a few seconds to reach every Cloudflare
                    // location (KV is eventually consistent), so retry a handful of times.
                    var attempt by remember(clip.id) { mutableIntStateOf(0) }
                    key(attempt) {
                        AsyncImage(
                            model = clipImageRequest(imageUrl, authHeader, clip.id),
                            contentDescription = "Image from ${clip.deviceName}",
                            contentScale = ContentScale.Crop,
                            onError = { if (attempt < 5) retryScope.launch { delay(2500); attempt++ } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp)
                                .clip(RoundedCornerShape(14.dp)).background(c.chip)
                                .clickable(onClick = onOpenImage),
                        )
                    }
                } else {
                    SelectionContainer {
                        Text(
                            content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.ink,
                            maxLines = if (expanded) Int.MAX_VALUE else 7,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (long) {
                        Text(
                            if (expanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelMedium, color = c.ink2,
                            modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
                        )
                    }
                }
            }

            // Footer
            Row(
                Modifier.padding(top = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    text = if (copied) "Copied" else "Copy",
                    icon = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    color = if (copied) c.ok else c.accent,
                    onClick = { onCopy { copied = true } },
                )
                ActionButton(text = "Share", icon = Icons.Rounded.Share, filled = false, onClick = onShare)
                Spacer(Modifier.weight(1f))
                if (clip.isImage) {
                    Text(formatBytes(clip.size), style = MaterialTheme.typography.labelSmall, color = c.ink3)
                }
                if (clip.isPinned) {
                    Text(
                        "Pinned", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = c.accent,
                        modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text(expiresIn(clip.expiresAt), style = MaterialTheme.typography.labelSmall, color = c.ink3, fontSize = 11.sp)
                }
            }
        }
    }
}
