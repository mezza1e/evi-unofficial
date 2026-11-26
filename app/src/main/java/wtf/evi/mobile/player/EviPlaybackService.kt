package wtf.evi.mobile.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import wtf.evi.mobile.AppContainer
import wtf.evi.mobile.EviApplication
import wtf.evi.mobile.MainActivity
import wtf.evi.mobile.R
import wtf.evi.mobile.data.model.AppSettings
import wtf.evi.mobile.data.model.EVI_USER_AGENT
import wtf.evi.mobile.data.model.EqPreset
import wtf.evi.mobile.data.model.Song

@OptIn(UnstableApi::class)
class EviPlaybackService : MediaSessionService() {

    private lateinit var container: AppContainer
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var sessionPlayer: EviForwardingPlayer
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    // Service-managed repeat state: keeps underlying ExoPlayer permanently in REPEAT_MODE_OFF
    // so enabling Loop (1) mid-song NEVER triggers MediaPeriodQueue.updateRepeatMode -> seekToCurrentPosition(true)
    // codec/AudioTrack flushes or concurrent dual-period HTTP stream collisions on the same track URL.
    private var customRepeatMode: Int = Player.REPEAT_MODE_OFF
    private var activeMediaItemIndex: Int = 0
    private var isHandlingManagedRepeatTransition: Boolean = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioFetchJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var statsTickJob: Job? = null
    private var errorRetryJob: Job? = null
    private var accumulatedListenMs: Long = 0L
    private var lastReportedSongId: String? = null
    private var lastSongFractionRef: Song? = null
    private var lastSongPositionMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        container = (application as EviApplication).container
        ensureNotificationChannel()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_notification)
        setMediaNotificationProvider(notificationProvider)

        val okHttpFactory = OkHttpDataSource.Factory(container.streamingHttpClient)
            .setUserAgent(EVI_USER_AGENT)

        // Route file:// offline downloads directly to FileDataSource and https:// streams directly to OkHttpDataSource
        // without SimpleCache disk span locking or stale presigned redirect (exo_redir) persistence.
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 20_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 60_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableAudioFloatOutput(false)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        // Register service listener FIRST so managed repeat transitions execute before MediaSession listeners
        exoPlayer.addListener(playerListener)
        sessionPlayer = EviForwardingPlayer(exoPlayer)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(OkHttpBitmapLoader(this, container.httpClient))
            .setCallback(SessionCallback())
            .build()

        refreshCustomLayout()

        serviceScope.launch {
            container.likedIds.collectLatest {
                refreshCustomLayout()
            }
        }
        serviceScope.launch {
            container.settingsStore.settings.collectLatest { settings ->
                applyEqualizerSettings(settings)
            }
        }

        startTelemetryLoop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        flushListenStats()
        errorRetryJob?.cancel()
        sleepTimerJob?.cancel()
        radioFetchJob?.cancel()
        statsTickJob?.cancel()
        serviceScope.cancel()
        releaseAudioEffects()
        mediaSession?.release()
        mediaSession = null
        exoPlayer.release()
        super.onDestroy()
    }

    private fun releaseAudioEffects() {
        runCatching {
            if (currentAudioSessionId != C.AUDIO_SESSION_ID_UNSET && currentAudioSessionId > 0) {
                val closeIntent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, currentAudioSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                }
                sendBroadcast(closeIntent)
            }
        }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.playback_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.playback_channel_description)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun refreshCustomLayout() {
        val session = mediaSession ?: return
        val currentSong = MediaItemMapper.fromMediaItem(exoPlayer.currentMediaItem)
        val isLiked = currentSong != null && container.likedIds.value.contains(currentSong.id)

        val likeButton = CommandButton.Builder(
            if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        )
            .setDisplayName(if (isLiked) "Unlike" else "Like")
            .setCustomIconResId(if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
            .build()

        session.setCustomLayout(listOf(likeButton))
    }

    private fun isSettingsEffectivelyFlat(settings: AppSettings): Boolean {
        return !settings.eqEnabled || (
            settings.eqPreset == EqPreset.FLAT &&
                settings.bassBoostStrength <= 0.01f &&
                settings.virtualizerStrength <= 0.01f
            )
    }

    private fun initOrUpdateEqualizer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        val settings = container.settingsStore.settings.value
        if (isSettingsEffectivelyFlat(settings)) {
            currentAudioSessionId = audioSessionId
            runCatching { equalizer?.enabled = false }
            runCatching { bassBoost?.enabled = false }
            runCatching { virtualizer?.enabled = false }
            return
        }
        if (currentAudioSessionId == audioSessionId && equalizer != null) {
            applyEqualizerSettings(settings)
            return
        }
        currentAudioSessionId = audioSessionId

        runCatching {
            val openIntent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
            sendBroadcast(openIntent)
        }

        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }

        // Attach strictly to this player's valid audioSessionId (never global session 0)
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, audioSessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, audioSessionId) }.getOrNull()

        applyEqualizerSettings(settings)
    }

    private fun applyBandLevels(eq: Equalizer, range: ShortArray, weights: List<Float>) {
        val minMb = range[0].toInt()
        val maxMb = range[1].toInt()
        val bands = eq.numberOfBands.toInt()
        for (b in 0 until bands) {
            val idx = if (weights.size <= 1) 0 else {
                ((b.toFloat() / (bands - 1).coerceAtLeast(1)) * (weights.size - 1))
                    .toInt()
                    .coerceIn(0, weights.size - 1)
            }
            val w = weights.getOrElse(idx) { 0f }.coerceIn(-1f, 1f)
            val targetMb = if (w >= 0f) {
                (w * maxMb).toInt()
            } else {
                if (minMb < 0) (w * kotlin.math.abs(minMb)).toInt() else (minMb + (w + 1f) * (maxMb - minMb) / 2f).toInt()
            }
            val clamped = targetMb.coerceIn(minMb, maxMb).toShort()
            runCatching { eq.setBandLevel(b.toShort(), clamped) }
        }
    }

    private fun applyEqualizerSettings(settings: AppSettings) {
        if (isSettingsEffectivelyFlat(settings)) {
            runCatching { equalizer?.enabled = false }
            runCatching { bassBoost?.enabled = false }
            runCatching { virtualizer?.enabled = false }
            return
        }

        if (equalizer == null) {
            val curId = exoPlayer.audioSessionId
            if (curId != C.AUDIO_SESSION_ID_UNSET && curId > 0) {
                initOrUpdateEqualizer(curId)
                return
            }
        }
        val eq = equalizer ?: return

        // 1. Multi-band Equalizer & hardware presets (isolated error boundary)
        runCatching {
            val range = eq.bandLevelRange
            if (settings.eqPreset != EqPreset.CUSTOM) {
                val numPresets = runCatching { eq.numberOfPresets.toInt() }.getOrDefault(0)
                var matchedPresetIndex: Short = -1
                for (p in 0 until numPresets) {
                    val pName = runCatching { eq.getPresetName(p.toShort()) }.getOrNull() ?: continue
                    if (pName.equals(settings.eqPreset.label, ignoreCase = true) ||
                        pName.replace(" ", "").equals(settings.eqPreset.name.replace("_", ""), ignoreCase = true)
                    ) {
                        matchedPresetIndex = p.toShort()
                        break
                    }
                }
                if (matchedPresetIndex >= 0) {
                    runCatching { eq.usePreset(matchedPresetIndex) }
                } else {
                    applyBandLevels(eq, range, settings.eqPreset.bandWeights)
                }
            } else {
                applyBandLevels(eq, range, settings.customEqBands)
            }
            eq.enabled = true
        }

        // 2. Bass Boost effect (isolated error boundary)
        runCatching {
            val presetBassBoost = when (settings.eqPreset) {
                EqPreset.BASS_BOOST -> 0.85f
                EqPreset.HIP_HOP -> 0.60f
                EqPreset.ELECTRONIC, EqPreset.DANCE -> 0.55f
                EqPreset.ROCK -> 0.35f
                else -> 0f
            }
            val effectiveBassBoost = maxOf(settings.bassBoostStrength, presetBassBoost).coerceIn(0f, 1f)
            bassBoost?.let { bb ->
                if (bb.strengthSupported) {
                    val strength = (effectiveBassBoost * 1000).toInt().toShort()
                    bb.setStrength(strength)
                    bb.enabled = effectiveBassBoost > 0.05f
                }
            }
        }

        // 3. Surround Virtualizer effect (isolated error boundary)
        runCatching {
            val presetVirtualizer = when (settings.eqPreset) {
                EqPreset.ELECTRONIC, EqPreset.DANCE -> 0.60f
                EqPreset.VOCAL, EqPreset.ACOUSTIC -> 0.40f
                EqPreset.ROCK -> 0.30f
                else -> 0f
            }
            val effectiveVirtualizer = maxOf(settings.virtualizerStrength, presetVirtualizer).coerceIn(0f, 1f)
            virtualizer?.let { vz ->
                if (vz.strengthSupported) {
                    val strength = (effectiveVirtualizer * 1000).toInt().toShort()
                    vz.setStrength(strength)
                    vz.enabled = effectiveVirtualizer > 0.05f
                }
            }
        }
    }

    private fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            container.updateSleepTimerRemaining(null)
            return
        }
        val totalMs = minutes * 60_000L
        container.updateSleepTimerRemaining(totalMs)
        sleepTimerJob = serviceScope.launch {
            var remaining = totalMs
            while (remaining > 0L) {
                delay(1000L)
                remaining -= 1000L
                container.updateSleepTimerRemaining(remaining.coerceAtLeast(0L))
            }
            exoPlayer.pause()
            container.updateSleepTimerRemaining(null)
            container.emitToast("Sleep timer paused playback")
        }
    }

    private fun ensureAutoplayRadioIfNeeded(forceJumpToNext: Boolean = false) {
        if (!container.settingsStore.settings.value.autoplayRadio) return
        // When ANY loop/repeat mode is active (REPEAT_MODE_ALL or REPEAT_MODE_ONE),
        // NEVER fetch or append radio tracks. The playback queue MUST strictly stay within playlist bounds!
        if (customRepeatMode != Player.REPEAT_MODE_OFF && !forceJumpToNext) return
        val current = MediaItemMapper.fromMediaItem(exoPlayer.currentMediaItem) ?: return
        val remaining = exoPlayer.mediaItemCount - 1 - exoPlayer.currentMediaItemIndex
        if (!forceJumpToNext && remaining > 1) return
        if (radioFetchJob?.isActive == true) {
            if (forceJumpToNext) {
                radioFetchJob?.cancel()
            } else {
                return
            }
        }

        radioFetchJob = serviceScope.launch {
            val recent = buildList {
                val start = (exoPlayer.currentMediaItemIndex - 3).coerceAtLeast(0)
                for (i in start..exoPlayer.currentMediaItemIndex) {
                    if (i in 0 until exoPlayer.mediaItemCount) {
                        MediaItemMapper.fromMediaItem(exoPlayer.getMediaItemAt(i))?.let { add(it) }
                    }
                }
            }
            val nextSongs = runCatching { container.apiClient.getNextSongs(current, recent) }.getOrDefault(emptyList())
            val existingIds = buildSet {
                for (i in 0 until exoPlayer.mediaItemCount) {
                    MediaItemMapper.fromMediaItem(exoPlayer.getMediaItemAt(i))?.id?.let { add(it) }
                }
            }
            var fresh = nextSongs.filter { it.id.isNotBlank() && it.id !in existingIds }
            if (fresh.isEmpty()) {
                val recs = runCatching { container.apiClient.getRecommendations() }.getOrDefault(emptyList())
                fresh = recs.filter { it.id.isNotBlank() && it.id !in existingIds }
            }
            if (fresh.isEmpty()) {
                val trending = runCatching { container.apiClient.getTrending() }.getOrNull()
                fresh = trending?.songs.orEmpty().filter { it.id.isNotBlank() && it.id !in existingIds }
            }

            if (customRepeatMode != Player.REPEAT_MODE_OFF && !forceJumpToNext) return@launch

            if (fresh.isNotEmpty()) {
                val quality = container.settingsStore.settings.value.audioQuality
                val insertStart = exoPlayer.mediaItemCount
                val items = fresh.map { song ->
                    val localAudio = container.downloadStore.getDownloadedAudioFile(song.id)
                    val localThumb = container.downloadStore.getDownloadedThumbFile(song.id)
                    MediaItemMapper.toMediaItem(song, quality, localAudio, localThumb, isAutoplay = true)
                }
                exoPlayer.addMediaItems(items)
                if (forceJumpToNext) {
                    activeMediaItemIndex = insertStart
                    exoPlayer.seekTo(insertStart, 0L)
                    exoPlayer.play()
                }
            } else if (forceJumpToNext && exoPlayer.mediaItemCount > 0) {
                activeMediaItemIndex = 0
                exoPlayer.seekTo(0, 0L)
                exoPlayer.play()
            }
        }
    }

    /**
     * Removes appended autoplay radio items when repeat mode is enabled.
     * When [preserveNextReadingItem] is true (active playback), cur + 1 is preserved until the
     * current song finishes so ExoPlayer's pre-buffered reading MediaPeriod is never yanked
     * mid-song (which would trigger an internal seekToCurrentPosition(true) audio flush).
     */
    private fun trimUpcomingAutoplayTracks(preserveNextReadingItem: Boolean = false) {
        runCatching {
            val total = exoPlayer.mediaItemCount
            val cur = exoPlayer.currentMediaItemIndex
            val minIndex = if (preserveNextReadingItem) cur + 2 else cur + 1
            if (cur < 0 || total <= minIndex) return

            var firstAutoplayIndex = -1
            for (i in minIndex until total) {
                if (MediaItemMapper.isAutoplay(exoPlayer.getMediaItemAt(i))) {
                    firstAutoplayIndex = i
                    break
                }
            }
            if (firstAutoplayIndex != -1 && firstAutoplayIndex < total) {
                exoPlayer.removeMediaItems(firstAutoplayIndex, total)
            }
        }
    }

    private fun startTelemetryLoop() {
        statsTickJob = serviceScope.launch {
            var heartbeatCounter = 0
            while (true) {
                delay(1000L)
                val current = MediaItemMapper.fromMediaItem(exoPlayer.currentMediaItem)
                if (exoPlayer.isPlaying) {
                    accumulatedListenMs += 1000L
                    lastSongPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    if (accumulatedListenMs >= 30_000L) {
                        flushListenStats()
                    }
                }
                heartbeatCounter++
                if (heartbeatCounter >= 15) {
                    heartbeatCounter = 0
                    if (container.currentUser.value != null) {
                        launch {
                            container.apiClient.sendHeartbeat(
                                song = current,
                                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                                playing = exoPlayer.isPlaying
                            )
                        }
                    }
                }
            }
        }
    }

    private fun flushListenStats() {
        val ms = accumulatedListenMs
        accumulatedListenMs = 0L
        if (ms > 1000L && container.currentUser.value != null) {
            serviceScope.launch {
                container.apiClient.recordListenMs(ms)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        private var errorRetryCount = 0

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!isHandlingManagedRepeatTransition && exoPlayer.currentMediaItemIndex >= 0) {
                activeMediaItemIndex = exoPlayer.currentMediaItemIndex
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            initOrUpdateEqualizer(audioSessionId)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                errorRetryCount = 0
                errorRetryJob?.cancel()
                errorRetryJob = null
                val sessionId = exoPlayer.audioSessionId
                if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0 &&
                    (equalizer == null || currentAudioSessionId != sessionId)
                ) {
                    initOrUpdateEqualizer(sessionId)
                }
            } else if (playbackState == Player.STATE_ENDED && exoPlayer.mediaItemCount > 0) {
                when (customRepeatMode) {
                    Player.REPEAT_MODE_ONE -> {
                        val targetIdx = activeMediaItemIndex.coerceIn(0, exoPlayer.mediaItemCount - 1)
                        isHandlingManagedRepeatTransition = (exoPlayer.currentMediaItemIndex != targetIdx)
                        lastSongPositionMs = 0L
                        exoPlayer.seekTo(targetIdx, 0L)
                        exoPlayer.play()
                        trimUpcomingAutoplayTracks(preserveNextReadingItem = false)
                        refreshCustomLayout()
                    }
                    Player.REPEAT_MODE_ALL -> {
                        trimUpcomingAutoplayTracks(preserveNextReadingItem = false)
                        if (exoPlayer.mediaItemCount > 0) {
                            val firstIdx = exoPlayer.currentTimeline
                                .getFirstWindowIndex(exoPlayer.shuffleModeEnabled)
                                .takeIf { it in 0 until exoPlayer.mediaItemCount } ?: 0
                            activeMediaItemIndex = firstIdx
                            isHandlingManagedRepeatTransition = (exoPlayer.currentMediaItemIndex != firstIdx)
                            lastSongPositionMs = 0L
                            exoPlayer.seekTo(firstIdx, 0L)
                            exoPlayer.play()
                            refreshCustomLayout()
                        }
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Cancel any pending error retry for a previous item immediately so it can never hijack a new song
            errorRetryJob?.cancel()
            errorRetryJob = null
            errorRetryCount = 0

            // 1. Handle completion of our own managed repeat seek cleanly
            if (isHandlingManagedRepeatTransition) {
                isHandlingManagedRepeatTransition = false
                if (exoPlayer.currentMediaItemIndex >= 0) {
                    activeMediaItemIndex = exoPlayer.currentMediaItemIndex
                }
                lastSongPositionMs = 0L
                refreshCustomLayout()
                return
            }

            // 2. Intercept natural end-of-track rollover when Loop (1) is active
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                customRepeatMode == Player.REPEAT_MODE_ONE &&
                exoPlayer.mediaItemCount > 0
            ) {
                val targetIdx = activeMediaItemIndex.coerceIn(0, exoPlayer.mediaItemCount - 1)
                val prevSong = lastSongFractionRef
                if (prevSong != null) {
                    serviceScope.launch { container.apiClient.recordListened(prevSong, 1.0) }
                }
                isHandlingManagedRepeatTransition = true
                lastSongPositionMs = 0L
                exoPlayer.seekTo(targetIdx, 0L)
                if (exoPlayer.playWhenReady) exoPlayer.play()
                trimUpcomingAutoplayTracks(preserveNextReadingItem = false)
                refreshCustomLayout()
                return
            }

            // 3. Intercept natural rollover into a leftover autoplay item when Repeat All is active
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                customRepeatMode == Player.REPEAT_MODE_ALL &&
                MediaItemMapper.isAutoplay(mediaItem) &&
                exoPlayer.mediaItemCount > 0
            ) {
                val curIdx = exoPlayer.currentMediaItemIndex
                if (curIdx in 0 until exoPlayer.mediaItemCount) {
                    exoPlayer.removeMediaItems(curIdx, exoPlayer.mediaItemCount)
                }
                if (exoPlayer.mediaItemCount > 0) {
                    val firstIdx = exoPlayer.currentTimeline
                        .getFirstWindowIndex(exoPlayer.shuffleModeEnabled)
                        .takeIf { it in 0 until exoPlayer.mediaItemCount } ?: 0
                    activeMediaItemIndex = firstIdx
                    isHandlingManagedRepeatTransition = true
                    lastSongPositionMs = 0L
                    exoPlayer.seekTo(firstIdx, 0L)
                    if (exoPlayer.playWhenReady) exoPlayer.play()
                    refreshCustomLayout()
                }
                return
            }

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                lastSongPositionMs = 0L
                refreshCustomLayout()
                return
            }

            if (exoPlayer.currentMediaItemIndex >= 0) {
                activeMediaItemIndex = exoPlayer.currentMediaItemIndex
            }

            // Report listened fraction for previous song
            val prevSong = lastSongFractionRef
            if (prevSong != null && prevSong.durationMs > 0L) {
                val fraction = lastSongPositionMs.toDouble() / prevSong.durationMs.toDouble()
                serviceScope.launch {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && fraction < 0.35) {
                        container.apiClient.recordSkipped(prevSong)
                    } else {
                        container.apiClient.recordListened(prevSong, fraction)
                    }
                }
            }

            val song = MediaItemMapper.fromMediaItem(mediaItem)
            lastSongFractionRef = song
            lastSongPositionMs = 0L
            refreshCustomLayout()

            if (song != null && song.id != lastReportedSongId) {
                lastReportedSongId = song.id
                serviceScope.launch {
                    container.apiClient.recordPlayed(song)
                    if (container.currentUser.value != null) {
                        container.apiClient.sendHeartbeat(song, 0L, exoPlayer.isPlaying)
                    }
                }
            }
            ensureAutoplayRadioIfNeeded(forceJumpToNext = false)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                flushListenStats()
            }
            val current = MediaItemMapper.fromMediaItem(exoPlayer.currentMediaItem)
            if (container.currentUser.value != null) {
                serviceScope.launch {
                    container.apiClient.sendHeartbeat(
                        current,
                        exoPlayer.currentPosition.coerceAtLeast(0L),
                        isPlaying
                    )
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            errorRetryJob?.cancel()
            errorRetryJob = null

            val currentItem = exoPlayer.currentMediaItem
            val curIdx = exoPlayer.currentMediaItemIndex
            val isLocalFile = currentItem?.localConfiguration?.uri?.scheme == "file"

            // 1. If a local offline file is corrupted or unreadable, purge the bad file and fall back to server stream
            if (isLocalFile && curIdx in 0 until exoPlayer.mediaItemCount) {
                val song = MediaItemMapper.fromMediaItem(currentItem)
                if (song != null) {
                    container.downloadStore.removeDownloadedSong(song.id)
                    val quality = container.settingsStore.settings.value.audioQuality
                    val streamingItem = MediaItemMapper.toMediaItem(
                        song = song,
                        quality = quality,
                        localAudioFile = null,
                        localThumbFile = null,
                        isAutoplay = MediaItemMapper.isAutoplay(currentItem)
                    )
                    container.emitToast("Offline copy unreadable — streaming from Evi...")
                    exoPlayer.replaceMediaItem(curIdx, streamingItem)
                    exoPlayer.seekTo(curIdx, 0L)
                    exoPlayer.prepare()
                    exoPlayer.play()
                    return
                }
            }

            val isNetworkOrIo = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.cause is java.io.IOException

            if (isNetworkOrIo && errorRetryCount < 2 && currentItem != null) {
                errorRetryCount++
                val retryAttempt = errorRetryCount
                val failedMediaId = currentItem.mediaId
                // First retry attempts resume position; second retry starts cleanly from 0L
                // in case mid-stream HTTP range seeking is unsupported for this track.
                val targetPos = if (retryAttempt == 1) lastSongPositionMs.coerceAtLeast(0L) else 0L
                errorRetryJob = serviceScope.launch {
                    delay(650L * retryAttempt)
                    if (exoPlayer.currentMediaItem?.mediaId == failedMediaId && exoPlayer.playWhenReady) {
                        exoPlayer.seekTo(targetPos)
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                return
            }
            errorRetryCount = 0

            // Under Loop (1), stop cleanly if track permanently fails so we never enter an infinite skip/retry loop
            if (customRepeatMode == Player.REPEAT_MODE_ONE) {
                container.emitToast("Couldn't play this track")
                exoPlayer.pause()
                return
            }

            // In a multi-track queue, advance once to keep playback moving
            if (exoPlayer.mediaItemCount > 1 && sessionPlayer.hasNextMediaItem()) {
                container.emitToast("Skipping unavailable track")
                sessionPlayer.seekToNext()
                exoPlayer.prepare()
                exoPlayer.play()
                return
            }

            val msg = error.cause?.message.orEmpty()
            val userMsg = when {
                msg.contains("403") || msg.contains("401") ->
                    "Sign in with your Evi account to play tracks"
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Network error while streaming track"
                else -> "Couldn't play this track (${error.errorCodeName})"
            }
            container.emitToast(userMsg)
        }
    }

    private inner class EviForwardingPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        private val registeredListeners = LinkedHashMap<Player.Listener, EviListenerAdapter>()

        override fun addListener(listener: Player.Listener) {
            val adapter = synchronized(registeredListeners) {
                registeredListeners.getOrPut(listener) { EviListenerAdapter(listener) }
            }
            wrappedPlayer.addListener(adapter)
        }

        override fun removeListener(listener: Player.Listener) {
            val adapter = synchronized(registeredListeners) {
                registeredListeners.remove(listener)
            }
            if (adapter != null) {
                wrappedPlayer.removeListener(adapter)
            }
        }

        override fun getRepeatMode(): Int = customRepeatMode

        override fun setRepeatMode(repeatMode: Int) {
            val normalized = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            if (customRepeatMode == normalized) return
            customRepeatMode = normalized

            if (exoPlayer.currentMediaItemIndex >= 0) {
                activeMediaItemIndex = exoPlayer.currentMediaItemIndex
            }

            if (normalized != Player.REPEAT_MODE_OFF) {
                radioFetchJob?.cancel()
                val activelyStreaming = exoPlayer.isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING
                trimUpcomingAutoplayTracks(preserveNextReadingItem = activelyStreaming)
            } else {
                ensureAutoplayRadioIfNeeded(forceJumpToNext = false)
            }

            val snapshot = synchronized(registeredListeners) { registeredListeners.keys.toList() }
            val repeatEvents = Player.Events(
                FlagSet.Builder()
                    .add(Player.EVENT_REPEAT_MODE_CHANGED)
                    .add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                    .build()
            )
            val cmds = availableCommands
            for (listener in snapshot) {
                listener.onRepeatModeChanged(normalized)
                listener.onAvailableCommandsChanged(cmds)
                listener.onEvents(this, repeatEvents)
            }
        }

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            val builder = base.buildUpon()
                .add(COMMAND_SET_REPEAT_MODE)
                .add(COMMAND_SET_SHUFFLE_MODE)
            if (mediaItemCount > 0) {
                builder
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            return builder.build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (command == COMMAND_SET_REPEAT_MODE || command == COMMAND_SET_SHUFFLE_MODE) {
                return true
            }
            if (mediaItemCount > 0 && (
                    command == COMMAND_SEEK_TO_PREVIOUS ||
                        command == COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                        command == COMMAND_SEEK_TO_NEXT ||
                        command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    )
            ) {
                return true
            }
            return super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean {
            if (mediaItemCount <= 0) return false
            if (customRepeatMode == Player.REPEAT_MODE_ALL || customRepeatMode == Player.REPEAT_MODE_ONE) return true
            if (container.settingsStore.settings.value.autoplayRadio) return true
            return super.hasNextMediaItem()
        }

        override fun hasPreviousMediaItem(): Boolean {
            if (mediaItemCount <= 0) return false
            if (currentPosition > 3000L) return true
            if (customRepeatMode == Player.REPEAT_MODE_ALL) return true
            return super.hasPreviousMediaItem()
        }

        override fun seekToNext() {
            val count = mediaItemCount
            if (count <= 0) return
            errorRetryJob?.cancel()
            errorRetryJob = null

            when (customRepeatMode) {
                Player.REPEAT_MODE_ONE -> {
                    // Explicit "Next" button press in Loop (1) advances to the next track in the queue
                    // and continues looping the newly selected track.
                    if (count > 1) {
                        val nextIdx = exoPlayer.currentTimeline
                            .getNextWindowIndex(currentMediaItemIndex, Player.REPEAT_MODE_ALL, shuffleModeEnabled)
                            .takeIf { it in 0 until count }
                            ?: ((currentMediaItemIndex + 1) % count)
                        activeMediaItemIndex = nextIdx
                        seekTo(nextIdx, 0L)
                    } else {
                        seekTo(0, 0L)
                    }
                }
                Player.REPEAT_MODE_ALL -> {
                    if (super.hasNextMediaItem()) {
                        val nextIdx = super.nextMediaItemIndex
                        if (nextIdx in 0 until count && MediaItemMapper.isAutoplay(getMediaItemAt(nextIdx))) {
                            exoPlayer.removeMediaItems(nextIdx, count)
                            val firstIdx = exoPlayer.currentTimeline
                                .getFirstWindowIndex(shuffleModeEnabled)
                                .takeIf { it in 0 until mediaItemCount } ?: 0
                            activeMediaItemIndex = firstIdx
                            seekTo(firstIdx, 0L)
                        } else {
                            super.seekToNextMediaItem()
                        }
                    } else {
                        val firstIdx = exoPlayer.currentTimeline
                            .getFirstWindowIndex(shuffleModeEnabled)
                            .takeIf { it in 0 until count } ?: 0
                        activeMediaItemIndex = firstIdx
                        seekTo(firstIdx, 0L)
                    }
                }
                else -> {
                    if (super.hasNextMediaItem()) {
                        super.seekToNextMediaItem()
                    } else if (container.settingsStore.settings.value.autoplayRadio) {
                        ensureAutoplayRadioIfNeeded(forceJumpToNext = true)
                    }
                }
            }
        }

        override fun seekToNextMediaItem() {
            seekToNext()
        }

        override fun seekToPrevious() {
            val count = mediaItemCount
            if (count <= 0) return
            errorRetryJob?.cancel()
            errorRetryJob = null

            if (currentPosition > 3000L) {
                seekTo(0L)
            } else if (super.hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
            } else if (customRepeatMode == Player.REPEAT_MODE_ALL && count > 1) {
                val lastIdx = exoPlayer.currentTimeline
                    .getLastWindowIndex(shuffleModeEnabled)
                    .takeIf { it in 0 until count } ?: (count - 1)
                activeMediaItemIndex = lastIdx
                seekTo(lastIdx, 0L)
            } else {
                seekTo(0L)
            }
        }

        override fun seekToPreviousMediaItem() {
            seekToPrevious()
        }

        private inner class EviListenerAdapter(
            private val delegate: Player.Listener
        ) : Player.Listener {

            override fun onEvents(player: Player, events: Player.Events) {
                delegate.onEvents(this@EviForwardingPlayer, events)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                delegate.onTimelineChanged(timeline, reason)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Suppress transient rollover notification when managed Repeat-One or Repeat-All
                // has already redirected ExoPlayer back to the target loop index in playerListener.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && (
                        customRepeatMode == Player.REPEAT_MODE_ONE ||
                            (customRepeatMode == Player.REPEAT_MODE_ALL && MediaItemMapper.isAutoplay(mediaItem))
                        )
                ) {
                    return
                }
                delegate.onMediaItemTransition(mediaItem, reason)
            }

            override fun onTracksChanged(tracks: Tracks) {
                delegate.onTracksChanged(tracks)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                delegate.onMediaMetadataChanged(mediaMetadata)
            }

            override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
                delegate.onPlaylistMetadataChanged(mediaMetadata)
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                delegate.onIsLoadingChanged(isLoading)
            }

            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                delegate.onAvailableCommandsChanged(this@EviForwardingPlayer.availableCommands)
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                delegate.onTrackSelectionParametersChanged(parameters)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Suppress transient STATE_ENDED when Repeat-One or Repeat-All immediately restarts
                if (playbackState == Player.STATE_ENDED &&
                    customRepeatMode != Player.REPEAT_MODE_OFF &&
                    exoPlayer.mediaItemCount > 0
                ) {
                    return
                }
                delegate.onPlaybackStateChanged(playbackState)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                delegate.onPlayWhenReadyChanged(playWhenReady, reason)
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                delegate.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                delegate.onIsPlayingChanged(isPlaying)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                // Suppressed: customRepeatMode notifications are dispatched explicitly by setRepeatMode()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                delegate.onShuffleModeEnabledChanged(shuffleModeEnabled)
            }

            override fun onPlayerError(error: PlaybackException) {
                delegate.onPlayerError(error)
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                delegate.onPlayerErrorChanged(error)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                delegate.onPositionDiscontinuity(oldPosition, newPosition, reason)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                delegate.onPlaybackParametersChanged(playbackParameters)
            }

            override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) {
                delegate.onSeekBackIncrementChanged(seekBackIncrementMs)
            }

            override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) {
                delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)
            }

            override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) {
                delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                delegate.onAudioSessionIdChanged(audioSessionId)
            }

            override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) {
                delegate.onAudioAttributesChanged(audioAttributes)
            }

            override fun onVolumeChanged(volume: Float) {
                delegate.onVolumeChanged(volume)
            }

            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
                delegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                delegate.onDeviceInfoChanged(deviceInfo)
            }

            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                delegate.onDeviceVolumeChanged(volume, muted)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                delegate.onVideoSizeChanged(videoSize)
            }

            override fun onSurfaceSizeChanged(width: Int, height: Int) {
                delegate.onSurfaceSizeChanged(width, height)
            }

            override fun onRenderedFirstFrame() {
                delegate.onRenderedFirstFrame()
            }

            override fun onCues(cueGroup: CueGroup) {
                delegate.onCues(cueGroup)
            }

            override fun onMetadata(metadata: Metadata) {
                delegate.onMetadata(metadata)
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_LIKE -> {
                    val song = MediaItemMapper.fromMediaItem(exoPlayer.currentMediaItem)
                    if (song != null) {
                        container.toggleLike(song)
                        refreshCustomLayout()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_SET_SLEEP_TIMER -> {
                    val minutes = args.getInt(ARG_SLEEP_MINUTES, 0)
                    startSleepTimer(minutes)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    companion object {
        const val CHANNEL_ID = "evi_playback"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"
        const val ACTION_TOGGLE_LIKE = "wtf.evi.mobile.TOGGLE_LIKE"
        const val ACTION_SET_SLEEP_TIMER = "wtf.evi.mobile.SET_SLEEP_TIMER"
        const val ARG_SLEEP_MINUTES = "sleep_minutes"
    }
}
