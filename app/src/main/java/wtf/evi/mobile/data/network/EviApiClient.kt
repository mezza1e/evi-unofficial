package wtf.evi.mobile.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import wtf.evi.mobile.data.model.Album
import wtf.evi.mobile.data.model.AlbumDetailResponse
import wtf.evi.mobile.data.model.Artist
import wtf.evi.mobile.data.model.ArtistArtResponse
import wtf.evi.mobile.data.model.ArtistDetailResponse
import wtf.evi.mobile.data.model.AuthMeResponse
import wtf.evi.mobile.data.model.EVI_BASE_URL
import wtf.evi.mobile.data.model.FollowsResponse
import wtf.evi.mobile.data.model.FriendItem
import wtf.evi.mobile.data.model.FriendsResponse
import wtf.evi.mobile.data.model.HistoryEntry
import wtf.evi.mobile.data.model.HistoryResponse
import wtf.evi.mobile.data.model.ImportResponse
import wtf.evi.mobile.data.model.LikesResponse
import wtf.evi.mobile.data.model.LyricsResponse
import wtf.evi.mobile.data.model.Mix
import wtf.evi.mobile.data.model.MixesResponse
import wtf.evi.mobile.data.model.NewReleasesResponse
import wtf.evi.mobile.data.model.Playlist
import wtf.evi.mobile.data.model.PlaylistSingleResponse
import wtf.evi.mobile.data.model.PlaylistsResponse
import wtf.evi.mobile.data.model.SearchResponse
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.model.SongsResponse
import wtf.evi.mobile.data.model.TicketPollResponse
import wtf.evi.mobile.data.model.TicketResponse
import wtf.evi.mobile.data.model.TrendingResponse
import wtf.evi.mobile.data.model.WeeklyPlaylist
import wtf.evi.mobile.data.model.WeeklyResponse
import java.io.IOException
import java.util.Calendar
import java.util.UUID

