package wtf.evi.mobile.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wtf.evi.mobile.AppContainer
import wtf.evi.mobile.data.model.Song

data class PlaybackUiState(
    val currentSong: Song? = null,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val seekPendingPositionMs: Long? = null
)

class PlaybackController(
    private val context: Context,
    private val container: AppContainer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var tickerJob: Job? = null
    private var pendingSeekTargetMs: Long? = null
    private var lastSeekTimestampMs: Long = 0L
    private var cachedQueue: List<Song> = emptyList()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    fun connect() {
        if (controller != null) return
        scope.launch {
            runCatching {
                val token = SessionToken(context, ComponentName(context, EviPlaybackService::class.java))
                val mc = MediaController.Builder(context, token).buildAsync().await()
                controller = mc
                mc.addListener(controllerListener)
                syncFromPlayer(mc, fullQueueRefresh = true)
                startPositionTicker()
            }
        }
    }

    fun disconnect() {
        tickerJob?.cancel()
        controller?.removeListener(controllerListener)
        controller?.release()
        controller = null
        cachedQueue = emptyList()
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val mc = controller
                if (mc != null && (mc.isPlaying || mc.playbackState == Player.STATE_BUFFERING)) {
                    val cur = _state.value
                    val targetMs = pendingSeekTargetMs
                    val now = System.currentTimeMillis()
                    val actualPos = mc.currentPosition.coerceAtLeast(0L)

                    val effectivePos = if (targetMs != null) {
                        val diff = kotlin.math.abs(actualPos - targetMs)
                        if (diff < 1500L || (mc.playbackState == Player.STATE_READY && (now - lastSeekTimestampMs) > 2500L)) {
                            pendingSeekTargetMs = null
                            actualPos
                        } else {
                            targetMs
                        }
                    } else {
                        actualPos
                    }

                    val dur = mc.duration.takeIf { it > 0L } ?: (cur.currentSong?.durationMs ?: 0L)
                    _state.value = cur.copy(
                        positionMs = effectivePos,
                        seekPendingPositionMs = pendingSeekTargetMs,
                        durationMs = dur,
                        bufferedMs = mc.bufferedPosition.coerceAtLeast(0L)
                    )
                }
                delay(100L)
            }
        }
    }

    private fun syncFromPlayer(mc: MediaController, fullQueueRefresh: Boolean = false) {
        val queue = if (fullQueueRefresh || cachedQueue.size != mc.mediaItemCount || cachedQueue.isEmpty()) {
            val q = (0 until mc.mediaItemCount).mapNotNull { idx ->
                MediaItemMapper.fromMediaItem(mc.getMediaItemAt(idx))
            }
            cachedQueue = q
            q
        } else {
            cachedQueue
        }
        val currentIdx = mc.currentMediaItemIndex.takeIf { it in queue.indices } ?: -1
        val currentSong = MediaItemMapper.fromMediaItem(mc.currentMediaItem)
        val dur = mc.duration.takeIf { it > 0L } ?: (currentSong?.durationMs ?: 0L)
        val targetMs = pendingSeekTargetMs
        val now = System.currentTimeMillis()
        val actualPos = mc.currentPosition.coerceAtLeast(0L)

        val effectivePos = if (targetMs != null) {
            val diff = kotlin.math.abs(actualPos - targetMs)
            if (diff < 1500L || (mc.playbackState == Player.STATE_READY && (now - lastSeekTimestampMs) > 2500L)) {
                pendingSeekTargetMs = null
                actualPos
            } else {
                targetMs
            }
        } else {
            actualPos
        }

        _state.value = PlaybackUiState(
            currentSong = currentSong,
            queue = queue,
            currentIndex = currentIdx,
            isPlaying = mc.isPlaying,
            isBuffering = mc.playbackState == Player.STATE_BUFFERING,
            positionMs = effectivePos,
            seekPendingPositionMs = pendingSeekTargetMs,
            durationMs = dur,
            bufferedMs = mc.bufferedPosition.coerceAtLeast(0L),
            shuffleEnabled = mc.shuffleModeEnabled,
            repeatMode = mc.repeatMode
        )
    }

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                pendingSeekTargetMs = null
            }
            val needsQueueRefresh = events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            controller?.let { syncFromPlayer(it, fullQueueRefresh = needsQueueRefresh) }
        }
    }

    private fun toMediaItemWithOfflineCheck(song: Song): MediaItem {
        val quality = container.settingsStore.settings.value.audioQuality
        val audioFile = container.downloadStore.getDownloadedAudioFile(song.id)
        val thumbFile = container.downloadStore.getDownloadedThumbFile(song.id)
        return MediaItemMapper.toMediaItem(song, quality, audioFile, thumbFile)
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        val clean = songs.filter { it.id.isNotBlank() }
        if (clean.isEmpty()) return
        val mc = controller ?: return
        cachedQueue = emptyList()
        val items = clean.map { toMediaItemWithOfflineCheck(it) }
        val safeIdx = startIndex.coerceIn(0, items.lastIndex)
        mc.shuffleModeEnabled = shuffle
        mc.setMediaItems(items, safeIdx, 0L)
        mc.prepare()
        mc.play()
        syncFromPlayer(mc, fullQueueRefresh = true)
    }

    fun playAtQueueIndex(index: Int) {
        val mc = controller ?: return
        if (index in 0 until mc.mediaItemCount) {
            mc.seekTo(index, 0L)
            mc.play()
        }
    }

    fun playNext(song: Song) {
        val mc = controller ?: return
        val item = toMediaItemWithOfflineCheck(song)
        if (mc.mediaItemCount == 0) {
            playQueue(listOf(song), 0)
            return
        }
        val insertIndex = (mc.currentMediaItemIndex + 1).coerceAtMost(mc.mediaItemCount)
        mc.addMediaItem(insertIndex, item)
        container.emitToast("Playing next: ${song.title}")
        syncFromPlayer(mc, fullQueueRefresh = true)
    }

    fun addToQueue(songs: List<Song>) {
        val clean = songs.filter { it.id.isNotBlank() }
        if (clean.isEmpty()) return
        val mc = controller ?: return
        if (mc.mediaItemCount == 0) {
            playQueue(clean, 0)
            return
        }
        mc.addMediaItems(clean.map { toMediaItemWithOfflineCheck(it) })
        val label = if (clean.size == 1) "Added \"${clean.first().title}\" to queue" else "Added ${clean.size} songs to queue"
        container.emitToast(label)
        syncFromPlayer(mc, fullQueueRefresh = true)
    }

    fun togglePlayPause() {
        val mc = controller ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun seekTo(positionMs: Long) {
        val mc = controller ?: return
        val safePos = positionMs.coerceAtLeast(0L)
        pendingSeekTargetMs = safePos
        lastSeekTimestampMs = System.currentTimeMillis()
        _state.value = _state.value.copy(
            positionMs = safePos,
            seekPendingPositionMs = safePos
        )
        mc.seekTo(safePos)
    }

    fun skipNext() {
        val mc = controller ?: return
        if (mc.repeatMode == Player.REPEAT_MODE_ONE && mc.mediaItemCount > 1) {
            val nextIdx = mc.currentTimeline
                .getNextWindowIndex(mc.currentMediaItemIndex, Player.REPEAT_MODE_ALL, mc.shuffleModeEnabled)
                .takeIf { it != androidx.media3.common.C.INDEX_UNSET && it in 0 until mc.mediaItemCount }
                ?: ((mc.currentMediaItemIndex + 1) % mc.mediaItemCount)
            mc.seekTo(nextIdx, 0L)
            mc.play()
            return
        }
        mc.seekToNext()
    }

    fun skipPrevious() {
        controller?.seekToPrevious()
    }

    fun toggleShuffle() {
        val mc = controller ?: return
        mc.shuffleModeEnabled = !mc.shuffleModeEnabled
        syncFromPlayer(mc)
    }

    fun cycleRepeat() {
        val mc = controller ?: return
        mc.repeatMode = when (mc.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        syncFromPlayer(mc)
    }

    fun removeFromQueue(index: Int) {
        val mc = controller ?: return
        if (index in 0 until mc.mediaItemCount && index != mc.currentMediaItemIndex) {
            mc.removeMediaItem(index)
            syncFromPlayer(mc, fullQueueRefresh = true)
        }
    }

    fun clearUpcomingQueue() {
        val mc = controller ?: return
        val cur = mc.currentMediaItemIndex
        if (cur >= 0 && mc.mediaItemCount > cur + 1) {
            mc.removeMediaItems(cur + 1, mc.mediaItemCount)
            syncFromPlayer(mc, fullQueueRefresh = true)
        }
    }

    fun setSleepTimer(minutes: Int) {
        val mc = controller ?: return
        val args = Bundle().apply {
            putInt(EviPlaybackService.ARG_SLEEP_MINUTES, minutes)
        }
        mc.sendCustomCommand(SessionCommand(EviPlaybackService.ACTION_SET_SLEEP_TIMER, Bundle.EMPTY), args)
        if (minutes > 0) {
            container.emitToast("Sleep timer set for $minutes min")
        } else {
            container.emitToast("Sleep timer turned off")
        }
    }

    fun refreshStreamQuality() {
        val mc = controller ?: return
        val cur = _state.value
        if (cur.queue.isEmpty() || cur.currentIndex !in cur.queue.indices) return
        val wasPlaying = mc.isPlaying
        val pos = mc.currentPosition.coerceAtLeast(0L)
        val items = cur.queue.map { toMediaItemWithOfflineCheck(it) }
        cachedQueue = emptyList()
        mc.setMediaItems(items, cur.currentIndex, pos)
        mc.prepare()
        if (wasPlaying) mc.play()
        syncFromPlayer(mc, fullQueueRefresh = true)
    }
}
