package wtf.evi.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wtf.evi.mobile.AppContainer
import wtf.evi.mobile.data.model.Album
import wtf.evi.mobile.data.model.AlbumDetailResponse
import wtf.evi.mobile.data.model.Artist
import wtf.evi.mobile.data.model.ArtistArtResponse
import wtf.evi.mobile.data.model.ArtistDetailResponse
import wtf.evi.mobile.data.model.LyricsResponse
import wtf.evi.mobile.data.model.Mix
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.model.WeeklyPlaylist
import java.io.File

@Serializable
data class CachedHomePayload(
    val trendingSongs: List<Song> = emptyList(),
    val trendingAlbums: List<Album> = emptyList(),
    val newReleases: List<Album> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    val discoverWeekly: WeeklyPlaylist? = null,
    val releaseRadar: WeeklyPlaylist? = null,
    val recommendations: List<Song> = emptyList()
)

data class HomeUiState(
    val loading: Boolean = true,
    val trendingSongs: List<Song> = emptyList(),
    val trendingAlbums: List<Album> = emptyList(),
    val newReleases: List<Album> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    val discoverWeekly: WeeklyPlaylist? = null,
    val releaseRadar: WeeklyPlaylist? = null,
    val recommendations: List<Song> = emptyList(),
    val error: String? = null
)

data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val loading: Boolean = false,
    val songs: List<Song> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val error: String? = null
)