class EviApiClient(
    private val httpClient: OkHttpClient,
    private val cookieJar: SessionCookieJar
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val albumCache = object : LinkedHashMap<String, AlbumDetailResponse>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AlbumDetailResponse>?): Boolean {
            return size > 64
        }
    }

    private val artistCache = object : LinkedHashMap<String, ArtistDetailResponse>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtistDetailResponse>?): Boolean {
            return size > 64
        }
    }

    private val searchCache = object : LinkedHashMap<String, SearchResponse>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchResponse>?): Boolean {
            return size > 128
        }
    }

    fun clearCaches() {
        synchronized(albumCache) { albumCache.clear() }
        synchronized(artistCache) { artistCache.clear() }
        synchronized(searchCache) { searchCache.clear() }
    }

    private suspend inline fun <reified T> getJson(pathAndQuery: String): T {
        val url = if (pathAndQuery.startsWith("http")) pathAndQuery else "$EVI_BASE_URL$pathAndQuery"
        val req = Request.Builder().url(url).get().build()
        return executeJson(req)
    }

    private suspend inline fun <reified T> postJson(path: String, body: JsonObject): T {
        val reqBody = json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType)
        val req = Request.Builder().url("$EVI_BASE_URL$path").post(reqBody).build()
        return executeJson(req)
    }

    private suspend inline fun <reified T> executeJson(request: Request): T = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiHttpException(response.code, text.ifBlank { "HTTP ${response.code}" })
            }
            json.decodeFromString<T>(text)
        }
    }

    private suspend fun postFireAndForget(path: String, body: JsonObject) {
        withContext(Dispatchers.IO) {
            runCatching {
                val reqBody = json.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonMediaType)
                val req = Request.Builder().url("$EVI_BASE_URL$path").post(reqBody).build()
                httpClient.newCall(req).execute().close()
            }
        }
    }

    // --- Auth ---

    suspend fun createTicket(): TicketResponse = postJson("/api/auth/ticket", buildJsonObject {})

    suspend fun pollTicket(ticketId: String): TicketPollResponse {
        val url = "$EVI_BASE_URL/api/auth/ticket".toHttpUrl().newBuilder()
            .addQueryParameter("id", ticketId)
            .build()
            .toString()
        val res = getJson<TicketPollResponse>(url)
        val token = res.session ?: res.token
        if (res.ok && !token.isNullOrBlank()) {
            cookieJar.setSessionToken(token)
        }
        return res
    }

    suspend fun getAuthMe(): AuthMeResponse = getJson("/api/auth/me")

    suspend fun logout() {
        postFireAndForget("/api/auth/logout", buildJsonObject {})
        cookieJar.clear()
    }

    // --- Catalog & Discovery ---

    suspend fun getTrending(): TrendingResponse = getJson("/api/catalog/trending")

    suspend fun getNewReleases(): List<Album> = getJson<NewReleasesResponse>("/api/new-releases").albums

    suspend fun search(query: String): SearchResponse {
        val q = query.trim().lowercase()
        synchronized(searchCache) {
            searchCache[q]?.let { return it }
        }
        val url = "$EVI_BASE_URL/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("ranked", "1")
            .build()
            .toString()
        val res = getJson<SearchResponse>(url)
        synchronized(searchCache) {
            searchCache[q] = res
        }
        return res
    }

    suspend fun getAlbum(albumId: String): AlbumDetailResponse {
        synchronized(albumCache) {
            albumCache[albumId]?.let { return it }
        }
        val url = "$EVI_BASE_URL/api/catalog/albums/$albumId".toHttpUrl().newBuilder()
            .addQueryParameter("song_limit", "100")
            .build()
            .toString()
        val res = getJson<AlbumDetailResponse>(url)
        synchronized(albumCache) {
            albumCache[albumId] = res
        }
        return res
    }

    suspend fun getArtist(artistId: String): ArtistDetailResponse {
        synchronized(artistCache) {
            artistCache[artistId]?.let { return it }
        }
        val url = "$EVI_BASE_URL/api/catalog/artists/$artistId".toHttpUrl().newBuilder()
            .addQueryParameter("album_limit", "25")
            .build()
            .toString()
        val res = getJson<ArtistDetailResponse>(url)
        synchronized(artistCache) {
            artistCache[artistId] = res
        }
        return res
    }

    suspend fun getArtistArt(artistName: String): ArtistArtResponse {
        val url = "$EVI_BASE_URL/api/artist-art".toHttpUrl().newBuilder()
            .addQueryParameter("artist", artistName)
            .build()
            .toString()
        return getJson(url)
    }

    suspend fun getMixes(): List<Mix> {
        val url = "$EVI_BASE_URL/api/mixes".toHttpUrl().newBuilder()
            .addQueryParameter("day", currentDayKey())
            .build()
            .toString()
        return runCatching { getJson<MixesResponse>(url).mixes }.getOrDefault(emptyList())
    }

    suspend fun getMixSongs(index: Int): List<Song> {
        val url = "$EVI_BASE_URL/api/mixes/$index".toHttpUrl().newBuilder()
            .addQueryParameter("day", currentDayKey())
            .build()
            .toString()
        return runCatching { getJson<SongsResponse>(url).songs }.getOrDefault(emptyList())
    }

    suspend fun getWeekly(kind: String): WeeklyPlaylist? {
        val refreshDay = if (kind == "radar") 5 else 1
        val url = "$EVI_BASE_URL/api/weekly/$kind".toHttpUrl().newBuilder()
            .addQueryParameter("week", currentWeekKey(refreshDay))
            .build()
            .toString()
        return runCatching { getJson<WeeklyResponse>(url).playlist }.getOrNull()
    }

    suspend fun getRecommendations(): List<Song> {
        val url = "$EVI_BASE_URL/api/recommend".toHttpUrl().newBuilder()
            .addQueryParameter("daypart", currentDaypart())
            .addQueryParameter("limit", "20")
            .build()
            .toString()
        return runCatching { getJson<SongsResponse>(url).songs }.getOrDefault(emptyList())
    }

    suspend fun getNextSongs(currentSong: Song, recentSongs: List<Song>): List<Song> {
        val url = "$EVI_BASE_URL/api/next".toHttpUrl().newBuilder()
            .addQueryParameter("songId", currentSong.id)
            .addQueryParameter("artists", recentSongs.map { it.artistId }.filter { it.isNotBlank() }.distinct().joinToString(","))
            .addQueryParameter("artist", currentSong.artistId)
            .addQueryParameter("artistName", currentSong.artist)
            .addQueryParameter("title", currentSong.title)
            .addQueryParameter("duration", currentSong.duration.toInt().toString())
            .addQueryParameter("recent", recentSongs.takeLast(3).joinToString(",") { it.id })
            .addQueryParameter("daypart", currentDaypart())
            .addQueryParameter("limit", "6")
            .build()
            .toString()
        return runCatching { getJson<SongsResponse>(url).songs }.getOrDefault(emptyList())
    }

    suspend fun getLyrics(song: Song): LyricsResponse {
        val url = "$EVI_BASE_URL/api/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("id", song.id)
            .addQueryParameter("title", song.title)
            .addQueryParameter("artist", song.artist)
            .addQueryParameter("duration", song.duration.toInt().toString())
            .build()
            .toString()
        return getJson(url)
    }

    // --- Library & User Actions ---

    suspend fun getLikes(): List<Song> =
        runCatching { getJson<LikesResponse>("/api/likes").likes }.getOrDefault(emptyList())

    suspend fun likeSong(song: Song) {
        val body = buildJsonObject {
            put("action", "like")
            put("song", songToJson(song))
        }
        postFireAndForget("/api/likes", body)
    }

    suspend fun unlikeSong(songId: String) {
        val body = buildJsonObject {
            put("action", "unlike")
            put("id", songId)
        }
        postFireAndForget("/api/likes", body)
    }

    suspend fun getPlaylists(): List<Playlist> =
        runCatching { getJson<PlaylistsResponse>("/api/playlists").playlists }.getOrDefault(emptyList())

    suspend fun createPlaylist(name: String, initialSongs: List<Song> = emptyList()): Playlist? {
        val id = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("action", "save")
            put("playlist", buildJsonObject {
                put("id", id)
                put("name", name.trim().ifBlank { "New Playlist" })
                put("songs", buildJsonArray { initialSongs.forEach { add(songToJson(it)) } })
            })
        }
        return postJson<PlaylistSingleResponse>("/api/playlists", body).playlist
    }

    suspend fun renamePlaylist(playlist: Playlist, newName: String): Playlist? {
        val body = buildJsonObject {
            put("action", "save")
            put("playlist", buildJsonObject {
                put("id", playlist.id)
                put("name", newName.trim().ifBlank { playlist.name })
                put("songs", buildJsonArray { playlist.songs.forEach { add(songToJson(it)) } })
                put("revision", playlist.revision)
            })
        }
        return postJson<PlaylistSingleResponse>("/api/playlists", body).playlist
    }

    suspend fun addSongsToPlaylist(playlist: Playlist, songs: List<Song>): Playlist? {
        val body = buildJsonObject {
            put("action", "tracks")
            put("id", playlist.id)
            if (playlist.isShared && !playlist.ownerId.isNullOrBlank()) {
                put("ownerId", playlist.ownerId)
            }
            put("add", buildJsonArray { songs.forEach { add(songToJson(it)) } })
        }
        return postJson<PlaylistSingleResponse>("/api/playlists", body).playlist
    }

    suspend fun removeSongFromPlaylist(playlist: Playlist, songId: String): Playlist? {
        val body = buildJsonObject {
            put("action", "tracks")
            put("id", playlist.id)
            if (playlist.isShared && !playlist.ownerId.isNullOrBlank()) {
                put("ownerId", playlist.ownerId)
            }
            put("remove", buildJsonArray { add(JsonPrimitive(songId)) })
        }
        return postJson<PlaylistSingleResponse>("/api/playlists", body).playlist
    }

    suspend fun deletePlaylist(playlistId: String) {
        val body = buildJsonObject {
            put("action", "delete")
            put("id", playlistId)
        }
        postFireAndForget("/api/playlists", body)
    }

    suspend fun importPlaylist(url: String): ImportResponse {
        val body = buildJsonObject {
            put("url", url.trim())
        }
        return postJson("/api/import", body)
    }

    suspend fun getFollows(): List<Artist> =
        runCatching { getJson<FollowsResponse>("/api/follows").artists }.getOrDefault(emptyList())

    suspend fun followArtist(artist: Artist) {
        val body = buildJsonObject {
            put("action", "follow")
            put("artist", buildJsonObject {
                put("id", artist.id)
                put("name", artist.name)
                put("image_url", artist.imageUrl)
            })
        }
        postFireAndForget("/api/follows", body)
    }

    suspend fun unfollowArtist(artistId: String) {
        val body = buildJsonObject {
            put("action", "unfollow")
            put("id", artistId)
        }
        postFireAndForget("/api/follows", body)
    }

    suspend fun getHistory(): List<HistoryEntry> =
        runCatching { getJson<HistoryResponse>("/api/history").plays }.getOrDefault(emptyList())

    suspend fun getFriends(): List<FriendItem> =
        runCatching { getJson<FriendsResponse>("/api/friends").friends }.getOrDefault(emptyList())

    // --- Playback Telemetry & Social Activity ---

    suspend fun recordPlayed(song: Song) {
        val body = buildJsonObject {
            put("action", "played")
            put("song", songToJson(song))
            put("daypart", currentDaypart())
        }
        postFireAndForget("/api/history", body)
    }

    suspend fun recordSkipped(song: Song) {
        val body = buildJsonObject {
            put("action", "skipped")
            put("song", songToJson(song))
        }
        postFireAndForget("/api/history", body)
    }

    suspend fun recordListened(song: Song, fraction: Double) {
        val body = buildJsonObject {
            put("action", "listened")
            put("song", songToJson(song))
            put("fraction", fraction.coerceIn(0.0, 1.0))
        }
        postFireAndForget("/api/history", body)
    }

    suspend fun recordListenMs(ms: Long) {
        if (ms <= 0L) return
        val body = buildJsonObject { put("ms", ms) }
        postFireAndForget("/api/stats/listen", body)
    }

    suspend fun sendHeartbeat(song: Song?, positionMs: Long, playing: Boolean) {
        val body = buildJsonObject {
            if (song != null) put("song", songToJson(song))
            put("positionMs", positionMs)
            put("playing", playing)
        }
        postFireAndForget("/api/social/heartbeat", body)
    }

    private fun songToJson(song: Song): JsonObject = buildJsonObject {
        put("id", song.id)
        put("title", song.title)
        put("artist", song.artist)
        put("artist_id", song.artistId)
        put("album", song.album)
        put("album_id", song.albumId)
        put("duration", song.duration)
        put("year", song.year)
        put("explicit", song.explicit)
        put("cover_art", song.coverArt)
        put("cover_url", song.coverUrl.ifBlank { "/api/artwork?id=${song.id}" })
        put("cover_url_large", song.coverUrlLarge.ifBlank { "/api/artwork?id=${song.id}" })
        put("url", song.url.ifBlank { "/api/stream/${song.id}" })
    }

    companion object {
        fun currentDaypart(): String {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..11 -> "morning"
                in 12..16 -> "afternoon"
                in 17..20 -> "evening"
                else -> "night"
            }
        }

        fun currentDayKey(): String {
            val cal = Calendar.getInstance()
            return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }

        fun currentWeekKey(refreshDay: Int): String {
            val cal = Calendar.getInstance()
            val jsDay = cal.get(Calendar.DAY_OF_WEEK) - 1 // Sunday = 0 .. Saturday = 6
            val delta = (jsDay - refreshDay + 7) % 7
            cal.add(Calendar.DAY_OF_MONTH, -delta)
            return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }
    }
}

class ApiHttpException(val code: Int, override val message: String) : IOException(message)
