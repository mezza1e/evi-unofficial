package wtf.evi.mobile.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.Song
import java.io.File

object MediaItemMapper {
    private const val KEY_ID = "evi_id"
    private const val KEY_TITLE = "evi_title"
    private const val KEY_ARTIST = "evi_artist"
    private const val KEY_ARTIST_ID = "evi_artist_id"
    private const val KEY_ALBUM = "evi_album"
    private const val KEY_ALBUM_ID = "evi_album_id"
    private const val KEY_DURATION = "evi_duration"
    private const val KEY_YEAR = "evi_year"
    private const val KEY_EXPLICIT = "evi_explicit"
    private const val KEY_COVER_ART = "evi_cover_art"
    private const val KEY_COVER_URL = "evi_cover_url"
    private const val KEY_COVER_URL_LARGE = "evi_cover_url_large"
    private const val KEY_URL = "evi_url"
    private const val KEY_IS_AUTOPLAY = "evi_is_autoplay"

    fun toMediaItem(
        song: Song,
        quality: AudioQuality,
        localAudioFile: File? = null,
        localThumbFile: File? = null,
        isAutoplay: Boolean = false
    ): MediaItem {
        val extras = Bundle().apply {
            putString(KEY_ID, song.id)
            putString(KEY_TITLE, song.title)
            putString(KEY_ARTIST, song.artist)
            putString(KEY_ARTIST_ID, song.artistId)
            putString(KEY_ALBUM, song.album)
            putString(KEY_ALBUM_ID, song.albumId)
            putDouble(KEY_DURATION, song.duration)
            putString(KEY_YEAR, song.year)
            putBoolean(KEY_EXPLICIT, song.explicit)
            putString(KEY_COVER_ART, song.coverArt)
            putString(KEY_COVER_URL, song.coverUrl)
            putString(KEY_COVER_URL_LARGE, song.coverUrlLarge)
            putString(KEY_URL, song.url)
            putBoolean(KEY_IS_AUTOPLAY, isAutoplay)
        }

        val artUrl = song.resolvedCoverUrl(512)
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(song.title.ifBlank { "Unknown Track" })
            .setArtist(song.artist.ifBlank { "Unknown Artist" })
            .setAlbumTitle(song.album.ifBlank { null })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)

        if (song.durationMs > 0L) {
            metadataBuilder.setDurationMs(song.durationMs)
        }
        if (localThumbFile != null && localThumbFile.exists() && localThumbFile.length() > 0L) {
            metadataBuilder.setArtworkUri(Uri.fromFile(localThumbFile))
        } else if (artUrl.isNotBlank()) {
            metadataBuilder.setArtworkUri(Uri.parse(artUrl))
        }

        val isLocal = localAudioFile != null && localAudioFile.exists() && localAudioFile.length() > 1024L
        val finalUri = if (isLocal) {
            Uri.fromFile(localAudioFile)
        } else {
            Uri.parse(song.resolvedStreamUrl(quality))
        }

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(finalUri)
            .setCustomCacheKey(if (isLocal) null else "${song.id}_${quality.queryValue}")
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun fromMediaItem(item: MediaItem?): Song? {
        if (item == null) return null
        val meta = item.mediaMetadata
        val ex = meta.extras
        val id = ex?.getString(KEY_ID)?.takeIf { it.isNotBlank() } ?: item.mediaId
        if (id.isBlank()) return null
        return Song(
            id = id,
            title = ex?.getString(KEY_TITLE) ?: meta.title?.toString().orEmpty(),
            artist = ex?.getString(KEY_ARTIST) ?: meta.artist?.toString().orEmpty(),
            artistId = ex?.getString(KEY_ARTIST_ID).orEmpty(),
            album = ex?.getString(KEY_ALBUM) ?: meta.albumTitle?.toString().orEmpty(),
            albumId = ex?.getString(KEY_ALBUM_ID).orEmpty(),
            duration = ex?.getDouble(KEY_DURATION) ?: ((meta.durationMs ?: 0L) / 1000.0),
            year = ex?.getString(KEY_YEAR).orEmpty(),
            explicit = ex?.getBoolean(KEY_EXPLICIT) ?: false,
            coverArt = ex?.getString(KEY_COVER_ART).orEmpty(),
            coverUrl = ex?.getString(KEY_COVER_URL).orEmpty(),
            coverUrlLarge = ex?.getString(KEY_COVER_URL_LARGE).orEmpty(),
            url = ex?.getString(KEY_URL).orEmpty()
        )
    }

    fun isAutoplay(item: MediaItem?): Boolean {
        return item?.mediaMetadata?.extras?.getBoolean(KEY_IS_AUTOPLAY) ?: false
    }
}
