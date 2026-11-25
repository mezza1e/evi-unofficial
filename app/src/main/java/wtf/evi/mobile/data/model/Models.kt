package wtf.evi.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val EVI_BASE_URL = "https://evi.wtf"
const val EVI_USER_AGENT = "EviNative/0.1.29"

@Serializable
data class Song(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    @SerialName("artist_id") val artistId: String = "",
    val album: String = "",
    @SerialName("album_id") val albumId: String = "",
    val duration: Double = 0.0,
    val year: String = "",
    val explicit: Boolean = false,
    @SerialName("cover_art") val coverArt: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("cover_url_large") val coverUrlLarge: String = "",
    val url: String = ""
) {
    fun resolvedCoverUrl(size: Int = 512): String {
        val raw = coverUrlLarge.ifBlank { coverUrl }.ifBlank {
            val artId = coverArt.ifBlank { id }
            if (artId.isNotBlank()) "/api/artwork?id=$artId" else ""
        }
        if (raw.isBlank()) return ""
        val abs = if (raw.startsWith("http")) raw else "$EVI_BASE_URL$raw"
        if (!abs.contains("/api/artwork")) return abs
        var out = abs
        if (!out.contains("size=")) {
            val sep = if ('?' in out) '&' else '?'
            out = "$out${sep}size=$size"
        }
        if (!out.contains("v=")) {
            out = "$out&v=3"
        }
        return out
    }

    fun resolvedStreamUrl(quality: AudioQuality): String {
        val rawBase = if (url.startsWith("http")) url else "$EVI_BASE_URL/api/stream/$id"
        val parts = rawBase.split('?', limit = 2)
        val basePath = parts[0]
        val queryParams = if (parts.size > 1 && parts[1].isNotBlank()) {
            parts[1].split('&').filter { param ->
                !param.startsWith("quality=") && !param.startsWith("direct=") && param.isNotBlank()
            }
        } else {
            emptyList()
        }
        val allParams = queryParams + listOf("quality=${quality.queryValue}", "direct=1")
        return "$basePath?${allParams.joinToString("&")}"
    }

    val durationMs: Long
        get() = (duration * 1000.0).toLong().coerceAtLeast(0L)

    val formattedDuration: String
        get() {
            val total = duration.toLong().coerceAtLeast(0L)
            val m = total / 60
            val s = total % 60
            return "%d:%02d".format(m, s)
        }
}

@Serializable
data class Album(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    @SerialName("artist_id") val artistId: String = "",
    val year: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("song_count") val songCount: Int = 0,
    @SerialName("cover_art") val coverArt: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("cover_url_large") val coverUrlLarge: String = ""
) {
    fun resolvedCoverUrl(size: Int = 512): String {
        val raw = coverUrlLarge.ifBlank { coverUrl }.ifBlank {
            val artId = coverArt.ifBlank { id }
            if (artId.isNotBlank()) "/api/artwork?id=$artId" else ""
        }
        if (raw.isBlank()) return ""
        val abs = if (raw.startsWith("http")) raw else "$EVI_BASE_URL$raw"
        if (!abs.contains("/api/artwork")) return abs
        var out = abs
        if (!out.contains("size=")) out = "$out&size=$size"
        if (!out.contains("v=")) out = "$out&v=3"
        return out
    }
}

@Serializable
data class Artist(
    val id: String = "",
    val name: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("image_url_large") val imageUrlLarge: String = "",
    val followers: Long = 0,
    val verified: Boolean = false
) {
    fun resolvedImageUrl(): String {
        val raw = imageUrlLarge.ifBlank { imageUrl }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http")) raw else "$EVI_BASE_URL$raw"
    }
}

@Serializable
data class TrendingResponse(
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
    val country: String = ""
)

@Serializable
data class NewReleasesResponse(
    val albums: List<Album> = emptyList()
)

@Serializable
data class SearchResponse(
    val query: String = "",
    val results: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList()
)

@Serializable
data class AlbumDetailResponse(
    val album: Album = Album(),
    val songs: List<Song> = emptyList()
)

@Serializable
data class ArtistDetailResponse(
    val artist: Artist = Artist(),
    @SerialName("top_songs") val topSongs: List<Song> = emptyList(),
    val albums: List<Album> = emptyList()
)

@Serializable
data class ArtistArtResponse(
    val portrait: String? = null,
    val banner: String? = null,
    val background: String? = null,
    val hero: String? = null,
    val bio: String? = null
)

@Serializable
data class User(
    val id: String = "",
    val username: String = "",
    val displayName: String = "",
    val avatar: String? = null,
    val premium: Boolean = false
) {
    val label: String
        get() = displayName.ifBlank { username.ifBlank { "Evi User" } }

    fun resolvedAvatarUrl(size: Int? = null): String {
        val raw = avatar?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val isAnimated = raw.contains("/a_") || raw.contains("a_") || raw.endsWith(".gif")

        var fullUrl = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "$EVI_BASE_URL$raw"
            id.isNotBlank() -> "https://cdn.discordapp.com/avatars/$id/$raw"
            else -> "$EVI_BASE_URL/api/avatar/$raw"
        }

        if (isAnimated && fullUrl.contains("cdn.discordapp.com/avatars/")) {
            fullUrl = when {
                fullUrl.contains(".png") -> fullUrl.replace(".png", ".gif")
                fullUrl.contains(".webp") -> fullUrl.replace(".webp", ".gif")
                !fullUrl.contains(".gif") -> "$fullUrl.gif"
                else -> fullUrl
            }
        }
        if (size != null) {
            fullUrl = if (fullUrl.contains("?")) {
                if (!fullUrl.contains("size=")) "$fullUrl&size=$size" else fullUrl
            } else {
                "$fullUrl?size=$size"
            }
        }
        return fullUrl
    }
}

