package wtf.evi.mobile

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import wtf.evi.mobile.data.download.DownloadManager
import wtf.evi.mobile.data.model.Artist
import wtf.evi.mobile.data.model.EVI_USER_AGENT
import wtf.evi.mobile.data.model.FriendItem
import wtf.evi.mobile.data.model.HistoryEntry
import wtf.evi.mobile.data.model.Playlist
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.model.User
import wtf.evi.mobile.data.network.EviApiClient
import wtf.evi.mobile.data.network.SessionCookieJar
import wtf.evi.mobile.data.store.DownloadStore
import wtf.evi.mobile.data.store.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

class EviApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(container.httpClient)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(128L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .allowHardware(true)
            .allowRgb565(false)
            .dispatcher(Dispatchers.IO)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching {
                coil.Coil.imageLoader(this).memoryCache?.clear()
            }
        }
    }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val cacheDir: File = context.cacheDir
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val cookieJar = SessionCookieJar(context)
    val settingsStore = SettingsStore(context)
    val downloadStore = DownloadStore(context)
    val connectionPool = ConnectionPool(maxIdleConnections = 12, keepAliveDuration = 5, TimeUnit.MINUTES)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .cookieJar(cookieJar)
        .cache(Cache(File(context.cacheDir, "http_cache"), 64L * 1024L * 1024L))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", EVI_USER_AGENT)
                .header("Connection", "keep-alive")
                .build()
            chain.proceed(req)
        }
        .build()

    val streamingHttpClient: OkHttpClient = httpClient.newBuilder()
        .cache(null) // Audio streaming relies on ExoPlayer's CacheDataSource; disable OkHttp disk cache to prevent range-request conflicts
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val apiClient = EviApiClient(httpClient, cookieJar)
    val downloadManager = DownloadManager(context, httpClient, downloadStore)

    val mediaCache: SimpleCache by lazy {
        val cacheDir = File(context.cacheDir, "media_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L)
        val dbProvider = StandaloneDatabaseProvider(context)
        SimpleCache(cacheDir, evictor, dbProvider)
    }

    // Shared reactive user & library state synchronized between Service and UI
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _likes = MutableStateFlow<List<Song>>(emptyList())
    val likes: StateFlow<List<Song>> = _likes.asStateFlow()

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _follows = MutableStateFlow<List<Artist>>(emptyList())
    val follows: StateFlow<List<Artist>> = _follows.asStateFlow()

    private val _followedIds = MutableStateFlow<Set<String>>(emptySet())
    val followedIds: StateFlow<Set<String>> = _followedIds.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendItem>>(emptyList())
    val friends: StateFlow<List<FriendItem>> = _friends.asStateFlow()

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    fun emitToast(message: String) {
        _toastEvents.tryEmit(message)
    }

    fun preloadImage(url: String) {
        if (url.isBlank()) return
        val req = coil.request.ImageRequest.Builder(appContext)
            .data(url)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .build()
        coil.Coil.imageLoader(appContext).enqueue(req)
    }

    fun updateSleepTimerRemaining(ms: Long?) {
        _sleepTimerRemainingMs.value = ms
    }

    suspend fun refreshSessionAndLibrary() {
        val me = runCatching { apiClient.getAuthMe() }.getOrNull()
        _currentUser.value = me?.user
        if (me?.user != null) {
            refreshLibraryOnly()
        } else {
            _likes.value = emptyList()
            _likedIds.value = emptySet()
            _playlists.value = emptyList()
            _follows.value = emptyList()
            _followedIds.value = emptySet()
            _history.value = emptyList()
            _friends.value = emptyList()
        }
    }

    suspend fun refreshLibraryOnly() {
        val likesList = apiClient.getLikes()
        _likes.value = likesList
        _likedIds.value = likesList.map { it.id }.toSet()

        _playlists.value = apiClient.getPlaylists()

        val followedList = apiClient.getFollows()
        _follows.value = followedList
        _followedIds.value = followedList.map { it.id }.toSet()

        _history.value = apiClient.getHistory()
        _friends.value = apiClient.getFriends()
    }

    fun toggleLike(song: Song): Boolean {
        val wasLiked = _likedIds.value.contains(song.id)
        if (wasLiked) {
            _likes.value = _likes.value.filterNot { it.id == song.id }
            _likedIds.value = _likedIds.value - song.id
            appScope.launch { apiClient.unlikeSong(song.id) }
            emitToast("Removed from Liked Songs")
        } else {
            _likes.value = listOf(song) + _likes.value.filterNot { it.id == song.id }
            _likedIds.value = _likedIds.value + song.id
            appScope.launch { apiClient.likeSong(song) }
            emitToast("Added to Liked Songs")
        }
        return !wasLiked
    }

    fun toggleFollow(artist: Artist) {
        val wasFollowed = _followedIds.value.contains(artist.id)
        if (wasFollowed) {
            _follows.value = _follows.value.filterNot { it.id == artist.id }
            _followedIds.value = _followedIds.value - artist.id
            appScope.launch { apiClient.unfollowArtist(artist.id) }
            emitToast("Unfollowed ${artist.name}")
        } else {
            _follows.value = listOf(artist) + _follows.value.filterNot { it.id == artist.id }
            _followedIds.value = _followedIds.value + artist.id
            appScope.launch { apiClient.followArtist(artist) }
            emitToast("Following ${artist.name}")
        }
    }

    suspend fun createPlaylist(name: String, songs: List<Song> = emptyList()): Playlist? {
        val created = runCatching { apiClient.createPlaylist(name, songs) }.getOrNull()
        if (created != null) {
            _playlists.value = listOf(created) + _playlists.value
            emitToast("Created \"${created.name}\"")
        } else {
            refreshLibraryOnly()
        }
        return created
    }

    suspend fun addSongToPlaylist(playlist: Playlist, song: Song) {
        val updated = runCatching { apiClient.addSongsToPlaylist(playlist, listOf(song)) }.getOrNull()
        if (updated != null) {
            _playlists.value = _playlists.value.map { if (it.id == updated.id) updated else it }
            emitToast("Added to ${playlist.name}")
        } else {
            emitToast("Sign in to edit playlists")
        }
    }

    suspend fun removeSongFromPlaylist(playlist: Playlist, songId: String) {
        val updated = runCatching { apiClient.removeSongFromPlaylist(playlist, songId) }.getOrNull()
        if (updated != null) {
            _playlists.value = _playlists.value.map { if (it.id == updated.id) updated else it }
            emitToast("Removed from ${playlist.name}")
        }
    }

    suspend fun renamePlaylist(playlist: Playlist, newName: String) {
        val updated = runCatching { apiClient.renamePlaylist(playlist, newName) }.getOrNull()
        if (updated != null) {
            _playlists.value = _playlists.value.map { if (it.id == updated.id) updated else it }
            emitToast("Renamed playlist")
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        apiClient.deletePlaylist(playlistId)
        emitToast("Playlist deleted")
    }

    suspend fun signOut() {
        apiClient.logout()
        _currentUser.value = null
        _likes.value = emptyList()
        _likedIds.value = emptySet()
        _playlists.value = emptyList()
        _follows.value = emptyList()
        _followedIds.value = emptySet()
        _history.value = emptyList()
        _friends.value = emptyList()
        emitToast("Signed out")
    }
}
