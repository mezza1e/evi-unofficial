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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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
    private var broadcastAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var radioFetchJob: Job? = null
    private var trimAutoplayJob: Job? = null
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

        val mediaSourceFactory = EviMediaSourceSupport.buildMediaSourceFactory(
            context = this,
            streamingHttpClient = container.streamingHttpClient,
            streamCache = container.streamCache
        )

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
                /* backBufferDurationMs = */ 120_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
            .setEnableAudioFloatOutput(false)

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

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
        trimAutoplayJob?.cancel()
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

    private fun closeBroadcastAudioSession() {
        if (broadcastAudioSessionId != C.AUDIO_SESSION_ID_UNSET && broadcastAudioSessionId > 0) {
            runCatching {
                val closeIntent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, broadcastAudioSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                }
                sendBroadcast(closeIntent)
            }
        }
        broadcastAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun releaseEffectInstances() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.enabled = false }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.enabled = false }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
    }

    private fun releaseAudioEffects() {
        closeBroadcastAudioSession()
        releaseEffectInstances()
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

    private fun attachAudioSession(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
        if (currentAudioSessionId != audioSessionId) {
            closeBroadcastAudioSession()
            releaseEffectInstances()
            currentAudioSessionId = audioSessionId
        }
        if (broadcastAudioSessionId != audioSessionId) {
            runCatching {
                val openIntent = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                }
                sendBroadcast(openIntent)
            }
            broadcastAudioSessionId = audioSessionId
        }
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
        val sessionId = currentAudioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET && it > 0 }
            ?: exoPlayer.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET && it > 0 }
            ?: return

        attachAudioSession(sessionId)

        // 1. Equalizer: allocate ONLY when non-flat & enabled so unused DSP chains never degrade audio
        val wantEq = settings.eqEnabled && settings.eqPreset != EqPreset.FLAT
        if (wantEq) {
            runCatching {
                val eq = equalizer ?: Equalizer(0, sessionId).also { equalizer = it }
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
        } else {
            runCatching { equalizer?.enabled = false }
            runCatching { equalizer?.release() }
            equalizer = null
        }

        // 2. Bass Boost: allocate ONLY when gain > 5%
        val presetBassBoost = if (!settings.eqEnabled) 0f else when (settings.eqPreset) {
            EqPreset.BASS_BOOST -> 0.85f
            EqPreset.HIP_HOP -> 0.60f
            EqPreset.ELECTRONIC, EqPreset.DANCE -> 0.55f
            EqPreset.ROCK -> 0.35f
            else -> 0f
        }
        val effectiveBassBoost = if (settings.eqEnabled) {
            maxOf(settings.bassBoostStrength, presetBassBoost).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (effectiveBassBoost > 0.05f) {
            runCatching {
                val bb = bassBoost ?: BassBoost(0, sessionId).also { bassBoost = it }
                if (bb.strengthSupported) {
                    val strength = (effectiveBassBoost * 1000).toInt().toShort()
                    bb.setStrength(strength)
                    bb.enabled = true
                }
            }
        } else {
            runCatching { bassBoost?.enabled = false }
            runCatching { bassBoost?.release() }
            bassBoost = null
        }

        // 3. Surround Virtualizer: allocate ONLY when gain > 5%
        val presetVirtualizer = if (!settings.eqEnabled) 0f else when (settings.eqPreset) {
            EqPreset.ELECTRONIC, EqPreset.DANCE -> 0.60f
            EqPreset.VOCAL, EqPreset.ACOUSTIC -> 0.40f
            EqPreset.ROCK -> 0.30f
            else -> 0f
        }
        val effectiveVirtualizer = if (settings.eqEnabled) {
            maxOf(settings.virtualizerStrength, presetVirtualizer).coerceIn(0f, 1f)
        } else {
            0f
        }
        if (effectiveVirtualizer > 0.05f) {
            runCatching {
                val vz = virtualizer ?: Virtualizer(0, sessionId).also { virtualizer = it }
                if (vz.strengthSupported) {
                    val strength = (effectiveVirtualizer * 1000).toInt().toShort()
                    vz.setStrength(strength)
                    vz.enabled = true
                }
            }
        } else {
            runCatching { virtualizer?.enabled = false }
            runCatching { virtualizer?.release() }
            virtualizer = null
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

            if (exoPlayer.repeatMode != Player.REPEAT_MODE_OFF && !forceJumpToNext) return@launch

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
        runCatching {
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

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) return
            if (audioSessionId != currentAudioSessionId) {
                attachAudioSession(audioSessionId)
                applyEqualizerSettings(container.settingsStore.settings.value)
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            trimAutoplayJob?.cancel()
            trimAutoplayJob = null
            if (repeatMode != Player.REPEAT_MODE_OFF) {
                radioFetchJob?.cancel()
            }
            if (repeatMode == Player.REPEAT_MODE_ALL) {
                trimAutoplayJob = serviceScope.launch {
                    delay(150L)
                    if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
                        trimUpcomingAutoplayTracks()
                    }
                }
            } else if (repeatMode == Player.REPEAT_MODE_OFF) {
                ensureAutoplayRadioIfNeeded(forceJumpToNext = false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                errorRetryCount = 0
                errorRetryJob?.cancel()
                errorRetryJob = null
                val sessionId = exoPlayer.audioSessionId
                if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0 &&
                    (broadcastAudioSessionId != sessionId || currentAudioSessionId != sessionId)
                ) {
                    attachAudioSession(sessionId)
                    applyEqualizerSettings(container.settingsStore.settings.value)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // If an auto-transition happened far before a remote track's known duration (e.g. a
            // prematurely truncated stream around 10s), evict the cached spans so repeat/replay
            // fetches a clean stream instead of looping a truncated cache entry.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                val oldItem = oldPosition.mediaItem
                val cacheKey = oldItem?.localConfiguration?.customCacheKey
                val isRemote = oldItem?.localConfiguration?.uri?.scheme != "file"
                val expectedMs = oldItem?.mediaMetadata?.durationMs ?: 0L
                if (isRemote && !cacheKey.isNullOrBlank() && expectedMs >= 15_000L &&
                    oldPosition.positionMs in 1L until (expectedMs * 0.65).toLong()
                ) {
                    runCatching { container.streamCache.removeResource(cacheKey) }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            errorRetryJob?.cancel()
            errorRetryJob = null
            errorRetryCount = 0

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                lastSongFractionRef?.let { repeatedSong ->
                    serviceScope.launch { container.apiClient.recordListened(repeatedSong, 1.0) }
                }
                lastSongPositionMs = 0L
                refreshCustomLayout()
                return
            }

            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                exoPlayer.playWhenReady &&
                exoPlayer.playbackState == Player.STATE_IDLE
            ) {
                exoPlayer.prepare()
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

            // 2. Evict any corrupted/partial cache spans for the failing remote stream
            currentItem?.localConfiguration?.customCacheKey?.let { key ->
                runCatching { container.streamCache.removeResource(key) }
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
                val targetPos = exoPlayer.currentPosition.coerceAtLeast(lastSongPositionMs).coerceAtLeast(0L)
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

            // Pause cleanly in REPEAT_MODE_ONE if track permanently fails to prevent infinite loop
            if (exoPlayer.repeatMode == Player.REPEAT_MODE_ONE) {
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
            if (mediaItemCount <= 0) return false
            if (repeatMode == REPEAT_MODE_ALL || repeatMode == REPEAT_MODE_ONE) return true
            if (container.settingsStore.settings.value.autoplayRadio) return true
            return super.hasNextMediaItem()
        }

        override fun hasPreviousMediaItem(): Boolean {
            if (mediaItemCount <= 0) return false
            if (currentPosition > 3000L) return true
            if (repeatMode == REPEAT_MODE_ALL) return true
            return super.hasPreviousMediaItem()
        }

        override fun seekToNext() {
            if (mediaItemCount <= 0) return
            errorRetryJob?.cancel()
            errorRetryJob = null
            if (repeatMode == REPEAT_MODE_ONE && mediaItemCount > 1) {
                val nextIdx = wrappedPlayer.currentTimeline
                    .getNextWindowIndex(currentMediaItemIndex, REPEAT_MODE_ALL, shuffleModeEnabled)
                    .takeIf { it != C.INDEX_UNSET && it in 0 until mediaItemCount }
                    ?: ((currentMediaItemIndex + 1) % mediaItemCount)
                seekTo(nextIdx, 0L)
                play()
            } else if (super.hasNextMediaItem()) {
                super.seekToNextMediaItem()
            } else if (repeatMode == REPEAT_MODE_ALL) {
                val firstIdx = wrappedPlayer.currentTimeline
                    .getFirstWindowIndex(shuffleModeEnabled)
                    .takeIf { it in 0 until mediaItemCount } ?: 0
                seekTo(firstIdx, 0L)
                play()
            } else if (repeatMode == REPEAT_MODE_OFF && container.settingsStore.settings.value.autoplayRadio) {
                ensureAutoplayRadioIfNeeded(forceJumpToNext = true)
            }
        }

        override fun seekToNextMediaItem() {
            seekToNext()
        }

        override fun seekToPrevious() {
            if (mediaItemCount <= 0) return
            errorRetryJob?.cancel()
            errorRetryJob = null
            if (currentPosition > 3000L) {
                seekTo(0L)
            } else if (super.hasPreviousMediaItem()) {
                super.seekToPreviousMediaItem()
            } else if (repeatMode == REPEAT_MODE_ALL && mediaItemCount > 1) {
                val lastIdx = wrappedPlayer.currentTimeline
                    .getLastWindowIndex(shuffleModeEnabled)
                    .takeIf { it in 0 until mediaItemCount } ?: (mediaItemCount - 1)
                seekTo(lastIdx, 0L)
                play()
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
