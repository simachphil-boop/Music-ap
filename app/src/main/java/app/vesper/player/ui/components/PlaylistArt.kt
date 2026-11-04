package app.vesper.player.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import app.vesper.player.data.db.PlaylistKind
import app.vesper.player.data.db.PlaylistSummary
import app.vesper.player.ui.LibraryState
import app.vesper.player.ui.VideoState
import app.vesper.player.ui.theme.Vesper

/** Custom cover if set, otherwise a 2×2 mosaic of the first items, otherwise a placeholder. */
@Composable
fun PlaylistArt(
    playlist: PlaylistSummary,
    previewIds: List<Long>,
    library: LibraryState,
    videos: VideoState,
    modifier: Modifier = Modifier,
) {
    if (playlist.coverPath != null) {
        PlaylistCover(playlist.coverPath, playlist.name, modifier)
        return
    }
    val isVideo = playlist.kind == PlaylistKind.VIDEO
    val uris = previewIds.mapNotNull { id ->
        if (isVideo) videos.byId[id]?.uri else library.trackById[id]?.uri
    }.distinct()
    when {
        uris.size >= 4 -> Column(modifier.clip(Vesper.shapes.artwork)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Artwork(uris[0], playlist.name, Modifier.weight(1f).fillMaxSize(), isVideo = isVideo, shape = RectangleShape)
                Artwork(uris[1], playlist.name, Modifier.weight(1f).fillMaxSize(), isVideo = isVideo, shape = RectangleShape)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Artwork(uris[2], playlist.name, Modifier.weight(1f).fillMaxSize(), isVideo = isVideo, shape = RectangleShape)
                Artwork(uris[3], playlist.name, Modifier.weight(1f).fillMaxSize(), isVideo = isVideo, shape = RectangleShape)
            }
        }
        uris.isNotEmpty() -> Artwork(uris[0], playlist.name, modifier, isVideo = isVideo, size = ArtSize.Medium)
        else -> Box(modifier.clip(Vesper.shapes.artwork)) {
            ArtPlaceholder(playlist.name, isVideo = false, modifier = Modifier.fillMaxSize())
        }
    }
}
