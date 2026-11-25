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
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
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
    private lateinit var sessionPlayer: Player
    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioFetchJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var statsTickJob: Job? = null
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

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(container.mediaCache)
            .setUpstreamDataSourceFactory(okHttpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 45_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableAudioFloatOutput(false)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        sessionPlayer = EviForwardingPlayer(exoPlayer)
        exoPlayer.addListener(playerListener)

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
            .setBitmapLoader(OkHttpBitmapLoader(container.httpClient))
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
        sleepTimerJob?.cancel()
        radioFetchJob?.cancel()
        statsTickJob?.cancel()
        serviceScope.cancel()
        runCatching {
            if (currentAudioSessionId != C.AUDIO_SESSION_ID_UNSET && currentAudioSessionId != 0) {
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
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

    private fun initOrUpdateEqualizer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        if (currentAudioSessionId == audioSessionId && equalizer != null) {
            applyEqualizerSettings(container.settingsStore.settings.value)
            return
        }
        currentAudioSessionId = audioSessionId

        // Open audio effect control session broadcast so system audio effects HAL attaches to this session
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

        // Standard application priority is 0 (priority > 0 is reserved for system apps in Android AudioEffect)
        // Fall back to global audio session (0) if session-specific attachment fails on OEM HAL
        equalizer = runCatching { Equalizer(0, audioSessionId).apply { enabled = true } }
            .recoverCatching { Equalizer(0, 0).apply { enabled = true } }
            .getOrNull()
        bassBoost = runCatching { BassBoost(0, audioSessionId) }
            .recoverCatching { BassBoost(0, 0) }
            .getOrNull()
        virtualizer = runCatching { Virtualizer(0, audioSessionId) }
            .recoverCatching { Virtualizer(0, 0) }
            .getOrNull()

        applyEqualizerSettings(container.settingsStore.settings.value)
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
        if (equalizer == null) {
            val curId = exoPlayer.audioSessionId
            if (curId != C.AUDIO_SESSION_ID_UNSET && curId != 0) {
                initOrUpdateEqualizer(curId)
            }
        }
        val eq = equalizer ?: return

        // 1. Multi-band Equalizer & hardware presets (isolated error boundary)
        runCatching {
            eq.enabled = settings.eqEnabled
            if (settings.eqEnabled) {
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
        }

        // 2. Bass Boost effect (isolated error boundary)
        runCatching {
            if (!settings.eqEnabled) {
                bassBoost?.enabled = false
            } else {
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
        }

        // 3. Surround Virtualizer effect (isolated error boundary)
        runCatching {
            if (!settings.eqEnabled) {
                virtualizer?.enabled = false
            } else {
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
        if (exoPlayer.repeatMode != Player.REPEAT_MODE_OFF && !forceJumpToNext) return
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
                    MediaItemMapper.fromMediaItem(exoPlayer.getMediaItemAt(i))?.let { add(it) }
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

            // Check repeat mode again in case user enabled loop while fetch was in flight
            if (exoPlayer.repeatMode != Player.REPEAT_MODE_OFF && !forceJumpToNext) return@launch

            if (fresh.isNotEmpty()) {
                val quality = container.settingsStore.settings.value.audioQuality
                val insertStart = exoPlayer.mediaItemCount
                exoPlayer.addMediaItems(fresh.map { MediaItemMapper.toMediaItem(it, quality, isAutoplay = true) })
                if (forceJumpToNext) {
                    exoPlayer.seekTo(insertStart, 0L)
                    exoPlayer.play()
                }
            } else if (forceJumpToNext && exoPlayer.mediaItemCount > 0) {
                exoPlayer.seekTo(0, 0L)
                exoPlayer.play()
            }
        }
    }

    private fun trimUpcomingAutoplayTracks() {
        val total = exoPlayer.mediaItemCount
        val cur = exoPlayer.currentMediaItemIndex
        if (cur < 0 || total <= cur + 1) return

        var firstAutoplayIndex = -1
        for (i in (cur + 1) until total) {
            if (MediaItemMapper.isAutoplay(exoPlayer.getMediaItemAt(i))) {
                firstAutoplayIndex = i
                break
            }
        }
        if (firstAutoplayIndex != -1) {
            exoPlayer.removeMediaItems(firstAutoplayIndex, total)
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
                    lastSongPositionMs = exoPlayer.currentPosition
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
                                positionMs = exoPlayer.currentPosition,
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
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            initOrUpdateEqualizer(audioSessionId)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            if (repeatMode != Player.REPEAT_MODE_OFF) {
                radioFetchJob?.cancel()
                trimUpcomingAutoplayTracks()
            }
        }

        private var errorRetryCount = 0

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                errorRetryCount = 0
                val sessionId = exoPlayer.audioSessionId
                if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0 && (equalizer == null || currentAudioSessionId != sessionId)) {
                    initOrUpdateEqualizer(sessionId)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                lastSongPositionMs = 0L
                refreshCustomLayout()
                return
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
                    container.apiClient.sendHeartbeat(current, exoPlayer.currentPosition, isPlaying)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isNetworkOrIo = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.cause is java.io.IOException

            if (isNetworkOrIo && errorRetryCount < 3) {
                errorRetryCount++
                val resumePos = lastSongPositionMs.coerceAtLeast(0L)
                serviceScope.launch {
                    delay(600L * errorRetryCount)
                    if (exoPlayer.currentMediaItem != null) {
                        exoPlayer.seekTo(resumePos)
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
                return
            }
            errorRetryCount = 0

            // If a single track in a multi-item queue fails permanently, skip to next to keep music going
            if (exoPlayer.mediaItemCount > 1 && exoPlayer.hasNextMediaItem()) {
                container.emitToast("Skipping unplayable track")
                exoPlayer.seekToNextMediaItem()
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
        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            return if (mediaItemCount > 0) {
                base.buildUpon()
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .build()
            } else {
                base
            }
        }

        override fun isCommandAvailable(command: Int): Boolean {
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
            if (repeatMode == REPEAT_MODE_OFF && container.settingsStore.settings.value.autoplayRadio) return true
            return super.hasNextMediaItem()
        }

        override fun hasPreviousMediaItem(): Boolean {
            if (currentPosition > 3000L) return true
            return super.hasPreviousMediaItem()
        }

        override fun seekToNext() {
            if (super.hasNextMediaItem()) {
                super.seekToNextMediaItem()
            } else if (repeatMode == REPEAT_MODE_ALL && mediaItemCount > 0) {
                seekTo(0, 0L)
            } else if (repeatMode == REPEAT_MODE_OFF && container.settingsStore.settings.value.autoplayRadio) {
                ensureAutoplayRadioIfNeeded(forceJumpToNext = true)
            }
        }

        override fun seekToNextMediaItem() {
            seekToNext()
        }

        override fun seekToPrevious() {
            if (currentPosition > 3000L) {
                seekTo(0L)
            } else if (super.hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
            } else {
                seekTo(0L)
            }
        }

        override fun seekToPreviousMediaItem() {
            seekToPrevious()
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
