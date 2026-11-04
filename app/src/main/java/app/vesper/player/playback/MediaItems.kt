package app.vesper.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.vesper.player.data.db.TrackEntity

const val ART_SCHEME = "vesperart"
const val EXTRA_TRACK_URI = "vesper.track_uri"
const val EXTRA_DURATION = "vesper.duration"

fun TrackEntity.toMediaItem(): MediaItem {
    val mediaUri = Uri.parse(uri)
    val extras = Bundle().apply {
        putString(EXTRA_TRACK_URI, uri)
        putLong(EXTRA_DURATION, durationMs)
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setGenre(genre)
        .setTrackNumber(trackNumber.takeIf { it > 0 })
        .setReleaseYear(year.takeIf { it > 0 })
        .setArtworkUri(artworkUriFor(uri))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(mediaUri)
        .setMimeType(mimeType.takeIf { it.isNotBlank() })
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(mediaUri).build())
        .setMediaMetadata(metadata)
        .build()
}

/** Encodes the track URI into a custom artwork URI handled by [ArtworkBitmapLoader]. */
fun artworkUriFor(trackUri: String): Uri =
    Uri.Builder().scheme(ART_SCHEME).authority("track").appendQueryParameter("u", trackUri).build()

fun MediaItem.trackUri(): String? =
    mediaMetadata.extras?.getString(EXTRA_TRACK_URI)
        ?: requestMetadata.mediaUri?.toString()
        ?: localConfiguration?.uri?.toString()

/** Restores the playable URI for items that crossed the controller/session boundary. */
fun MediaItem.withResolvedUri(): MediaItem {
    if (localConfiguration != null) return this
    val uri = requestMetadata.mediaUri ?: mediaMetadata.extras?.getString(EXTRA_TRACK_URI)?.let(Uri::parse)
    return if (uri != null) buildUpon().setUri(uri).build() else this
}
