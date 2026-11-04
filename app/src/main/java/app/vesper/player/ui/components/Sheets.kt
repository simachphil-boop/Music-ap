package app.vesper.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vesper.player.data.db.PlaylistSummary
import app.vesper.player.ui.theme.Vesper
import kotlinx.coroutines.launch

data class SheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun sheetShape(): Shape {
    val spec = Vesper.theme.shapes
    return if (spec.cut) CutCornerShape(topStart = 18.dp, topEnd = 18.dp)
    else RoundedCornerShape(topStart = (spec.large + 6).dp, topEnd = (spec.large + 6).dp)
}

/** Themed modal sheet wrapper with a slim drag handle. */
@Composable
fun VesperSheet(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(dismiss: (after: () -> Unit) -> Unit) -> Unit,
) {
    val c = Vesper.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: (() -> Unit) -> Unit = { after ->
        scope.launch { state.hide() }.invokeOnCompletion {
            onDismiss()
            after()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        contentColor = c.textPrimary,
        scrimColor = androidx.compose.ui.graphics.Color(0x99000000),
        shape = sheetShape(),
        dragHandle = {
            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(c.outline),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            content(dismiss)
        }
    }
}

@Composable
fun ActionSheet(
    onDismiss: () -> Unit,
    actions: List<SheetAction>,
    header: (@Composable () -> Unit)? = null,
) {
    val c = Vesper.colors
    VesperSheet(onDismiss) { dismiss ->
        if (header != null) {
            header()
            OrnamentDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        actions.forEach { action ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { dismiss(action.onClick) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    action.icon, null,
                    tint = if (action.destructive) c.danger else c.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    action.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (action.destructive) c.danger else c.textPrimary,
                )
            }
        }
    }
}

/** Header used by track/video action sheets. */
@Composable
fun SheetMediaHeader(title: String, subtitle: String, artUri: String?, isVideo: Boolean = false) {
    val c = Vesper.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artUri, title, Modifier.size(54.dp), isVideo = isVideo)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AddToPlaylistSheet(
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onPick: (PlaylistSummary) -> Unit,
    onCreate: (name: String) -> Unit,
) {
    val c = Vesper.colors
    var creating by remember { mutableStateOf(false) }
    VesperSheet(onDismiss) { dismiss ->
        Text(
            "Добавить в плейлист",
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(Vesper.shapes.small).background(c.highlight), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = c.accentBright)
            }
            Spacer(Modifier.width(16.dp))
            Text("Новый плейлист", style = MaterialTheme.typography.titleMedium, color = c.accentBright)
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(playlists, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { dismiss { onPick(p) } }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistCover(p.coverPath, p.name, Modifier.size(44.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (p.kind == 1) videosCount(p.itemCount) else tracksCount(p.itemCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textMuted,
                        )
                    }
                }
            }
        }
        if (playlists.isEmpty()) {
            Text(
                "Плейлистов пока нет — создайте первый.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
    if (creating) {
        TextInputDialog(
            title = "Новый плейлист",
            label = "Название",
            initial = "",
            confirmLabel = "Создать",
            onDismiss = { creating = false },
            onConfirm = {
                creating = false
                onCreate(it)
                onDismiss()
            },
        )
    }
}

/** Playlist artwork: user-chosen cover or a themed placeholder. */
@Composable
fun PlaylistCover(coverPath: String?, name: String, modifier: Modifier = Modifier, shape: Shape = Vesper.shapes.artwork) {
    val uri = coverPath?.let { android.net.Uri.fromFile(java.io.File(it)).toString() }
    CoverImage(uri, name, modifier, shape)
}

@Composable
private fun CoverImage(uri: String?, name: String, modifier: Modifier, shape: Shape) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, uri) {
        value = if (uri == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
        }
    }
    Box(modifier.clip(shape)) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = remember(bmp) { bmp.asImageBitmap() },
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ArtPlaceholder(seed = name, isVideo = false, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    VesperDialog(
        title = title,
        onDismiss = onDismiss,
        confirmLabel = confirmLabel,
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onConfirm(text.trim()) },
    ) {
        VesperTextField(text, { text = it }, label)
    }
}

@Composable
fun VesperTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val c = Vesper.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = Vesper.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accentBright,
            unfocusedBorderColor = c.outline,
            focusedLabelColor = c.accentBright,
            unfocusedLabelColor = c.textMuted,
            cursorColor = c.accentBright,
            focusedTextColor = c.textPrimary,
            unfocusedTextColor = c.textPrimary,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun VesperDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissLabel: String = "Отмена",
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Vesper.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.textPrimary,
        textContentColor = c.textSecondary,
        shape = Vesper.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, color = if (destructive) c.danger else if (confirmEnabled) c.accentBright else c.textMuted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel, color = c.textSecondary) }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    VesperDialog(title, onDismiss, confirmLabel, onConfirm, destructive = true) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Vesper.colors.textSecondary)
    }
}

/** Radio-style list of choices inside a sheet. */
@Composable
fun ChoiceSheet(
    title: String,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val c = Vesper.colors
    VesperSheet(onDismiss) { dismiss ->
        Text(title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        options.forEachIndexed { i, label ->
            Row(
                Modifier.fillMaxWidth().clickable { dismiss { onSelect(i) } }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (i == selected) c.accentBright else c.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (i == selected) Icon(Icons.Rounded.Check, null, tint = c.accentBright)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
