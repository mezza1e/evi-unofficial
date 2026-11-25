package wtf.evi.mobile.ui.components

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import wtf.evi.mobile.data.model.AppSettings
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.EVI_BASE_URL
import wtf.evi.mobile.data.model.EqPreset
import wtf.evi.mobile.data.model.LyricsResponse
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.player.PlaybackUiState
import wtf.evi.mobile.ui.theme.LocalEviColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingDialog(
    playbackState: PlaybackUiState,
    settings: AppSettings,
    isLiked: Boolean,
    sleepTimerRemainingMs: Long?,
    lyrics: LyricsResponse?,
    lyricsLoading: Boolean,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onOpenArtist: (String, String) -> Unit,
    onOpenMoreOptions: (Song) -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onClearUpcomingQueue: () -> Unit,
    onSelectQuality: (AudioQuality) -> Unit,
    onSelectEqPreset: (EqPreset) -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onToggleAutoplayRadio: (Boolean) -> Unit,
    onToggleEq: (Boolean) -> Unit = {},
    onBandChange: (Int, Float) -> Unit = { _, _ -> },
    onBassBoostChange: (Float) -> Unit = {},
    onVirtualizerChange: (Float) -> Unit = {},
    onResetEq: () -> Unit = {},
    startWithLyrics: Boolean = false
) {
    val song = playbackState.currentSong ?: return
    val colors = LocalEviColors.current
    val context = LocalContext.current

    var showLyrics by remember(song.id, startWithLyrics) { mutableStateOf(startWithLyrics) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showAudioTools by remember { mutableStateOf(false) }
    var showEqualizerSheet by remember { mutableStateOf(false) }

    val topBg = lerp(colors.background, colors.coverTint, 0.42f)
    val midBg = lerp(colors.background, colors.coverTint, 0.16f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
                ?: (context as? ComponentActivity)?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                // Keep 3-button system navigation bar (Back, Home, Recents) permanently visible
                // matching Home, Library, Search, and Charts with zero twitching or layout shift
                insetsController.isAppearanceLightNavigationBars = false
                insetsController.show(WindowInsetsCompat.Type.navigationBars())
            }
            onDispose { }
        }

        val density = LocalDensity.current
        val activity = context as? ComponentActivity
        val activityNavBarPx = remember(activity) {
            val decor = activity?.window?.decorView
            if (decor != null) {
                ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            } else {
                0
            }
        }
        val activityNavBarDp = with(density) { activityNavBarPx.toDp() }
        val composeNavBarDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        val navBarHeight: Dp = if (composeNavBarDp.value > 0f) {
            composeNavBarDp
        } else if (activityNavBarDp.value > 0f) {
            activityNavBarDp
        } else {
            56.dp
        }
        val bottomBreathingRoom: Dp = 48.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(topBg, midBg, colors.background)
                    )
                )
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp)
                    .padding(top = 10.dp, bottom = navBarHeight + bottomBreathingRoom)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Collapse",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "NOW PLAYING",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                        if (song.album.isNotBlank()) {
                            Text(
                                text = song.album,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Audio quality badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.textPrimary.copy(alpha = 0.12f))
                                .clickable { showAudioTools = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = settings.audioQuality.badge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (settings.audioQuality == AudioQuality.LOSSLESS) colors.accent else colors.textPrimary
                            )
                        }
                        // Lyrics button: to the left of the queue button
                        IconButton(
                            onClick = { showLyrics = !showLyrics },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lyrics,
                                contentDescription = "Lyrics",
                                modifier = Modifier.size(20.dp),
                                tint = if (showLyrics) colors.accent else colors.textSecondary
                            )
                        }
                        // Queue button
                        IconButton(
                            onClick = { showQueueSheet = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Queue",
                                modifier = Modifier.size(20.dp),
                                tint = colors.textSecondary
                            )
                        }
                        // More options button (3-dots)
                        IconButton(
                            onClick = { onOpenMoreOptions(song) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = "Song options",
                                modifier = Modifier.size(20.dp),
                                tint = colors.textSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Main Center Area: Cover Art OR Live Synced Lyrics
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (showLyrics) {
                        LyricsPanel(
                            lyrics = lyrics,
                            loading = lyricsLoading,
                            positionMs = playbackState.positionMs,
                            onSeekTo = onSeekTo
                        )
                    } else {
                        var dragAccum by remember { mutableFloatStateOf(0f) }
                        ArtworkImage(
                            url = song.resolvedCoverUrl(512),
                            contentDescription = song.title,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f)
                                .shadow(24.dp, RoundedCornerShape(16.dp))
                                .pointerInput(song.id) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragAccum = 0f },
                                        onHorizontalDrag = { _, d -> dragAccum += d },
                                        onDragEnd = {
                                            if (dragAccum < -90f) onSkipNext()
                                            else if (dragAccum > 90f) onSkipPrevious()
                                            dragAccum = 0f
                                        }
                                    )
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Track title + artist + Heart
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (song.explicit) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(colors.textSecondary.copy(alpha = 0.24f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("E", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                                }
                            }
                            Text(
                                text = song.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(enabled = song.artistId.isNotBlank()) {
                                    onDismiss()
                                    onOpenArtist(song.artistId, song.artist)
                                }
                            )
                        }
                    }
                    IconButton(onClick = onToggleLike) {
                        Icon(
                            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            tint = if (isLiked) colors.accent else colors.textPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Interactive Seekbar
                var dragging by remember { mutableStateOf(false) }
                var dragFraction by remember { mutableFloatStateOf(0f) }
                var localPendingSeekMs by remember { mutableStateOf<Long?>(null) }
                val durMs = playbackState.durationMs.coerceAtLeast(1L)
                val targetMs = localPendingSeekMs ?: playbackState.seekPendingPositionMs

                LaunchedEffect(playbackState.currentSong?.id) {
                    localPendingSeekMs = null
                }
                LaunchedEffect(playbackState.positionMs) {
                    val local = localPendingSeekMs
                    if (local != null && kotlin.math.abs(playbackState.positionMs - local) < 1500L) {
                        localPendingSeekMs = null
                    }
                }

                val effectivePosMs = when {
                    dragging -> (dragFraction * durMs).toLong()
                    targetMs != null -> targetMs
                    else -> playbackState.positionMs
                }
                val currentFraction = (effectivePosMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
                val shownPosMs = effectivePosMs

                Slider(
                    value = currentFraction,
                    onValueChange = {
                        dragging = true
                        dragFraction = it
                    },
                    onValueChangeFinished = {
                        val seekTarget = (dragFraction * durMs).toLong().coerceIn(0L, durMs)
                        localPendingSeekMs = seekTarget
                        onSeekTo(seekTarget)
                        dragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = colors.textPrimary,
                        activeTrackColor = colors.textPrimary,
                        inactiveTrackColor = colors.textPrimary.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatMs(shownPosMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Text(formatMs(playbackState.durationMs), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Main Transport Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (playbackState.shuffleEnabled) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onSkipPrevious, modifier = Modifier.size(54.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    Surface(
                        onClick = onTogglePlayPause,
                        shape = CircleShape,
                        color = colors.textPrimary,
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (playbackState.isBuffering && !playbackState.isPlaying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 3.dp,
                                    color = colors.background
                                )
                            } else {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                    tint = colors.background,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSkipNext, modifier = Modifier.size(54.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    IconButton(onClick = onCycleRepeat) {
                        val icon = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Repeat",
                            tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }

    if (showQueueSheet) {
        QueueModalSheet(
            playbackState = playbackState,
            autoplayRadio = settings.autoplayRadio,
            onDismiss = { showQueueSheet = false },
            onPlayIndex = onPlayQueueIndex,
            onRemoveIndex = onRemoveFromQueue,
            onClearUpcoming = onClearUpcomingQueue,
            onToggleAutoplay = onToggleAutoplayRadio
        )
    }

    if (showAudioTools) {
        AudioToolsSheet(
            settings = settings,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            onDismiss = { showAudioTools = false },
            onSelectQuality = onSelectQuality,
            onSelectEqPreset = onSelectEqPreset,
            onOpenFullEqualizer = {
                showAudioTools = false
                showEqualizerSheet = true
            },
            onSetSleepTimer = onSetSleepTimer,
            onToggleAutoplay = onToggleAutoplayRadio
        )
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

@Composable
private fun LyricsPanel(
    lyrics: LyricsResponse?,
    loading: Boolean,
    positionMs: Long,
    onSeekTo: (Long) -> Unit
) {
    val colors = LocalEviColors.current
    val lines = remember(lyrics) { lyrics?.getResolvedLines().orEmpty() }
    val listState = rememberLazyListState()

    val activeIndex = remember(lines, positionMs) {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].startTime <= positionMs) idx = i else break
        }
        idx
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && activeIndex < lines.size) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.32f))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            loading -> {
                CircularProgressIndicator(color = colors.accent)
            }
            lines.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 130.dp, bottom = 170.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        val isActive = index == activeIndex
                        val isPast = index < activeIndex
                        val lineText = buildAnnotatedString {
                            if (line.words.isNotEmpty()) {
                                for (w in line.words) {
                                    val wordColor = when {
                                        isActive && positionMs >= w.startTime -> colors.textPrimary
                                        isActive -> colors.textPrimary.copy(alpha = 0.45f)
                                        isPast -> colors.textSecondary.copy(alpha = 0.55f)
                                        else -> colors.textSecondary.copy(alpha = 0.40f)
                                    }
                                    withStyle(SpanStyle(color = wordColor)) {
                                        append(w.word)
                                    }
                                }
                            } else {
                                withStyle(
                                    SpanStyle(
                                        color = if (isActive) colors.textPrimary else if (isPast) colors.textSecondary.copy(alpha = 0.55f) else colors.textSecondary.copy(alpha = 0.40f)
                                    )
                                ) {
                                    append(line.fullText)
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSeekTo(line.startTime) }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = lineText,
                                fontSize = if (isActive) 24.sp else 18.sp,
                                lineHeight = if (isActive) 32.sp else 24.sp,
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                            if (line.translatedLyric.isNotBlank()) {
                                Text(
                                    text = line.translatedLyric,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
            }
            !lyrics?.plain.isNullOrBlank() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = lyrics?.plain.orEmpty(),
                        fontSize = 18.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary.copy(alpha = 0.9f)
                    )
                }
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No lyrics for this track",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Keep listening — the music speaks for itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueModalSheet(
    playbackState: PlaybackUiState,
    autoplayRadio: Boolean,
    onDismiss: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClearUpcoming: () -> Unit,
    onToggleAutoplay: (Boolean) -> Unit
) {
    val colors = LocalEviColors.current
    val curIdx = playbackState.currentIndex

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Queue", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
                if (playbackState.queue.size > curIdx + 1) {
                    TextButton(onClick = onClearUpcoming) {
                        Text("Clear upcoming", color = colors.accent)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Autoplay Radio", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
                    Text("Keep playing similar songs when queue ends", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Switch(checked = autoplayRadio, onCheckedChange = onToggleAutoplay)
            }

            HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 8.dp))

            LazyColumn(modifier = Modifier.height(340.dp)) {
                itemsIndexed(playbackState.queue) { index, item ->
                    val isCurrent = index == curIdx
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayIndex(index) }
                            .background(if (isCurrent) colors.accent.copy(alpha = 0.1f) else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(
                            url = item.resolvedCoverUrl(128),
                            contentDescription = item.title,
                            modifier = Modifier.size(44.dp),
                            shape = RoundedCornerShape(6.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = if (isCurrent) colors.accent else colors.textPrimary,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!isCurrent) {
                            IconButton(onClick = { onRemoveIndex(index) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = colors.textMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AudioToolsSheet(
    settings: AppSettings,
    sleepTimerRemainingMs: Long?,
    onDismiss: () -> Unit,
    onSelectQuality: (AudioQuality) -> Unit,
    onSelectEqPreset: (EqPreset) -> Unit,
    onOpenFullEqualizer: () -> Unit,
    onSetSleepTimer: (Int) -> Unit,
    onToggleAutoplay: (Boolean) -> Unit
) {
    val colors = LocalEviColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState())
        ) {
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

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Equalizer", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                TextButton(onClick = onOpenFullEqualizer) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Customize Bands", color = colors.accent, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.eqPreset == preset,
                        onClick = { onSelectEqPreset(preset) },
                        label = { Text(preset.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Sleep Timer", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                if (sleepTimerRemainingMs != null) {
                    Text(
                        text = "${formatMs(sleepTimerRemainingMs)} left",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.accent
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Off", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour").forEach { (mins, label) ->
                    val active = (mins == 0 && sleepTimerRemainingMs == null)
                    FilterChip(
                        selected = active,
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
                    Text("Auto-queue recommended tracks", style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
                Switch(checked = settings.autoplayRadio, onCheckedChange = onToggleAutoplay)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%d:%02d".format(m, s)
}
