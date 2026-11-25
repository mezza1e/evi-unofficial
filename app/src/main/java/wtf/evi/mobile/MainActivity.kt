package wtf.evi.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import wtf.evi.mobile.data.model.Album
import wtf.evi.mobile.data.model.Mix
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.model.WeeklyPlaylist
import wtf.evi.mobile.player.EviPlaybackService
import wtf.evi.mobile.player.PlaybackController
import wtf.evi.mobile.ui.EviViewModel
import wtf.evi.mobile.ui.components.AddToPlaylistSheet
import wtf.evi.mobile.ui.components.AuthSheet
import wtf.evi.mobile.ui.components.EqualizerBottomSheet
import wtf.evi.mobile.ui.components.MiniPlayer
import wtf.evi.mobile.ui.components.NowPlayingDialog
import wtf.evi.mobile.ui.components.SongOptionsSheet
import wtf.evi.mobile.ui.screens.AlbumDetailScreen
import wtf.evi.mobile.ui.screens.ArtistDetailScreen
import wtf.evi.mobile.ui.screens.ExploreScreen
import wtf.evi.mobile.ui.screens.HomeScreen
import wtf.evi.mobile.ui.screens.LibraryScreen
import wtf.evi.mobile.ui.screens.SearchScreen
import wtf.evi.mobile.ui.screens.SettingsScreen
import wtf.evi.mobile.ui.screens.SongCollectionDetailScreen
import wtf.evi.mobile.ui.theme.EviTheme
import wtf.evi.mobile.ui.theme.LocalEviColors

sealed interface DetailRoute {
    data class AlbumRoute(val albumId: String, val hint: Album? = null) : DetailRoute
    data class ArtistRoute(val artistId: String, val nameHint: String = "") : DetailRoute
    data class PlaylistRoute(val playlistId: String) : DetailRoute
    data class MixRoute(val mix: Mix) : DetailRoute
    data class WeeklyRoute(val weekly: WeeklyPlaylist) : DetailRoute
    data object LikedSongsRoute : DetailRoute
    data object DownloadedSongsRoute : DetailRoute
    data object SettingsRoute : DetailRoute
}

class MainActivity : ComponentActivity() {
    private var openNowPlayingTrigger by mutableStateOf(false)
    private var incomingDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        val container = (application as EviApplication).container
        setContent {
            val vm: EviViewModel = viewModel(factory = EviViewModel.Factory(container))
            EviRootApp(
                container = container,
                vm = vm,
                openNowPlayingTrigger = openNowPlayingTrigger,
                onConsumeNowPlayingTrigger = { openNowPlayingTrigger = false },
                incomingDeepLink = incomingDeepLink,
                onConsumeDeepLink = { incomingDeepLink = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EviPlaybackService.EXTRA_OPEN_NOW_PLAYING, false)) {
            openNowPlayingTrigger = true
        }
        intent.dataString?.let { incomingDeepLink = it }
    }
}