@Serializable
data class AuthMeResponse(
    val user: User? = null,
    val admin: Boolean = false,
    val oauthConfigured: Boolean = true
)

@Serializable
data class TicketResponse(
    val id: String = "",
    val code: String = ""
)

@Serializable
data class TicketPollResponse(
    val ok: Boolean = false,
    val session: String? = null,
    val token: String? = null
)

@Serializable
data class Playlist(
    val id: String = "",
    val name: String = "",
    val songs: List<Song> = emptyList(),
    val cover: String? = null,
    val revision: Int = 0,
    val smart: Boolean = false,
    val isShared: Boolean = false,
    val ownerId: String? = null
) {
    fun resolvedCoverUrl(): String {
        val c = cover?.takeIf { it.isNotBlank() }
        if (c != null) return if (c.startsWith("http")) c else "$EVI_BASE_URL$c"
        return songs.firstOrNull()?.resolvedCoverUrl(512) ?: ""
    }
}

@Serializable
data class PlaylistsResponse(
    val playlists: List<Playlist> = emptyList()
)

@Serializable
data class PlaylistSingleResponse(
    val playlist: Playlist? = null,
    val error: String? = null
)

@Serializable
data class LikesResponse(
    val likes: List<Song> = emptyList()
)

@Serializable
data class FollowsResponse(
    val artists: List<Artist> = emptyList()
)

@Serializable
data class HistoryEntry(
    val song: Song = Song(),
    val plays: Int = 1,
    val lastPlayedAt: Long = 0L
)

@Serializable
data class HistoryResponse(
    val plays: List<HistoryEntry> = emptyList()
)

@Serializable
data class Mix(
    val index: Int = 0,
    val title: String = "",
    val subtitle: String = "",
    val artistId: String = "",
    val artistName: String = "",
    val cover: String = "",
    val songs: List<Song> = emptyList()
) {
    fun resolvedCoverUrl(): String {
        val raw = cover.ifBlank { songs.firstOrNull()?.resolvedCoverUrl(512) ?: "" }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http")) raw else "$EVI_BASE_URL$raw"
    }
}

@Serializable
data class MixesResponse(
    val mixes: List<Mix> = emptyList()
)

@Serializable
data class SongsResponse(
    val songs: List<Song> = emptyList()
)

@Serializable
data class WeeklyPlaylist(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val songs: List<Song> = emptyList()
)

@Serializable
data class WeeklyResponse(
    val playlist: WeeklyPlaylist? = null
)

@Serializable
data class LyricWord(
    val word: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L
)

@Serializable
data class LyricLine(
    val words: List<LyricWord> = emptyList(),
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val text: String = "",
    val lyric: String = "",
    val content: String = "",
    val translatedLyric: String = "",
    val romanLyric: String = "",
    val isBG: Boolean = false,
    val isDuet: Boolean = false,
    val singer: String = ""
) {
    val fullText: String
        get() = words.joinToString("") { it.word }.trim()
            .ifBlank { text }
            .ifBlank { lyric }
            .ifBlank { content }
}

