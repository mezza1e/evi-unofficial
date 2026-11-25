package wtf.evi.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import wtf.evi.mobile.data.model.AccentColor
import wtf.evi.mobile.data.model.Album
import wtf.evi.mobile.data.model.AlbumDetailResponse
import wtf.evi.mobile.data.model.AppSettings
import wtf.evi.mobile.data.model.AppTheme
import wtf.evi.mobile.data.model.Artist
import wtf.evi.mobile.data.model.ArtistArtResponse
import wtf.evi.mobile.data.model.ArtistDetailResponse
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.EqPreset
import wtf.evi.mobile.data.model.Playlist
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.model.User
import wtf.evi.mobile.player.PlaybackUiState
import wtf.evi.mobile.ui.components.AlbumCard
import wtf.evi.mobile.ui.components.ArtworkImage
import wtf.evi.mobile.ui.components.EqualizerBottomSheet
import wtf.evi.mobile.ui.components.SongRow
import wtf.evi.mobile.ui.theme.LocalEviColors

@Composable
fun AlbumDetailScreen(
    albumId: String,
    albumHint: Album?,
    loadAlbum: suspend (String) -> AlbumDetailResponse?,
    likedIds: Set<String>,
    playbackState: PlaybackUiState,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int, Boolean) -> Unit,
    onToggleLike: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    onOpenArtist: (String, String) -> Unit,
    downloadedIds: Set<String> = emptySet()
) {
    val colors = LocalEviColors.current
    var detail by remember(albumId) { mutableStateOf<AlbumDetailResponse?>(null) }
    var loading by remember(albumId) { mutableStateOf(true) }

    LaunchedEffect(albumId) {
        loading = true
        detail = loadAlbum(albumId)
        loading = false
    }

    val album = detail?.album?.takeIf { it.title.isNotBlank() } ?: albumHint ?: Album(id = albumId)
    val songs = detail?.songs.orEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(bottom = bottomPadding + 20.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ArtworkImage(
                    url = album.resolvedCoverUrl(512),
                    contentDescription = album.title,
                    modifier = Modifier.size(220.dp),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = album.title.ifBlank { "Album" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = listOf(album.artist, album.year).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.accent,
                    modifier = Modifier.clickable(enabled = album.artistId.isNotBlank()) {
                        onOpenArtist(album.artistId, album.artist)
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (songs.isNotEmpty()) onPlaySongs(songs, 0, false) },
                        enabled = songs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { if (songs.isNotEmpty()) onPlaySongs(songs, 0, true) },
                        enabled = songs.isNotEmpty(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = colors.textPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Shuffle", color = colors.textPrimary)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        } else {
            itemsIndexed(songs, key = { idx, s -> "alb_${idx}_${s.id}" }) { idx, song ->
                SongRow(
                    song = song,
                    trackNumber = idx + 1,
                    showArtwork = false,
                    isCurrent = playbackState.currentSong?.id == song.id,
                    isPlaying = playbackState.isPlaying,
                    isLiked = likedIds.contains(song.id),
                    isDownloaded = downloadedIds.contains(song.id),
                    onClick = { onPlaySongs(songs, idx, false) },
                    onToggleLike = { onToggleLike(song) },
                    onMoreClick = { onMoreSong(song) }
                )
            }
        }
    }
}

@Composable
fun ArtistDetailScreen(
    artistId: String,
    artistNameHint: String,
    loadArtist: suspend (String, String) -> Pair<ArtistDetailResponse?, ArtistArtResponse?>,
    isFollowed: Boolean,
    likedIds: Set<String>,
    playbackState: PlaybackUiState,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onToggleFollow: (Artist) -> Unit,
    onPlaySongs: (List<Song>, Int, Boolean) -> Unit,
    onToggleLike: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    downloadedIds: Set<String> = emptySet()
) {
    val colors = LocalEviColors.current
    var detail by remember(artistId) { mutableStateOf<ArtistDetailResponse?>(null) }
    var art by remember(artistId) { mutableStateOf<ArtistArtResponse?>(null) }
    var loading by remember(artistId) { mutableStateOf(true) }

    LaunchedEffect(artistId) {
        loading = true
        val (d, a) = loadArtist(artistId, artistNameHint)
        detail = d
        art = a
        loading = false
    }

    val artist = detail?.artist?.takeIf { it.name.isNotBlank() }
        ?: Artist(id = artistId, name = artistNameHint.ifBlank { "Artist" })
    val heroUrl = art?.hero ?: art?.banner ?: art?.portrait ?: artist.resolvedImageUrl()
    val topSongs = detail?.topSongs.orEmpty()
    val albums = detail?.albums.orEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(bottom = bottomPadding + 20.dp)
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                ArtworkImage(
                    url = heroUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(0.dp),
                    fallbackIcon = Icons.Rounded.Person
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.25f), colors.background)
                            )
                        )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White
                        )
                        if (artist.verified) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.Rounded.Verified, contentDescription = "Verified", tint = colors.accent, modifier = Modifier.size(22.dp))
                        }
                    }
                    if (artist.followers > 0) {
                        Text("${artist.followers} followers", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { if (topSongs.isNotEmpty()) onPlaySongs(topSongs, 0, false) },
                    enabled = topSongs.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { if (topSongs.isNotEmpty()) onPlaySongs(topSongs, 0, true) },
                    enabled = topSongs.isNotEmpty(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = colors.textPrimary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Shuffle", color = colors.textPrimary)
                }
                OutlinedButton(
                    onClick = { onToggleFollow(artist) },
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(if (isFollowed) "Following" else "Follow", color = if (isFollowed) colors.accent else colors.textPrimary)
                }
            }
        }

        if (loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        }

        if (topSongs.isNotEmpty()) {
            item {
                Text(
                    "Popular",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            itemsIndexed(topSongs.take(10), key = { idx, s -> "art_${idx}_${s.id}" }) { idx, song ->
                SongRow(
                    song = song,
                    trackNumber = idx + 1,
                    showArtwork = true,
                    isCurrent = playbackState.currentSong?.id == song.id,
                    isPlaying = playbackState.isPlaying,
                    isLiked = likedIds.contains(song.id),
                    isDownloaded = downloadedIds.contains(song.id),
                    onClick = { onPlaySongs(topSongs, idx, false) },
                    onToggleLike = { onToggleLike(song) },
                    onMoreClick = { onMoreSong(song) }
                )
            }
        }

        if (albums.isNotEmpty()) {
            item {
                Text(
                    "Discography",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(albums, key = { "aa_${it.id}" }) { alb ->
                        AlbumCard(album = alb, onClick = { onOpenAlbum(alb) })
                    }
                }
            }
        }

        if (!art?.bio.isNullOrBlank()) {
            item {
                Text(
                    "About",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)
                )
                Text(
                    text = art?.bio.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
fun SongCollectionDetailScreen(
    title: String,
    subtitle: String,
    coverUrl: String?,
    songs: List<Song>,
    loading: Boolean = false,
    isEditablePlaylist: Boolean = false,
    likedIds: Set<String>,
    playbackState: PlaybackUiState,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onPlaySongs: (List<Song>, Int, Boolean) -> Unit,
    onToggleLike: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    onRenamePlaylist: ((String) -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null,
    downloadedIds: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadAll: (() -> Unit)? = null,
    onRemoveDownloads: (() -> Unit)? = null
) {
    val colors = LocalEviColors.current
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(bottom = bottomPadding + 20.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
                if (isEditablePlaylist) {
                    Row {
                        if (onRenamePlaylist != null) {
                            IconButton(onClick = { showRename = true }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Rename", tint = colors.textPrimary)
                            }
                        }
                        if (onDeletePlaylist != null) {
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = colors.textSecondary)
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (coverUrl != null) {
                    ArtworkImage(
                        url = coverUrl,
                        contentDescription = title,
                        modifier = Modifier.size(190.dp),
                        shape = RoundedCornerShape(14.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(colors.accent, colors.surfaceElevated))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(64.dp))
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { if (songs.isNotEmpty()) onPlaySongs(songs, 0, false) },
                        enabled = songs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = { if (songs.isNotEmpty()) onPlaySongs(songs, 0, true) },
                        enabled = songs.isNotEmpty(),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = colors.textPrimary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Shuffle", color = colors.textPrimary)
                    }
                    if (onDownloadAll != null && songs.isNotEmpty()) {
                        val allDownloaded = songs.isNotEmpty() && songs.all { downloadedIds.contains(it.id) }
                        val isDownloading = songs.any { downloadProgress.containsKey(it.id) }
                        OutlinedButton(
                            onClick = {
                                if (allDownloaded) {
                                    onRemoveDownloads?.invoke()
                                } else {
                                    onDownloadAll()
                                }
                            },
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Downloading...", color = colors.accent)
                            } else if (allDownloaded) {
                                Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Downloaded", color = colors.accent)
                            } else {
                                Icon(Icons.Rounded.Download, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                val count = songs.count { downloadedIds.contains(it.id) }
                                Text(if (count > 0) "Download ($count/${songs.size})" else "Download", color = colors.textPrimary)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        } else {
            itemsIndexed(songs, key = { idx, s -> "col_${idx}_${s.id}" }) { idx, song ->
                SongRow(
                    song = song,
                    isCurrent = playbackState.currentSong?.id == song.id,
                    isPlaying = playbackState.isPlaying,
                    isLiked = likedIds.contains(song.id),
                    isDownloaded = downloadedIds.contains(song.id),
                    onClick = { onPlaySongs(songs, idx, false) },
                    onToggleLike = { onToggleLike(song) },
                    onMoreClick = { onMoreSong(song) }
                )
            }
        }
    }

    if (showRename && onRenamePlaylist != null) {
        var newName by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = colors.surfaceElevated,
            title = { Text("Rename playlist", color = colors.textPrimary) },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        onRenamePlaylist(newName.trim())
                        showRename = false
                    }
                }) { Text("Save", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteConfirm && onDeletePlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = colors.surfaceElevated,
            title = { Text("Delete this playlist?", color = colors.textPrimary) },
            text = { Text("This will remove \"$title\" from your Evi library.", color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeletePlaylist()
                    onBack()
                }) { Text("Delete", color = Color(0xFFFF5A5A)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    user: User?,
    settings: AppSettings,
    sleepTimerRemainingMs: Long?,
    bottomPadding: Dp,
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    onSelectAccent: (AccentColor) -> Unit,
    onSelectQuality: (AudioQuality) -> Unit,
    onSelectEqPreset: (EqPreset) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onToggleAutoplay: (Boolean) -> Unit,
    onToggleEq: (Boolean) -> Unit = {},
    onBandChange: (Int, Float) -> Unit = { _, _ -> },
    onBassBoostChange: (Float) -> Unit = {},
    onVirtualizerChange: (Float) -> Unit = {},
    onResetEq: () -> Unit = {}
) {
    val colors = LocalEviColors.current
    var showEqualizerSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomPadding + 24.dp)
        ) {
            // Account Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceElevated)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(
                    url = user?.resolvedAvatarUrl(),
                    contentDescription = user?.label ?: "Account",
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    fallbackIcon = Icons.Rounded.Person
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user?.label ?: "Not signed in",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (user != null) "@${user.username}" else "Sign in to stream and sync",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
                if (user == null) {
                    Button(
                        onClick = onOpenSignIn,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)
                    ) { Text("Sign in") }
                } else {
                    OutlinedButton(onClick = onSignOut) {
                        Text("Sign out", color = colors.textPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text("Audio Quality", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioQuality.entries.forEach { q ->
                    FilterChip(
                        selected = settings.audioQuality == q,
                        onClick = { onSelectQuality(q) },
                        label = { Text(q.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Equalizer", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(
                        text = if (settings.eqEnabled) "${settings.eqPreset.label} mode" else "Equalizer disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                TextButton(onClick = { showEqualizerSheet = true }) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Customize", color = colors.accent, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.eqPreset == preset && settings.eqEnabled,
                        onClick = {
                            if (!settings.eqEnabled) onToggleEq(true)
                            onSelectEqPreset(preset)
                        },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sleep Timer", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                if (sleepTimerRemainingMs != null) {
                    val m = sleepTimerRemainingMs / 60000
                    val s = (sleepTimerRemainingMs % 60000) / 1000
                    Text("%d:%02d left".format(m, s), color = colors.accent, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour").forEach { (mins, label) ->
                    FilterChip(
                        selected = mins == 0 && sleepTimerRemainingMs == null,
                        onClick = { onSetSleepTimer(mins) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Autoplay Radio", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                    Text("Keep streaming similar tracks when your queue ends", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Switch(checked = settings.autoplayRadio, onCheckedChange = onToggleAutoplay)
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 18.dp))

            Text("Theme", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTheme.entries.forEach { t ->
                    FilterChip(
                        selected = settings.theme == t,
                        onClick = { onSelectTheme(t) },
                        label = { Text(t.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Accent Color", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentColor.entries.forEach { a ->
                    FilterChip(
                        selected = settings.accent == a,
                        onClick = { onSelectAccent(a) },
                        label = { Text(a.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Evi Unofficial v0.1.29",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted
            )
        }
    }

    if (showEqualizerSheet) {
        EqualizerBottomSheet(
            settings = settings,
            onDismiss = { showEqualizerSheet = false },
            onToggleEq = onToggleEq,
            onSelectPreset = onSelectEqPreset,
            onBandChange = onBandChange,
            onBassBoostChange = onBassBoostChange,
            onVirtualizerChange = onVirtualizerChange,
            onResetToFlat = onResetEq
        )
    }
}