class EviViewModel(val container: AppContainer) : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val homeCacheFile = File(container.cacheDir, "home_catalog_cache_v1.json")

    private fun readInitialCachedHome(): HomeUiState? {
        return runCatching {
            if (homeCacheFile.exists()) {
                val text = homeCacheFile.readText()
                val payload = json.decodeFromString<CachedHomePayload>(text)
                if (payload.trendingSongs.isNotEmpty() || payload.newReleases.isNotEmpty()) {
                    HomeUiState(
                        loading = false,
                        trendingSongs = payload.trendingSongs,
                        trendingAlbums = payload.trendingAlbums,
                        newReleases = payload.newReleases,
                        mixes = payload.mixes,
                        discoverWeekly = payload.discoverWeekly,
                        releaseRadar = payload.releaseRadar,
                        recommendations = payload.recommendations,
                        error = null
                    )
                } else null
            } else null
        }.getOrNull()
    }

    private val _homeState = MutableStateFlow(readInitialCachedHome() ?: HomeUiState(loading = true))
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _lyricsCache = mutableMapOf<String, LyricsResponse>()
    private val _currentLyrics = MutableStateFlow<LyricsResponse?>(null)
    val currentLyrics: StateFlow<LyricsResponse?> = _currentLyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private var searchJob: Job? = null
    private var lyricsJob: Job? = null

    init {
        // Speculatively preload first 8 cached covers into memory immediately on launch
        val initial = _homeState.value.trendingSongs
        if (initial.isNotEmpty()) {
            initial.take(8).forEach { song ->
                val cover = song.resolvedCoverUrl(256)
                if (cover.isNotBlank()) container.preloadImage(cover)
            }
        }
        refreshAll()
    }

    private fun persistCachedHome(state: HomeUiState) {
        if (state.trendingSongs.isEmpty() && state.newReleases.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val payload = CachedHomePayload(
                    trendingSongs = state.trendingSongs,
                    trendingAlbums = state.trendingAlbums,
                    newReleases = state.newReleases,
                    mixes = state.mixes,
                    discoverWeekly = state.discoverWeekly,
                    releaseRadar = state.releaseRadar,
                    recommendations = state.recommendations
                )
                val text = json.encodeToString(CachedHomePayload.serializer(), payload)
                homeCacheFile.writeText(text)
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            // Only show full loading spinner if we don't already have cached items showing
            val hasContent = _homeState.value.trendingSongs.isNotEmpty() || _homeState.value.newReleases.isNotEmpty()
            if (!hasContent) {
                _homeState.value = _homeState.value.copy(loading = true, error = null)
            }
            val sessionJob = async { container.refreshSessionAndLibrary() }
            val trendingJob = async { runCatching { container.apiClient.getTrending() }.getOrNull() }
            val releasesJob = async { runCatching { container.apiClient.getNewReleases() }.getOrDefault(emptyList()) }

            sessionJob.await()
            val trending = trendingJob.await()
            val releases = releasesJob.await()

            val signedIn = container.currentUser.value != null
            var mixes = emptyList<Mix>()
            var discover: WeeklyPlaylist? = null
            var radar: WeeklyPlaylist? = null
            var recs = emptyList<Song>()

            if (signedIn) {
                val mixesJob = async { container.apiClient.getMixes() }
                val discoverJob = async { container.apiClient.getWeekly("discover") }
                val radarJob = async { container.apiClient.getWeekly("radar") }
                val recJob = async { container.apiClient.getRecommendations() }
                mixes = mixesJob.await()
                discover = discoverJob.await()
                radar = radarJob.await()
                recs = recJob.await()
            }

            val cur = _homeState.value
            val finalTrendingSongs = trending?.songs.takeIf { !it.isNullOrEmpty() } ?: cur.trendingSongs
            val finalTrendingAlbums = trending?.albums.takeIf { !it.isNullOrEmpty() } ?: cur.trendingAlbums
            val finalNewReleases = releases.takeIf { it.isNotEmpty() } ?: cur.newReleases
            val finalMixes = mixes.takeIf { it.isNotEmpty() } ?: cur.mixes
            val finalDiscover = discover ?: cur.discoverWeekly
            val finalRadar = radar ?: cur.releaseRadar
            val finalRecs = recs.takeIf { it.isNotEmpty() } ?: cur.recommendations

            val newState = HomeUiState(
                loading = false,
                trendingSongs = finalTrendingSongs,
                trendingAlbums = finalTrendingAlbums,
                newReleases = finalNewReleases,
                mixes = finalMixes,
                discoverWeekly = finalDiscover,
                releaseRadar = finalRadar,
                recommendations = finalRecs,
                error = if (finalTrendingSongs.isEmpty() && finalNewReleases.isEmpty()) "Couldn't load Evi catalog" else null
            )
            _homeState.value = newState
            persistCachedHome(newState)
            finalTrendingSongs.take(8).forEach { s ->
                val c = s.resolvedCoverUrl(256)
                if (c.isNotBlank()) container.preloadImage(c)
            }
        }
    }

    fun onSearchQueryChange(newQuery: String) {
        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            searchJob?.cancel()
            _searchState.value = SearchUiState(query = "")
        } else {
            // Only update the input field text; keep previous submitted results on screen until Enter is pressed
            _searchState.value = _searchState.value.copy(query = newQuery)
        }
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchJob?.cancel()
            _searchState.value = SearchUiState(query = "")
            return
        }
        container.settingsStore.addRecentSearch(trimmed)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.value = _searchState.value.copy(
                query = query,
                submittedQuery = trimmed,
                loading = true,
                error = null
            )
            val result = runCatching { container.apiClient.search(trimmed) }
            result.onSuccess { res ->
                _searchState.value = SearchUiState(
                    query = query,
                    submittedQuery = trimmed,
                    loading = false,
                    songs = res.results,
                    artists = res.artists,
                    error = null
                )
                // Speculative pre-fetching for ALL returned songs and artists:
                // Pre-warm thumbnails into memory cache so lower items load instantly upon scrolling
                res.results.forEach { song ->
                    val c = song.resolvedCoverUrl(128)
                    if (c.isNotBlank()) container.preloadImage(c)
                }
                res.artists.forEach { artist ->
                    val c = artist.resolvedImageUrl()
                    if (c.isNotBlank()) container.preloadImage(c)
                }
            }.onFailure { err ->
                _searchState.value = _searchState.value.copy(
                    query = query,
                    submittedQuery = trimmed,
                    loading = false,
                    error = err.message ?: "Search failed"
                )
            }
        }
    }

    fun loadLyricsFor(song: Song?) {
        lyricsJob?.cancel()
        if (song == null || song.id.isBlank()) {
            _currentLyrics.value = null
            _lyricsLoading.value = false
            return
        }
        val cached = _lyricsCache[song.id]
        if (cached != null) {
            _currentLyrics.value = cached
            _lyricsLoading.value = false
            return
        }
        _currentLyrics.value = null
        _lyricsLoading.value = true
        lyricsJob = viewModelScope.launch {
            val res = runCatching { container.apiClient.getLyrics(song) }.getOrNull()
            if (res != null) {
                _lyricsCache[song.id] = res
            }
            _currentLyrics.value = res
            _lyricsLoading.value = false
        }
    }

    suspend fun loadAlbumDetail(albumId: String): AlbumDetailResponse? =
        runCatching { container.apiClient.getAlbum(albumId) }.getOrNull()

    suspend fun loadArtistDetail(artistId: String, artistNameHint: String): Pair<ArtistDetailResponse?, ArtistArtResponse?> {
        val detail = runCatching { container.apiClient.getArtist(artistId) }.getOrNull()
        val name = detail?.artist?.name?.takeIf { it.isNotBlank() } ?: artistNameHint
        val art = if (name.isNotBlank()) {
            runCatching { container.apiClient.getArtistArt(name) }.getOrNull()
        } else null
        return detail to art
    }

    suspend fun loadMixSongs(index: Int): List<Song> =
        container.apiClient.getMixSongs(index)

    fun importPlaylistUrl(url: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val res = runCatching { container.apiClient.importPlaylist(url) }
            res.onSuccess { imp ->
                if (!imp.error.isNullOrBlank()) {
                    onDone(imp.error)
                } else if (imp.tracks.isEmpty()) {
                    onDone(imp.warning ?: "No matching songs found")
                } else {
                    val name = imp.name?.takeIf { it.isNotBlank() } ?: "Imported Playlist"
                    container.createPlaylist(name, imp.tracks)
                    onDone("Imported ${imp.tracks.size} songs into \"$name\"")
                }
            }.onFailure { err ->
                onDone(err.message ?: "Import failed")
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return EviViewModel(container) as T
        }
    }
}
