package wtf.evi.mobile.ui.components

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import wtf.evi.mobile.R
import wtf.evi.mobile.data.model.Album
import wtf.evi.mobile.data.model.Artist
import wtf.evi.mobile.data.model.EVI_BASE_URL
import wtf.evi.mobile.data.model.Playlist
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.ui.theme.LocalEviColors

@Composable
fun EviSwirlLogo(
    modifier: Modifier = Modifier,
    tint: Color = Color.White
) {
    Image(
        painter = painterResource(id = R.drawable.ic_notification),
        contentDescription = "Evi",
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier
    )
}

@Composable
fun ArtworkImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    fallbackIcon: ImageVector = Icons.Rounded.MusicNote
) {
    val colors = LocalEviColors.current
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceElevated),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(24.dp)
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = remember(url) {
                    ImageRequest.Builder(context)
                        .data(url)
                        .crossfade(true)
                        .allowHardware(true)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build()
                },
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    startAnimationIfAnimatable(state.result.drawable)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun startAnimationIfAnimatable(drawable: android.graphics.drawable.Drawable?) {
    if (drawable == null) return
    if (drawable is android.graphics.drawable.Animatable) {
        if (Build.VERSION.SDK_INT >= 28 && drawable is android.graphics.drawable.AnimatedImageDrawable) {
            drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
        }
        drawable.start()
        return
    }
    if (Build.VERSION.SDK_INT >= 23 && drawable is android.graphics.drawable.DrawableWrapper) {
        startAnimationIfAnimatable(drawable.drawable)
        return
    }
    if (drawable is android.graphics.drawable.LayerDrawable) {
        for (i in 0 until drawable.numberOfLayers) {
            startAnimationIfAnimatable(drawable.getDrawable(i))
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    trackNumber: Int? = null,
    showArtwork: Boolean = true,
    isDownloaded: Boolean = false,
    onRemoveClick: (() -> Unit)? = null
) {
    val colors = LocalEviColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (trackNumber != null) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (isCurrent && !showArtwork) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Playing",
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(
                        text = trackNumber.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isCurrent) colors.accent else colors.textMuted
                    )
                }
            }
            if (showArtwork) {
                Spacer(modifier = Modifier.width(4.dp))
            }
        }

        if (showArtwork) {
            Box(modifier = Modifier.size(50.dp)) {
                ArtworkImage(
                    url = song.resolvedCoverUrl(128),
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(6.dp)
                )
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = if (isPlaying) "Playing" else "Paused",
                            tint = colors.accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (isCurrent) colors.accent else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDownloaded) {
                    Icon(
                        imageVector = Icons.Rounded.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = colors.accent,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(13.dp)
                    )
                }
                if (song.explicit) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.textSecondary.copy(alpha = 0.2f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "E",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textSecondary
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(song.artist)
                        if (song.duration > 0) {
                            append(" • ")
                            append(song.formattedDuration)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onToggleLike, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isLiked) "Unlike" else "Like",
                tint = if (isLiked) colors.accent else colors.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        if (onRemoveClick != null) {
            IconButton(onClick = onRemoveClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Remove from playlist",
                    tint = Color(0xFFFF5A5A).copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onMoreClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "More options",
                tint = colors.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 148.dp
) {
    val colors = LocalEviColors.current
    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        ArtworkImage(
            url = album.resolvedCoverUrl(256),
            contentDescription = album.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(10.dp),
            fallbackIcon = Icons.Rounded.Album
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                append(album.artist)
                if (album.year.isNotBlank()) {
                    append(" • ")
                    append(album.year)
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ArtistCircleCard(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 112.dp
) {
    val colors = LocalEviColors.current
    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtworkImage(
            url = artist.resolvedImageUrl(),
            contentDescription = artist.name,
            modifier = Modifier
                .size(width - 12.dp)
                .aspectRatio(1f),
            shape = CircleShape,
            fallbackIcon = Icons.Rounded.Person
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    song: Song,
    isLiked: Boolean,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    currentPlaylist: Playlist? = null,
    playlistsContainingSong: List<Playlist> = emptyList(),
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromCurrentPlaylist: (() -> Unit)? = null,
    onOpenRemoveFromPlaylistPicker: (() -> Unit)? = null,
    onOpenArtist: () -> Unit,
    onOpenAlbum: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onRemoveDownload: (() -> Unit)? = null,
    onOpenEqualizer: (() -> Unit)? = null,
    onOpenLyrics: (() -> Unit)? = null
) {
    val colors = LocalEviColors.current
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(
                    url = song.resolvedCoverUrl(128),
                    contentDescription = song.title,
                    modifier = Modifier.size(54.dp),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 6.dp))

            SheetActionItem(
                icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                iconTint = if (isLiked) colors.accent else colors.textPrimary,
                label = if (isLiked) "Remove from Liked songs" else "Add to Liked songs",
                onClick = { onToggleLike(); onDismiss() }
            )
            SheetActionItem(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                label = "Add to playlist",
                onClick = { onDismiss(); onAddToPlaylist() }
            )
            if (currentPlaylist != null && onRemoveFromCurrentPlaylist != null) {
                SheetActionItem(
                    icon = Icons.Rounded.DeleteOutline,
                    label = "Remove from \"${currentPlaylist.name}\"",
                    iconTint = colors.accent,
                    onClick = { onDismiss(); onRemoveFromCurrentPlaylist() }
                )
            }
            if (playlistsContainingSong.isNotEmpty() && onOpenRemoveFromPlaylistPicker != null) {
                SheetActionItem(
                    icon = Icons.Rounded.DeleteOutline,
                    label = "Remove from a playlist...",
                    onClick = { onDismiss(); onOpenRemoveFromPlaylistPicker() }
                )
            }
            SheetActionItem(
                icon = Icons.Rounded.QueuePlayNext,
                label = "Play next",
                onClick = { onPlayNext(); onDismiss() }
            )
            SheetActionItem(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Add to queue",
                onClick = { onAddToQueue(); onDismiss() }
            )
            if (isDownloading) {
                SheetActionItem(
                    icon = Icons.Rounded.Downloading,
                    label = "Downloading for offline listening...",
                    iconTint = colors.accent,
                    onClick = onDismiss
                )
            } else if (isDownloaded) {
                SheetActionItem(
                    icon = Icons.Rounded.DeleteOutline,
                    label = "Remove offline download",
                    iconTint = colors.textSecondary,
                    onClick = {
                        onDismiss()
                        onRemoveDownload?.invoke()
                    }
                )
            } else if (onDownload != null) {
                SheetActionItem(
                    icon = Icons.Rounded.Download,
                    label = "Download for offline listening",
                    onClick = {
                        onDismiss()
                        onDownload()
                    }
                )
            }
            if (onOpenLyrics != null) {
                SheetActionItem(
                    icon = Icons.Rounded.Lyrics,
                    label = "View lyrics",
                    onClick = { onDismiss(); onOpenLyrics() }
                )
            }
            if (onOpenEqualizer != null) {
                SheetActionItem(
                    icon = Icons.Rounded.GraphicEq,
                    label = "Audio Equalizer & Effects",
                    onClick = { onDismiss(); onOpenEqualizer() }
                )
            }
            if (song.artistId.isNotBlank()) {
                SheetActionItem(
                    icon = Icons.Rounded.Person,
                    label = "Go to artist",
                    onClick = { onDismiss(); onOpenArtist() }
                )
            }
            if (song.albumId.isNotBlank()) {
                SheetActionItem(
                    icon = Icons.Rounded.Album,
                    label = "Go to album",
                    onClick = { onDismiss(); onOpenAlbum() }
                )
            }
            SheetActionItem(
                icon = Icons.Rounded.Share,
                label = "Share song link",
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "$EVI_BASE_URL/song/${song.id}")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share ${song.title}"))
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun SheetActionItem(
    icon: ImageVector,
    label: String,
    iconTint: Color = LocalEviColors.current.textPrimary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = LocalEviColors.current.textPrimary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    song: Song,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit
) {
    val colors = LocalEviColors.current
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Add to playlist",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                TextButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New playlist")
                }
            }

            if (playlists.isEmpty()) {
                Text(
                    text = "No playlists yet. Create one to add \"${song.title}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                    items(playlists.filter { !it.smart }, key = { it.id }) { pl ->
                        val isAlreadyInPlaylist = pl.songs.any { it.id == song.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPlaylist(pl)
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(
                                url = pl.resolvedCoverUrl(),
                                contentDescription = pl.name,
                                modifier = Modifier.size(46.dp),
                                shape = RoundedCornerShape(6.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pl.name, color = colors.textPrimary, fontWeight = FontWeight.Medium)
                                Text("${pl.songs.size} songs", color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (isAlreadyInPlaylist) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Added",
                                        tint = colors.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Added",
                                        color = colors.accent,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = colors.surfaceElevated,
            title = { Text("New playlist", color = colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        onCreatePlaylist(name.trim())
                        showCreateDialog = false
                        onDismiss()
                    }
                }) { Text("Create & Add", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoveFromPlaylistSheet(
    song: Song,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Playlist) -> Unit
) {
    val colors = LocalEviColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Remove from playlist",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
            }

            if (playlists.isEmpty()) {
                Text(
                    text = "\"${song.title}\" is not in any of your editable playlists.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                    items(playlists, key = { it.id }) { pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPlaylist(pl)
                                    onDismiss()
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(
                                url = pl.resolvedCoverUrl(),
                                contentDescription = pl.name,
                                modifier = Modifier.size(46.dp),
                                shape = RoundedCornerShape(6.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pl.name, color = colors.textPrimary, fontWeight = FontWeight.Medium)
                                Text("${pl.songs.size} songs", color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                            }
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove",
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