@Composable
private fun EviRootApp(
    container: AppContainer,
    vm: EviViewModel,
    openNowPlayingTrigger: Boolean,
    onConsumeNowPlayingTrigger: () -> Unit,
    incomingDeepLink: String?,
    onConsumeDeepLink: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playbackController = remember { PlaybackController(context.applicationContext, container) }
    DisposableEffect(Unit) {
        playbackController.connect()
        onDispose { playbackController.disconnect() }
    }

    // Request POST_NOTIFICATIONS permission on Android 13+ so media controls always show
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val settings by container.settingsStore.settings.collectAsState()
    val user by container.currentUser.collectAsState()
    val likes by container.likes.collectAsState()
    val likedIds by container.likedIds.collectAsState()
    val playlists by container.playlists.collectAsState()
    val follows by container.follows.collectAsState()
    val followedIds by container.followedIds.collectAsState()
    val history by container.history.collectAsState()
    val friends by container.friends.collectAsState()
    val sleepTimerRemainingMs by container.sleepTimerRemainingMs.collectAsState()
    val downloadedSongs by container.downloadStore.downloadedSongs.collectAsState()
    val downloadedSongIds by container.downloadStore.downloadedSongIds.collectAsState()
    val downloadProgress by container.downloadStore.downloadProgress.collectAsState()

    val homeState by vm.homeState.collectAsState()
    val searchState by vm.searchState.collectAsState()
    val playbackState by playbackController.state.collectAsState()
    val currentLyrics by vm.currentLyrics.collectAsState()
    val lyricsLoading by vm.lyricsLoading.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val backStack = remember { mutableStateListOf<DetailRoute>() }
    var showNowPlaying by remember { mutableStateOf(false) }
    var openWithLyrics by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var songForOptions by remember { mutableStateOf<Song?>(null) }
    var songForPlaylistPicker by remember { mutableStateOf<Song?>(null) }
    var showGlobalEqualizerSheet by remember { mutableStateOf(false) }
    var pendingPlayAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        container.toastEvents.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(playbackState.currentSong?.id) {
        vm.loadLyricsFor(playbackState.currentSong)
    }

    LaunchedEffect(openNowPlayingTrigger) {
        if (openNowPlayingTrigger && playbackState.currentSong != null) {
            showNowPlaying = true
            onConsumeNowPlayingTrigger()
        }
    }

    LaunchedEffect(incomingDeepLink) {
        val url = incomingDeepLink ?: return@LaunchedEffect
        val parts = url.removePrefix("https://evi.wtf/").split('/').filter { it.isNotBlank() }
        if (parts.size >= 2) {
            when (parts[0]) {
                "artist" -> backStack.add(DetailRoute.ArtistRoute(parts[1]))
                "album" -> backStack.add(DetailRoute.AlbumRoute(parts[1]))
                "song" -> {
                    selectedTab = 1
                    vm.onSearchQueryChange(parts[1])
                }
            }
        }
        onConsumeDeepLink()
    }

    BackHandler(enabled = backStack.isNotEmpty()) {
        backStack.removeAt(backStack.lastIndex)
    }

    fun playWithAuthCheck(songs: List<Song>, index: Int, shuffle: Boolean) {
        if (container.currentUser.value == null && !container.cookieJar.hasSession.value) {
            pendingPlayAction = { playbackController.playQueue(songs, index, shuffle) }
            showAuthSheet = true
            return
        }
        playbackController.playQueue(songs, index, shuffle)
    }

    val currentCover = playbackState.currentSong?.resolvedCoverUrl(256)

    EviTheme(settings = settings, currentCoverUrl = currentCover) {
        val colors = LocalEviColors.current
        val hasMiniPlayer = playbackState.currentSong != null
        val contentBottomPadding = if (hasMiniPlayer) 78.dp else 12.dp

        Scaffold(
            containerColor = colors.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface.copy(alpha = 0.96f))
                        .navigationBarsPadding()
                ) {
                    if (hasMiniPlayer) {
                        MiniPlayer(
                            playbackState = playbackState,
                            isLiked = likedIds.contains(playbackState.currentSong?.id),
                            onOpenNowPlaying = { showNowPlaying = true },
                            onTogglePlayPause = { playbackController.togglePlayPause() },
                            onSkipNext = { playbackController.skipNext() },
                            onSkipPrevious = { playbackController.skipPrevious() },
                            onToggleLike = { playbackState.currentSong?.let { container.toggleLike(it) } }
                        )
                    }

                    NavigationBar(
                        containerColor = Color.Transparent,
                        tonalElevation = 0.dp
                    ) {
                        val navItems = listOf(
                            Triple("Home", Icons.Rounded.Home, 0),
                            Triple("Search", Icons.Rounded.Search, 1),
                            Triple("Charts", Icons.Rounded.Explore, 2),
                            Triple("Library", Icons.Rounded.LibraryMusic, 3)
                        )
                        navItems.forEach { (label, icon, idx) ->
                            val isSelected = selectedTab == idx && backStack.isEmpty()
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    selectedTab = idx
                                    backStack.clear()
                                },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = colors.accent,
                                    selectedTextColor = colors.accent,
                                    unselectedIconColor = colors.textMuted,
                                    unselectedTextColor = colors.textMuted,
                                    indicatorColor = colors.accent.copy(alpha = 0.14f)
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .statusBarsPadding()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                val activeDetail = backStack.lastOrNull()
                if (activeDetail != null) {
                    when (activeDetail) {
                        is DetailRoute.AlbumRoute -> {
                            AlbumDetailScreen(
                                albumId = activeDetail.albumId,
                                albumHint = activeDetail.hint,
                                loadAlbum = vm::loadAlbumDetail,
                                likedIds = likedIds,
                                playbackState = playbackState,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onPlaySongs = ::playWithAuthCheck,
                                onToggleLike = { container.toggleLike(it) },
                                onMoreSong = { songForOptions = it },
                                onOpenArtist = { id, name -> backStack.add(DetailRoute.ArtistRoute(id, name)) },
                                downloadedIds = downloadedSongIds
                            )
                        }
                        is DetailRoute.ArtistRoute -> {
                            ArtistDetailScreen(
                                artistId = activeDetail.artistId,
                                artistNameHint = activeDetail.nameHint,
                                loadArtist = vm::loadArtistDetail,
                                isFollowed = followedIds.contains(activeDetail.artistId),
                                likedIds = likedIds,
                                playbackState = playbackState,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onToggleFollow = { container.toggleFollow(it) },
                                onPlaySongs = ::playWithAuthCheck,
                                onToggleLike = { container.toggleLike(it) },
                                onMoreSong = { songForOptions = it },
                                onOpenAlbum = { alb -> backStack.add(DetailRoute.AlbumRoute(alb.id, alb)) },
                                downloadedIds = downloadedSongIds
                            )
                        }
                        is DetailRoute.PlaylistRoute -> {
                            val playlist = playlists.find { it.id == activeDetail.playlistId }
                            SongCollectionDetailScreen(
                                title = playlist?.name ?: "Playlist",
                                subtitle = "${playlist?.songs?.size ?: 0} songs",
                                coverUrl = playlist?.resolvedCoverUrl()?.takeIf { it.isNotBlank() },
                                songs = playlist?.songs.orEmpty(),
                                isEditablePlaylist = playlist != null && !playlist.smart && !playlist.isShared,
                                likedIds = likedIds,
                                playbackState = playbackState,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onPlaySongs = ::playWithAuthCheck,
                                onToggleLike = { container.toggleLike(it) },
                                onMoreSong = { songForOptions = it },
                                onRenamePlaylist = { newName ->
                                    playlist?.let { pl -> scope.launch { container.renamePlaylist(pl, newName) } }
                                },
                                onDeletePlaylist = {
                                    playlist?.let { pl -> scope.launch { container.deletePlaylist(pl.id) } }
                                },
                                downloadedIds = downloadedSongIds,
                                downloadProgress = downloadProgress,
                                onDownloadAll = {
                                    playlist?.songs?.let { sList ->
                                        container.downloadManager.downloadSongs(sList, settings.audioQuality)
                                    }
                                },
                                onRemoveDownloads = {
                                    playlist?.songs?.let { sList ->
                                        container.downloadManager.deleteDownloads(sList)
                                    }
                                }
                            )
                        }
                        is DetailRoute.LikedSongsRoute -> {
                            SongCollectionDetailScreen(
                                title = "Liked Songs",
                                subtitle = "${likes.size} songs",
                                coverUrl = null,
                                songs = likes,
                                likedIds = likedIds,
                                playbackState = playbackState,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onPlaySongs = ::playWithAuthCheck,
                                onToggleLike = { container.toggleLike(it) },
                                onMoreSong = { songForOptions = it },
                                downloadedIds = downloadedSongIds,
                                downloadProgress = downloadProgress,
                                onDownloadAll = {
                                    container.downloadManager.downloadSongs(likes, settings.audioQuality)
                                },
                                onRemoveDownloads = {
                                    container.downloadManager.deleteDownloads(likes)
                                }
                            )
                        }
                        is DetailRoute.DownloadedSongsRoute -> {
                            SongCollectionDetailScreen(
                                title = "Downloaded Songs",
                                subtitle = "${downloadedSongs.size} songs • Offline playback",
                                coverUrl = null,
                                songs = downloadedSongs,
                                likedIds = likedIds,
                                playbackState = playbackState,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onPlaySongs = ::playWithAuthCheck,
                                onToggleLike = { container.toggleLike(it) },
                                onMoreSong = { songForOptions = it },
                                downloadedIds = downloadedSongIds,
                                downloadProgress = downloadProgress,
                                onDownloadAll = null,
                                onRemoveDownloads = {
                                    container.downloadManager.deleteAllDownloads()
                                }
                            )
                        }
                        is DetailRoute.MixRoute -> {
                            var mixSongs by remember(activeDetail.mix.index) { mutableStateOf(activeDetail.mix.songs) }
                            var loadingMix by remember(activeDetail.mix.index) { mutableStateOf(activeDetail.mix.songs.isEmpty()) }
                            LaunchedEffect(activeDetail.mix.index) {
                                if (mixSongs.isEmpty()) {
                                    mixSongs = vm.loadMixSongs(activeDetail.mix.index)
                                    loadingMix = false
                                }
                            }
                            SongCollectionDetailScreen(
                                title = activeDetail.mix.title.ifBlank { "Daily Mix ${activeDetail.mix.index + 1}" },
                                subtitle = activeDetail.mix.subtitle,
                                coverUrl = activeDetail.mix.resolvedCoverUrl().takeIf { it.isNotBlank() },
                                songs = mixSongs,
                                loading = loadingMix,
                                likedIds = likedIds,
                                playbackState = playbackState,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onPlaySongs = ::playWithAuthCheck,
                                onToggleLike = { container.toggleLike(it) },
                                onMoreSong = { songForOptions = it },
                                downloadedIds = downloadedSongIds,
                                downloadProgress = downloadProgress,
                                onDownloadAll = {
                                    container.downloadManager.downloadSongs(mixSongs, settings.audioQuality)
                                },
                                onRemoveDownloads = {
                                    container.downloadManager.deleteDownloads(mixSongs)
                                }
                            )
                        }
                        is DetailRoute.WeeklyRoute -> {
                            SongCollectionDetailScreen(
                                title = activeDetail.weekly.title.ifBlank { "Weekly Mix" },
                                subtitle = activeDetail.weekly.subtitle,
                                coverUrl = activeDetail.weekly.songs.firstOrNull()?.resolvedCoverUrl(512),
                                songs = activeDetail.weekly.songs,
                                likedIds = likedIds,
                                playbackState = playbackState,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onPlaySongs = ::playWithAuthCheck,
                                onToggleLike = { container.toggleLike(it) },
                                onMoreSong = { songForOptions = it },
                                downloadedIds = downloadedSongIds,
                                downloadProgress = downloadProgress,
                                onDownloadAll = {
                                    container.downloadManager.downloadSongs(activeDetail.weekly.songs, settings.audioQuality)
                                },
                                onRemoveDownloads = {
                                    container.downloadManager.deleteDownloads(activeDetail.weekly.songs)
                                }
                            )
                        }
                        is DetailRoute.SettingsRoute -> {
                            SettingsScreen(
                                user = user,
                                settings = settings,
                                sleepTimerRemainingMs = sleepTimerRemainingMs,
                                bottomPadding = contentBottomPadding,
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onOpenSignIn = { showAuthSheet = true },
                                onSignOut = { scope.launch { container.signOut() } },
                                onSelectTheme = container.settingsStore::setTheme,
                                onSelectAccent = container.settingsStore::setAccent,
                                onSelectQuality = { q ->
                                    container.settingsStore.setAudioQuality(q)
                                    playbackController.refreshStreamQuality()
                                },
                                onSelectEqPreset = container.settingsStore::setEqPreset,
                                onSetSleepTimer = playbackController::setSleepTimer,
                                onToggleAutoplay = container.settingsStore::setAutoplayRadio,
                                onToggleEq = container.settingsStore::setEqEnabled,
                                onBandChange = container.settingsStore::setCustomEqBand,
                                onBassBoostChange = container.settingsStore::setBassBoostStrength,
                                onVirtualizerChange = container.settingsStore::setVirtualizerStrength,
                                onResetEq = container.settingsStore::resetEqualizer
                            )
                        }
                    }
                } else {
                    when (selectedTab) {
                        0 -> HomeScreen(
                            homeState = homeState,
                            user = user,
                            history = history,
                            likedIds = likedIds,
                            playbackState = playbackState,
                            bottomPadding = contentBottomPadding,
                            onRefresh = vm::refreshAll,
                            onOpenSignIn = { showAuthSheet = true },
                            onOpenSettings = { backStack.add(DetailRoute.SettingsRoute) },
                            onPlaySongs = ::playWithAuthCheck,
                            onToggleLike = { container.toggleLike(it) },
                            onMoreSong = { songForOptions = it },
                            onOpenAlbum = { alb -> backStack.add(DetailRoute.AlbumRoute(alb.id, alb)) },
                            onOpenMix = { mix -> backStack.add(DetailRoute.MixRoute(mix)) },
                            onOpenWeekly = { wk -> backStack.add(DetailRoute.WeeklyRoute(wk)) },
                            downloadedIds = downloadedSongIds
                        )
                        1 -> SearchScreen(
                            searchState = searchState,
                            recentSearches = settings.recentSearches,
                            trendingSongs = homeState.trendingSongs,
                            likedIds = likedIds,
                            playbackState = playbackState,
                            bottomPadding = contentBottomPadding,
                            onQueryChange = vm::onSearchQueryChange,
                            onSubmitSearch = vm::submitSearch,
                            onClearRecents = container.settingsStore::clearRecentSearches,
                            onPlaySongs = ::playWithAuthCheck,
                            onToggleLike = { container.toggleLike(it) },
                            onMoreSong = { songForOptions = it },
                            onOpenArtist = { art -> backStack.add(DetailRoute.ArtistRoute(art.id, art.name)) },
                            downloadedIds = downloadedSongIds
                        )
                        2 -> ExploreScreen(
                            trendingSongs = homeState.trendingSongs,
                            newReleases = homeState.newReleases,
                            likedIds = likedIds,
                            playbackState = playbackState,
                            bottomPadding = contentBottomPadding,
                            onPlaySongs = ::playWithAuthCheck,
                            onToggleLike = { container.toggleLike(it) },
                            onMoreSong = { songForOptions = it },
                            onOpenAlbum = { alb -> backStack.add(DetailRoute.AlbumRoute(alb.id, alb)) },
                            downloadedIds = downloadedSongIds
                        )
                        3 -> LibraryScreen(
                            user = user,
                            likes = likes,
                            playlists = playlists,
                            follows = follows,
                            history = history,
                            friends = friends,
                            likedIds = likedIds,
                            playbackState = playbackState,
                            bottomPadding = contentBottomPadding,
                            onOpenSignIn = { showAuthSheet = true },
                            onOpenSettings = { backStack.add(DetailRoute.SettingsRoute) },
                            onOpenLikedSongs = { backStack.add(DetailRoute.LikedSongsRoute) },
                            onOpenPlaylist = { pl -> backStack.add(DetailRoute.PlaylistRoute(pl.id)) },
                            onOpenArtist = { art -> backStack.add(DetailRoute.ArtistRoute(art.id, art.name)) },
                            onCreatePlaylist = { name -> scope.launch { container.createPlaylist(name) } },
                            onImportPlaylistUrl = vm::importPlaylistUrl,
                            onPlaySongs = ::playWithAuthCheck,
                            onToggleLike = { container.toggleLike(it) },
                            onMoreSong = { songForOptions = it },
                            downloadedSongs = downloadedSongs,
                            downloadedIds = downloadedSongIds,
                            onOpenDownloads = { backStack.add(DetailRoute.DownloadedSongsRoute) }
                        )
                    }
                }
            }
        }

        if (showNowPlaying && playbackState.currentSong != null) {
            NowPlayingDialog(
                playbackState = playbackState,
                settings = settings,
                isLiked = likedIds.contains(playbackState.currentSong?.id),
                sleepTimerRemainingMs = sleepTimerRemainingMs,
                lyrics = currentLyrics,
                lyricsLoading = lyricsLoading,
                startWithLyrics = openWithLyrics,
                onDismiss = {
                    showNowPlaying = false
                    openWithLyrics = false
                },
                onTogglePlayPause = playbackController::togglePlayPause,
                onSeekTo = playbackController::seekTo,
                onSkipNext = playbackController::skipNext,
                onSkipPrevious = playbackController::skipPrevious,
                onToggleShuffle = playbackController::toggleShuffle,
                onCycleRepeat = playbackController::cycleRepeat,
                onToggleLike = { playbackState.currentSong?.let { container.toggleLike(it) } },
                onOpenArtist = { id, name -> backStack.add(DetailRoute.ArtistRoute(id, name)) },
                onOpenMoreOptions = { songForOptions = it },
                onPlayQueueIndex = playbackController::playAtQueueIndex,
                onRemoveFromQueue = playbackController::removeFromQueue,
                onClearUpcomingQueue = playbackController::clearUpcomingQueue,
                onSelectQuality = { q ->
                    container.settingsStore.setAudioQuality(q)
                    playbackController.refreshStreamQuality()
                },
                onSelectEqPreset = container.settingsStore::setEqPreset,
                onSetSleepTimer = playbackController::setSleepTimer,
                onToggleAutoplayRadio = container.settingsStore::setAutoplayRadio,
                onToggleEq = container.settingsStore::setEqEnabled,
                onBandChange = container.settingsStore::setCustomEqBand,
                onBassBoostChange = container.settingsStore::setBassBoostStrength,
                onVirtualizerChange = container.settingsStore::setVirtualizerStrength,
                onResetEq = container.settingsStore::resetEqualizer
            )
        }

        songForOptions?.let { song ->
            SongOptionsSheet(
                song = song,
                isLiked = likedIds.contains(song.id),
                isDownloaded = downloadedSongIds.contains(song.id),
                isDownloading = downloadProgress.containsKey(song.id),
                onDismiss = { songForOptions = null },
                onPlayNext = { playbackController.playNext(song) },
                onAddToQueue = { playbackController.addToQueue(listOf(song)) },
                onToggleLike = { container.toggleLike(song) },
                onAddToPlaylist = { songForPlaylistPicker = song },
                onDownload = {
                    container.downloadManager.downloadSong(song, settings.audioQuality)
                },
                onRemoveDownload = {
                    container.downloadManager.deleteDownload(song.id)
                },
                onOpenLyrics = {
                    if (playbackState.currentSong?.id != song.id) {
                        playWithAuthCheck(listOf(song), 0, false)
                    }
                    openWithLyrics = true
                    showNowPlaying = true
                },
                onOpenArtist = {
                    if (song.artistId.isNotBlank()) {
                        showNowPlaying = false
                        backStack.add(DetailRoute.ArtistRoute(song.artistId, song.artist))
                    }
                },
                onOpenAlbum = {
                    if (song.albumId.isNotBlank()) {
                        showNowPlaying = false
                        backStack.add(DetailRoute.AlbumRoute(song.albumId))
                    }
                },
                onOpenEqualizer = { showGlobalEqualizerSheet = true }
            )
        }

        if (showGlobalEqualizerSheet) {
            EqualizerBottomSheet(
                settings = settings,
                onDismiss = { showGlobalEqualizerSheet = false },
                onToggleEq = container.settingsStore::setEqEnabled,
                onSelectPreset = container.settingsStore::setEqPreset,
                onBandChange = container.settingsStore::setCustomEqBand,
                onBassBoostChange = container.settingsStore::setBassBoostStrength,
                onVirtualizerChange = container.settingsStore::setVirtualizerStrength,
                onResetToFlat = container.settingsStore::resetEqualizer
            )
        }

        songForPlaylistPicker?.let { song ->
            AddToPlaylistSheet(
                song = song,
                playlists = playlists,
                onDismiss = { songForPlaylistPicker = null },
                onSelectPlaylist = { pl -> scope.launch { container.addSongToPlaylist(pl, song) } },
                onCreatePlaylist = { name -> scope.launch { container.createPlaylist(name, listOf(song)) } }
            )
        }

        if (showAuthSheet) {
            AuthSheet(
                container = container,
                onDismiss = { showAuthSheet = false },
                onSignedIn = {
                    vm.refreshAll()
                    pendingPlayAction?.invoke()
                    pendingPlayAction = null
                }
            )
        }
    }
}
