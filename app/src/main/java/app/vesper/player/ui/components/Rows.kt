package app.vesper.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vesper.player.data.db.TrackEntity
import app.vesper.player.data.db.VideoEntity
import app.vesper.player.ui.theme.Ornament
import app.vesper.player.ui.theme.Vesper

@Composable
fun TrackRow(
    track: TrackEntity,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    index: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Vesper.colors
    val bg by animateColorAsState(if (isCurrent) c.highlight else Color.Transparent, tween(300), label = "rowBg")
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick ?: onMore)
            .padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) PlayingBars(isPlaying)
                else Text(
                    "$index",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.textMuted,
                )
            }
        } else {
            Box(Modifier.size(50.dp)) {
                Artwork(uri = track.uri, seed = track.title, modifier = Modifier.fillMaxSize())
                if (isCurrent) {
                    Box(
                        Modifier.fillMaxSize().clip(Vesper.shapes.artwork).background(Color(0x88000000)),
                        contentAlignment = Alignment.Center,
                    ) { PlayingBars(isPlaying, color = Color.White) }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) c.accentBright else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.favorite) {
                    Icon(Icons.Rounded.Favorite, null, tint = c.accentBright, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    "${track.artist}  ·  ${formatDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
        if (onMore != null) {
            PressableIcon(Icons.Rounded.MoreVert, "Действия", onClick = onMore, tint = c.textMuted, size = 20.dp)
        }
    }
}

@Composable
fun VideoRow(
    video: VideoEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Vesper.colors
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumb(video, Modifier.width(128.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (video.favorite) {
                    Icon(Icons.Rounded.Favorite, null, tint = c.accentBright, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                val res = if (video.height > 0) "${minOf(video.width, video.height)}p  ·  " else ""
                Text(
                    res + formatSize(video.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) trailing()
        if (onMore != null) {
            PressableIcon(Icons.Rounded.MoreVert, "Действия", onClick = onMore, tint = c.textMuted, size = 20.dp)
        }
    }
}

/** 16:9 thumbnail with duration badge and watched-progress line. */
@Composable
fun VideoThumb(video: VideoEntity, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    Box(modifier.aspectRatio(16f / 9f).clip(Vesper.shapes.small)) {
        Artwork(
            uri = video.uri,
            seed = video.title,
            isVideo = true,
            size = ArtSize.Medium,
            shape = Vesper.shapes.small,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            formatDuration(video.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(Vesper.shapes.small)
                .background(Color(0xB3000000))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
        val progress = video.progress
        if (progress > 0.01f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000)))
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(c.accentBright),
            )
        }
    }
}

/** Square tile used by horizontal carousels (recent, favourites, albums). */
@Composable
fun MediaTile(
    title: String,
    subtitle: String,
    artUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp,
    onLongClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
) {
    val c = Vesper.colors
    Column(
        modifier
            .width(width)
            .clip(Vesper.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 4.dp),
    ) {
        Box {
            Artwork(
                uri = artUri,
                seed = title,
                size = ArtSize.Medium,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (highlighted) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(Vesper.shapes.chip)
                        .background(Color(0xAA000000)).padding(6.dp),
                ) { PlayingBars(true, color = Color.White) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (highlighted) c.accentBright else c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Vesper.colors
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (subtitle != null) OverlineText(subtitle, color = c.ornament)
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        if (Vesper.theme.ornament == Ornament.GOTHIC || Vesper.theme.ornament == Ornament.NEON) {
            OrnamentDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val c = Vesper.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).clip(Vesper.shapes.large).background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = c.accentBright, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(primaryLabel, onPrimary)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(secondaryLabel, onSecondary)
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Vesper.colors
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = c.accentBright, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun StatLine(label: String, value: String) {
    val c = Vesper.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary)
    }
}
