package wtf.evi.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import wtf.evi.mobile.data.model.Album
import wtf.evi.mobile.data.model.Artist
import wtf.evi.mobile.data.model.FriendItem
import wtf.evi.mobile.data.model.HistoryEntry
import wtf.evi.mobile.data.model.Mix
import wtf.evi.mobile.data.model.Playlist
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.data.model.User
import wtf.evi.mobile.data.model.WeeklyPlaylist
import wtf.evi.mobile.data.network.EviApiClient
import wtf.evi.mobile.player.PlaybackUiState
import wtf.evi.mobile.ui.HomeUiState
import wtf.evi.mobile.ui.SearchUiState
import wtf.evi.mobile.ui.components.AlbumCard
import wtf.evi.mobile.ui.components.ArtistCircleCard
import wtf.evi.mobile.ui.components.ArtworkImage
import wtf.evi.mobile.ui.components.EviSwirlLogo
import wtf.evi.mobile.ui.components.SongRow
import wtf.evi.mobile.ui.theme.LocalEviColors

@Composable
fun HomeScreen(
    homeState: HomeUiState,
    user: User?,
    history: List<HistoryEntry>,
    likedIds: Set<String>,
    playbackState: PlaybackUiState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRefresh: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlaySongs: (List<Song>, Int, Boolean) -> Unit,
    onToggleLike: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onOpenMix: (Mix) -> Unit,
    onOpenWeekly: (WeeklyPlaylist) -> Unit,
    downloadedIds: Set<String> = emptySet()
) {
    val colors = LocalEviColors.current
    val greeting = when (EviApiClient.currentDaypart()) {
        "morning" -> "Good morning"
        "afternoon" -> "Good afternoon"
        "evening" -> "Good evening"
        else -> "Good night"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EviSwirlLogo(modifier = Modifier.size(28.dp), tint = colors.accent)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (user == null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.accent)
                            .clickable(onClick = onOpenSignIn)
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "Sign in",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.onAccent
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = colors.textPrimary
                    )
                }
            }
        }

        if (user == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(colors.accent.copy(alpha = 0.24f), colors.surfaceElevated)
                            )
                        )
                        .clickable(onClick = onOpenSignIn)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Connect your Evi account",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Sign in with Discord to stream songs, sync your playlists, and get Daily Mixes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = colors.accent, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }

        if (homeState.loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }
        } else if (homeState.error != null && homeState.trendingSongs.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(homeState.error, color = colors.textSecondary)
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }

        // Jump Back In (from history)
        if (history.isNotEmpty()) {
            item {
                SectionHeader("Jump back in")
                val recents = history.take(6)
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recents.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { entry ->
                                val s = entry.song
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceElevated)
                                        .clickable {
                                            val all = recents.map { it.song }
                                            onPlaySongs(all, all.indexOf(s).coerceAtLeast(0), false)
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ArtworkImage(
                                        url = s.resolvedCoverUrl(128),
                                        contentDescription = s.title,
                                        modifier = Modifier.size(56.dp),
                                        shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                                    )
                                    Text(
                                        text = s.title,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Made For You (Mixes + Weekly)
        if (homeState.mixes.isNotEmpty() || homeState.discoverWeekly != null || homeState.releaseRadar != null) {
            item {
                SectionHeader("Made for you")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    homeState.discoverWeekly?.let { wk ->
                        item {
                            MixCard(
                                title = wk.title.ifBlank { "Discover Weekly" },
                                subtitle = wk.subtitle.ifBlank { "Songs you haven't heard" },
                                imageUrl = wk.songs.firstOrNull()?.resolvedCoverUrl(256),
                                onClick = { onOpenWeekly(wk) }
                            )
                        }
                    }
                    homeState.releaseRadar?.let { wk ->
                        item {
                            MixCard(
                                title = wk.title.ifBlank { "Release Radar" },
                                subtitle = wk.subtitle.ifBlank { "New from artists you follow" },
                                imageUrl = wk.songs.firstOrNull()?.resolvedCoverUrl(256),
                                onClick = { onOpenWeekly(wk) }
                            )
                        }
                    }
                    items(homeState.mixes, key = { "mix_${it.index}" }) { mix ->
                        MixCard(
                            title = mix.title.ifBlank { "Daily Mix ${mix.index + 1}" },
                            subtitle = mix.subtitle.ifBlank { mix.artistName },
                            imageUrl = mix.resolvedCoverUrl(),
                            onClick = { onOpenMix(mix) }
                        )
                    }
                }
            }
        }

        // Trending Songs
        if (homeState.trendingSongs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Trending now", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                    TextButton(onClick = { onPlaySongs(homeState.trendingSongs, 0, true) }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Shuffle", color = colors.accent)
                    }
                }
            }
            itemsIndexed(homeState.trendingSongs.take(8), key = { _, s -> "tr_${s.id}" }) { idx, song ->
                SongRow(
                    song = song,
                    isCurrent = playbackState.currentSong?.id == song.id,
                    isPlaying = playbackState.isPlaying,
                    isLiked = likedIds.contains(song.id),
                    isDownloaded = downloadedIds.contains(song.id),
                    onClick = { onPlaySongs(homeState.trendingSongs, idx, false) },
                    onToggleLike = { onToggleLike(song) },
                    onMoreClick = { onMoreSong(song) }
                )
            }
        }

        // Trending Albums
        if (homeState.trendingAlbums.isNotEmpty()) {
            item {
                SectionHeader("Albums everyone's playing")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(homeState.trendingAlbums, key = { "ta_${it.id}" }) { album ->
                        AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                    }
                }
            }
        }

        // New Releases
        if (homeState.newReleases.isNotEmpty()) {
            item {
                SectionHeader("New releases")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(homeState.newReleases, key = { "nr_${it.id}" }) { album ->
                        AlbumCard(album = album, onClick = { onOpenAlbum(album) })
                    }
                }
            }
        }

        // Recommendations
        if (homeState.recommendations.isNotEmpty()) {
            item {
                SectionHeader("Picked for you")
            }
            itemsIndexed(homeState.recommendations.take(10), key = { _, s -> "rec_${s.id}" }) { idx, song ->
                SongRow(
                    song = song,
                    isCurrent = playbackState.currentSong?.id == song.id,
                    isPlaying = playbackState.isPlaying,
                    isLiked = likedIds.contains(song.id),
                    isDownloaded = downloadedIds.contains(song.id),
                    onClick = { onPlaySongs(homeState.recommendations, idx, false) },
                    onToggleLike = { onToggleLike(song) },
                    onMoreClick = { onMoreSong(song) }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = LocalEviColors.current.textPrimary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
    )
}

@Composable
private fun MixCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit
) {
    val colors = LocalEviColors.current
    Column(
        modifier = Modifier
            .width(152.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        ArtworkImage(
            url = imageUrl,
            contentDescription = title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SearchScreen(
    searchState: SearchUiState,
    recentSearches: List<String>,
    trendingSongs: List<Song>,
    likedIds: Set<String>,
    playbackState: PlaybackUiState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onClearRecents: () -> Unit,
    onPlaySongs: (List<Song>, Int, Boolean) -> Unit,
    onToggleLike: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    downloadedIds: Set<String> = emptySet()
) {
    val colors = LocalEviColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Text(
            text = "Search",
            style = MaterialTheme.typography.headlineLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)
        )

        OutlinedTextField(
            value = searchState.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Songs, artists, or a line of lyrics", color = colors.textMuted) },
            leadingIcon = {
                IconButton(
                    onClick = { if (searchState.query.isNotBlank()) onSubmitSearch(searchState.query) },
                    enabled = searchState.query.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = if (searchState.query.isNotBlank()) colors.accent else colors.textSecondary
                    )
                }
            },
            trailingIcon = {
                if (searchState.query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = colors.textSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceElevated,
                unfocusedContainerColor = colors.surfaceElevated,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(searchState.query) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding + 16.dp)
        ) {
            if (searchState.submittedQuery.isBlank()) {
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Recent searches", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            TextButton(onClick = onClearRecents) { Text("Clear", color = colors.textMuted) }
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(recentSearches) { q ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onSubmitSearch(q) },
                                    label = { Text(q, color = colors.textPrimary) },
                                    colors = FilterChipDefaults.filterChipColors(containerColor = colors.surfaceElevated)
                                )
                            }
                        }
                    }
                }

                if (trendingSongs.isNotEmpty()) {
                    item { SectionHeader("Trending searches") }
                    itemsIndexed(trendingSongs.take(12), key = { _, s -> "st_${s.id}" }) { idx, song ->
                        SongRow(
                            song = song,
                            isCurrent = playbackState.currentSong?.id == song.id,
                            isPlaying = playbackState.isPlaying,
                            isLiked = likedIds.contains(song.id),
                            isDownloaded = downloadedIds.contains(song.id),
                            onClick = { onPlaySongs(trendingSongs, idx, false) },
                            onToggleLike = { onToggleLike(song) },
                            onMoreClick = { onMoreSong(song) }
                        )
                    }
                }
            } else if (searchState.loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }
            } else if (searchState.songs.isEmpty() && searchState.artists.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Nothing found for \"${searchState.submittedQuery}\"", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Check the spelling, or try fewer words.", color = colors.textSecondary)
                    }
                }
            } else {
                // Top Result
                val topSong = searchState.songs.firstOrNull()
                if (topSong != null) {
                    item {
                        Text(
                            text = "Top result",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceElevated)
                                .clickable {
                                    onSubmitSearch(searchState.query)
                                    onPlaySongs(searchState.songs, 0, false)
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(
                                url = topSong.resolvedCoverUrl(256),
                                contentDescription = topSong.title,
                                modifier = Modifier.size(72.dp),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = topSong.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Song • ${topSong.artist}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = colors.onAccent, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }

                if (searchState.artists.isNotEmpty()) {
                    item {
                        SectionHeader("Artists")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchState.artists, key = { "sa_${it.id}" }) { artist ->
                                ArtistCircleCard(artist = artist, onClick = { onOpenArtist(artist) })
                            }
                        }
                    }
                }

                if (searchState.songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    itemsIndexed(searchState.songs, key = { i, s -> "ss_${i}_${s.id}" }) { idx, song ->
                        SongRow(
                            song = song,
                            isCurrent = playbackState.currentSong?.id == song.id,
                            isPlaying = playbackState.isPlaying,
                            isLiked = likedIds.contains(song.id),
                            isDownloaded = downloadedIds.contains(song.id),
                            onClick = {
                                onSubmitSearch(searchState.query)
                                onPlaySongs(searchState.songs, idx, false)
                            },
                            onToggleLike = { onToggleLike(song) },
                            onMoreClick = { onMoreSong(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExploreScreen(
    trendingSongs: List<Song>,
    newReleases: List<Album>,
    likedIds: Set<String>,
    playbackState: PlaybackUiState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onPlaySongs: (List<Song>, Int, Boolean) -> Unit,
    onToggleLike: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    downloadedIds: Set<String> = emptySet()
) {
    val colors = LocalEviColors.current
    var tab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (tab == 0) "Top Charts" else "New Releases",
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textPrimary
            )
            if (tab == 0 && trendingSongs.isNotEmpty()) {
                Button(
                    onClick = { onPlaySongs(trendingSongs, 0, false) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play all", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Charts", "New Releases").forEachIndexed { i, label ->
                FilterChip(
                    selected = tab == i,
                    onClick = { tab = i },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent.copy(alpha = 0.2f),
                        selectedLabelColor = colors.accent
                    )
                )
            }
        }

        if (tab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = bottomPadding + 16.dp)
            ) {
                itemsIndexed(trendingSongs, key = { _, s -> "chart_${s.id}" }) { idx, song ->
                    SongRow(
                        song = song,
                        trackNumber = idx + 1,
                        isCurrent = playbackState.currentSong?.id == song.id,
                        isPlaying = playbackState.isPlaying,
                        isLiked = likedIds.contains(song.id),
                        isDownloaded = downloadedIds.contains(song.id),
                        onClick = { onPlaySongs(trendingSongs, idx, false) },
                        onToggleLike = { onToggleLike(song) },
                        onMoreClick = { onMoreSong(song) }
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = bottomPadding + 16.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(newReleases, key = { "rel_${it.id}" }) { album ->
                    AlbumCard(
                        album = album,
                        onClick = { onOpenAlbum(album) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    user: User?,
    likes: List<Song>,
    playlists: List<Playlist>,
    follows: List<Artist>,
    history: List<HistoryEntry>,
    friends: List<FriendItem>,
    likedIds: Set<String>,
    playbackState: PlaybackUiState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLikedSongs: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onOpenArtist: (Artist) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onImportPlaylistUrl: (String, (String) -> Unit) -> Unit,
    onPlaySongs: (List<Song>, Int, Boolean) -> Unit,
    onToggleLike: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    downloadedSongs: List<Song> = emptyList(),
    downloadedIds: Set<String> = emptySet(),
    onOpenDownloads: () -> Unit = {}
) {
    val colors = LocalEviColors.current
    var filterTab by remember { mutableIntStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importFeedback by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your Library",
                style = MaterialTheme.typography.headlineLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showImportDialog = true }) {
                Icon(Icons.Rounded.Download, contentDescription = "Import playlist", tint = colors.textPrimary)
            }
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Create playlist", tint = colors.textPrimary)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = colors.textPrimary)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Playlists", "Downloads", "Artists", "History", "Friends").forEachIndexed { idx, label ->
                item {
                    FilterChip(
                        selected = filterTab == idx,
                        onClick = { filterTab = idx },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }
        }

        if (user == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EviSwirlLogo(modifier = Modifier.size(48.dp), tint = colors.accent)
                Spacer(modifier = Modifier.height(14.dp))
                Text("Sign in to view your library", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Liked songs, playlists, followed artists, history, and friend activity sync across Evi desktop and mobile.",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onOpenSignIn,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Sign in with Discord", fontWeight = FontWeight.SemiBold)
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding + 16.dp)
        ) {
            when (filterTab) {
                0 -> {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenLikedSongs)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(colors.accent, colors.accent.copy(alpha = 0.4f))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Favorite, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(26.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Liked Songs", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                Text("${likes.size} songs", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenDownloads)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(colors.accent.copy(alpha = 0.85f), colors.surfaceElevated)
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(26.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Downloaded Songs", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                Text("${downloadedSongs.size} songs • Offline playback", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                            }
                        }
                    }

                    items(playlists, key = { "pl_${it.id}" }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPlaylist(playlist) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(
                                url = playlist.resolvedCoverUrl(),
                                contentDescription = playlist.name,
                                modifier = Modifier.size(56.dp),
                                shape = RoundedCornerShape(8.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (playlist.smart) "Smart playlist" else "${playlist.songs.size} songs",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (downloadedSongs.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(44.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("No downloaded songs yet", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Tap the 3-dot menu on any song or the Download button on playlists to listen offline without internet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${downloadedSongs.size} songs offline", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { onPlaySongs(downloadedSongs, 0, false) },
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Play", fontWeight = FontWeight.SemiBold)
                                    }
                                    OutlinedButton(
                                        onClick = { onPlaySongs(downloadedSongs, 0, true) },
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Shuffle", color = colors.textPrimary)
                                    }
                                }
                            }
                        }
                        itemsIndexed(downloadedSongs, key = { _, s -> "dls_${s.id}" }) { idx, song ->
                            SongRow(
                                song = song,
                                isCurrent = playbackState.currentSong?.id == song.id,
                                isPlaying = playbackState.isPlaying,
                                isLiked = likedIds.contains(song.id),
                                isDownloaded = true,
                                onClick = { onPlaySongs(downloadedSongs, idx, false) },
                                onToggleLike = { onToggleLike(song) },
                                onMoreClick = { onMoreSong(song) }
                            )
                        }
                    }
                }

                2 -> {
                    if (follows.isEmpty()) {
                        item {
                            Text(
                                "Follow artists to see them here.",
                                color = colors.textSecondary,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    } else {
                        items(follows, key = { "fol_${it.id}" }) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenArtist(artist) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkImage(
                                    url = artist.resolvedImageUrl(),
                                    contentDescription = artist.name,
                                    modifier = Modifier.size(54.dp),
                                    shape = CircleShape,
                                    fallbackIcon = Icons.Rounded.Person
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(artist.name, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            }
                        }
                    }
                }

                3 -> {
                    if (history.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.History, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No recently played tracks yet", color = colors.textSecondary)
                            }
                        }
                    } else {
                        val songs = history.map { it.song }
                        itemsIndexed(history, key = { i, e -> "hist_${i}_${e.song.id}" }) { idx, entry ->
                            SongRow(
                                song = entry.song,
                                isCurrent = playbackState.currentSong?.id == entry.song.id,
                                isPlaying = playbackState.isPlaying,
                                isLiked = likedIds.contains(entry.song.id),
                                isDownloaded = downloadedIds.contains(entry.song.id),
                                onClick = { onPlaySongs(songs, idx, false) },
                                onToggleLike = { onToggleLike(entry.song) },
                                onMoreClick = { onMoreSong(entry.song) }
                            )
                        }
                    }
                }

                4 -> {
                    if (friends.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.Headphones, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No friends listening right now", color = colors.textSecondary)
                            }
                        }
                    } else {
                        items(friends, key = { "fr_${it.id}" }) { friend ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = friend.song != null) {
                                        friend.song?.let { onPlaySongs(listOf(it), 0, false) }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkImage(
                                    url = friend.song?.resolvedCoverUrl(128) ?: friend.resolvedAvatarUrl(),
                                    contentDescription = friend.displayName,
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    fallbackIcon = Icons.Rounded.Person
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = friend.displayName.ifBlank { friend.username },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = friend.song?.let { "${it.title} • ${it.artist}" } ?: "Not listening",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (friend.playing) colors.accent else colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
            title = { Text("Create playlist", color = colors.textPrimary) },
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
                    }
                }) { Text("Create", color = colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showImportDialog) {
        var url by remember { mutableStateOf("") }
        var importing by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!importing) showImportDialog = false },
            containerColor = colors.surfaceElevated,
            title = { Text("Import playlist", color = colors.textPrimary) },
            text = {
                Column {
                    Text(
                        "Paste a public playlist or album link from Spotify, Apple Music, Deezer or YouTube.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text("https://...") },
                        singleLine = true
                    )
                    if (!importFeedback.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(importFeedback.orEmpty(), color = colors.accent, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !importing && url.isNotBlank(),
                    onClick = {
                        importing = true
                        importFeedback = "Reading playlist..."
                        onImportPlaylistUrl(url.trim()) { msg ->
                            importing = false
                            importFeedback = msg
                        }
                    }
                ) { Text(if (importing) "Importing..." else "Import", color = colors.accent) }
            },
            dismissButton = {
                TextButton(enabled = !importing, onClick = {
                    importFeedback = null
                    showImportDialog = false
                }) { Text("Close") }
            }
        )
    }
}