@Serializable
data class LyricsResponse(
    val lines: List<LyricLine> = emptyList(),
    val plain: String? = null,
    val lyrics: String? = null,
    val source: String? = null
) {
    fun getResolvedLines(): List<LyricLine> {
        val validLines = lines.filter { it.fullText.isNotBlank() }
        if (validLines.isNotEmpty()) return validLines
        val rawPlain = plain.takeIf { !it.isNullOrBlank() } ?: lyrics
        if (!rawPlain.isNullOrBlank()) {
            val parsed = parseLrcLines(rawPlain)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    companion object {
        fun parseLrcLines(lrcText: String): List<LyricLine> {
            val regex = Regex("""\[(\d{1,2}):(\d{1,2}(?:\.\d{1,3})?)\](.*)""")
            val result = mutableListOf<LyricLine>()
            lrcText.lineSequence().forEach { line ->
                val trimmed = line.trim()
                val match = regex.find(trimmed)
                if (match != null) {
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val secFloat = match.groupValues[2].toFloatOrNull() ?: 0f
                    val textContent = match.groupValues[3].trim()
                    val startMs = (min * 60_000L) + (secFloat * 1000f).toLong()
                    if (textContent.isNotBlank()) {
                        result.add(LyricLine(startTime = startMs, text = textContent))
                    }
                }
            }
            return result.sortedBy { it.startTime }
        }
    }
}

@Serializable
data class ImportResponse(
    val name: String? = null,
    val tracks: List<Song> = emptyList(),
    val warning: String? = null,
    val error: String? = null
)

@Serializable
data class FriendItem(
    val id: String = "",
    val username: String = "",
    val displayName: String = "",
    val avatar: String? = null,
    val song: Song? = null,
    val playing: Boolean = false,
    val updatedAt: Long = 0L
) {
    fun resolvedAvatarUrl(size: Int? = null): String {
        val raw = avatar?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val isAnimated = raw.contains("/a_") || raw.contains("a_") || raw.endsWith(".gif")
        var fullUrl = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "$EVI_BASE_URL$raw"
            id.isNotBlank() -> "https://cdn.discordapp.com/avatars/$id/$raw"
            else -> "$EVI_BASE_URL/api/avatar/$raw"
        }
        if (isAnimated && fullUrl.contains("cdn.discordapp.com/avatars/")) {
            fullUrl = when {
                fullUrl.contains(".png") -> fullUrl.replace(".png", ".gif")
                fullUrl.contains(".webp") -> fullUrl.replace(".webp", ".gif")
                !fullUrl.contains(".gif") -> "$fullUrl.gif"
                else -> fullUrl
            }
        }
        if (size != null) {
            fullUrl = if (fullUrl.contains("?")) {
                if (!fullUrl.contains("size=")) "$fullUrl&size=$size" else fullUrl
            } else {
                "$fullUrl?size=$size"
            }
        }
        return fullUrl
    }
}

@Serializable
data class FriendsResponse(
    val friends: List<FriendItem> = emptyList()
)

enum class AppTheme(val label: String, val bgHex: Long, val surfaceHex: Long, val surfaceElevatedHex: Long) {
    EVI_BLACK("EviBlack", 0xFF09090B, 0xFF121216, 0xFF1B1B22),
    GRAPHITE("Graphite", 0xFF141418, 0xFF1D1E24, 0xFF272830),
    MIDNIGHT("Midnight", 0xFF0A0F1E, 0xFF11182E, 0xFF1A2442),
    PLUM("Plum", 0xFF140B1A, 0xFF1E1228, 0xFF2B1A38),
    FOREST("Forest", 0xFF091410, 0xFF10201A, 0xFF182E26)
}

enum class AccentColor(val label: String, val colorHex: Long) {
    COVER("Cover", 0xFF1ED760),
    WHITE("White", 0xFFFFFFFF),
    PINK("Pink", 0xFFFF4D8D),
    AMBER("Amber", 0xFFFFB020),
    TEAL("Teal", 0xFF1EC8A5),
    VIOLET("Violet", 0xFF9D7BFF),
    LIME("Lime", 0xFF9EE83B)
}

enum class AudioQuality(val label: String, val badge: String, val queryValue: String) {
    LOSSLESS("Lossless (FLAC)", "LOSSLESS", "lossless"),
    HIGH("High (320 kbps)", "320 KBPS", "320"),
    FAST("Data Saver (Fast)", "FAST", "fast")
}

enum class EqPreset(val label: String, val bandWeights: List<Float>) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
    BASS_BOOST("Bass boost", listOf(0.85f, 0.55f, 0f, -0.1f, 0f)),
    TREBLE_BOOST("Treble boost", listOf(-0.1f, 0f, 0.2f, 0.6f, 0.85f)),
    VOCAL("Vocal", listOf(-0.35f, 0.1f, 0.65f, 0.55f, 0.15f)),
    ROCK("Rock", listOf(0.55f, 0.25f, -0.15f, 0.35f, 0.65f)),
    ELECTRONIC("Electronic", listOf(0.75f, 0.4f, -0.25f, 0.45f, 0.7f)),
    HIP_HOP("Hip-hop", listOf(0.8f, 0.5f, 0.05f, 0.15f, 0.4f)),
    POP("Pop", listOf(-0.1f, 0.2f, 0.5f, 0.3f, -0.15f)),
    ACOUSTIC("Acoustic", listOf(0.35f, 0.15f, 0.3f, 0.45f, 0.25f)),
    DANCE("Dance", listOf(0.7f, 0.35f, -0.1f, 0.4f, 0.6f)),
    CLASSICAL("Classical", listOf(0.45f, 0.3f, -0.1f, 0.35f, 0.5f)),
    JAZZ("Jazz", listOf(0.35f, 0.2f, -0.15f, 0.25f, 0.45f)),
    R_AND_B("R&B", listOf(0.6f, 0.45f, 0.1f, 0.25f, 0.5f)),
    LATE_NIGHT("Late night", listOf(-0.25f, 0.2f, 0.45f, 0.25f, -0.2f)),
    CUSTOM("Custom", listOf(0f, 0f, 0f, 0f, 0f))
}

@Serializable
data class AppSettings(
    val theme: AppTheme = AppTheme.EVI_BLACK,
    val accent: AccentColor = AccentColor.COVER,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val eqEnabled: Boolean = true,
    val customEqBands: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val bassBoostStrength: Float = 0f,
    val virtualizerStrength: Float = 0f,
    val autoplayRadio: Boolean = true,
    val recentSearches: List<String> = emptyList()
)
