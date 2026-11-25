package wtf.evi.mobile.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import wtf.evi.mobile.player.PlaybackUiState
import wtf.evi.mobile.ui.theme.LocalEviColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    playbackState: PlaybackUiState,
    isLiked: Boolean,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier
) {
    val song = playbackState.currentSong ?: return
    val colors = LocalEviColors.current
    val cardColor = lerp(colors.surfaceElevated, colors.coverTint, 0.22f)
    val activePosMs = playbackState.seekPendingPositionMs ?: playbackState.positionMs
    val progress = if (playbackState.durationMs > 0L) {
        (activePosMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    var dragAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .pointerInput(song.id) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onHorizontalDrag = { _, dragAmount -> dragAccum += dragAmount },
                    onDragEnd = {
                        if (dragAccum < -80f) onSkipNext()
                        else if (dragAccum > 80f) onSkipPrevious()
                        dragAccum = 0f
                    }
                )
            }
            .clickable(onClick = onOpenNowPlaying)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(
                url = song.resolvedCoverUrl(128),
                contentDescription = song.title,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(7.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee()
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleLike, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) colors.accent else colors.textPrimary,
                    modifier = Modifier.size(21.dp)
                )
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(40.dp)) {
                if (playbackState.isBuffering && !playbackState.isPlaying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.textPrimary
                    )
                } else {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            IconButton(onClick = onSkipNext, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.textPrimary.copy(alpha = 0.14f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(colors.accent)
            )
        }
    }
}
