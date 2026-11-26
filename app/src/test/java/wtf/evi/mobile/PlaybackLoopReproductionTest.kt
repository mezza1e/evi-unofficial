package wtf.evi.mobile

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.player.EviMediaSourceSupport
import wtf.evi.mobile.player.MediaItemMapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackLoopReproductionTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var streamCache: SimpleCache
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "repro_cache_${System.nanoTime()}").apply { mkdirs() }
        streamCache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(64L * 1024L * 1024L),
            StandaloneDatabaseProvider(context)
        )
        httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        runCatching { streamCache.release() }
        runCatching { server.shutdown() }
        cacheDir.deleteRecursively()
    }

    /**
     * Valid MPEG-1 Layer III CBR 320kbps 44.1kHz stereo frames (1044 bytes/frame, 1152 samples/frame).
     */
    private fun generateMp3Bytes(durationSeconds: Int): ByteArray {
        val frames = ((durationSeconds * 44100L) / 1152L).toInt().coerceAtLeast(1)
        val frameSize = 1044
        val frame = ByteArray(frameSize)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = 0xE0.toByte()
        frame[3] = 0x04.toByte()
        val out = ByteArrayOutputStream(frames * frameSize)
        repeat(frames) { out.write(frame) }
        return out.toByteArray()
    }

    private fun buildLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(20_000, 60_000, 1_500, 3_000)
        .setBackBuffer(120_000, true)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    private fun buildLegacyV103Player(clock: FakeClock): ExoPlayer {
        val okFactory = OkHttpDataSource.Factory(httpClient)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(okFactory)
            .setFlags(
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                    CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS
            )
        val dataSourceFactory = DefaultDataSource.Factory(context, cacheFactory)
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        return TestExoPlayerBuilder(context)
            .setClock(clock)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .setLoadControl(buildLoadControl())
            .build()
    }

    private fun buildFixedPlayer(clock: FakeClock): ExoPlayer {
        val mediaSourceFactory = EviMediaSourceSupport.buildMediaSourceFactory(
            context = context,
            streamingHttpClient = httpClient,
            streamCache = streamCache
        )
        return TestExoPlayerBuilder(context)
            .setClock(clock)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(buildLoadControl())
            .build()
    }

    private fun advanceUntil(clock: FakeClock, maxSteps: Int = 600, stepMs: Long = 50L, cond: () -> Boolean): Boolean {
        repeat(maxSteps) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            clock.advanceTime(stepMs)
            Thread.sleep(4)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return cond()
    }

    private fun makeMediaItem(id: String, url: String, durationSeconds: Double, cacheKey: String? = "${id}_320"): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle("Track $id")
            .setDurationMs((durationSeconds * 1000.0).toLong())
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse(url))
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(meta)
            .build()
    }

    @Test
    fun legacyBugRepro_vs_fixedBuild_truncatedSecondPeriodNeverClobbersDuration() {
        val fullMp3 = generateMp3Bytes(90)
        val truncated10sMp3 = fullMp3.copyOfRange(0, (fullMp3.size * 10) / 90)
        val reqCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = reqCount.incrementAndGet()
                val path = request.path.orEmpty()
                return if (path.contains("trunc") && n >= 2) {
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "audio/mpeg")
                        .setBody(Buffer().write(truncated10sMp3))
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "audio/mpeg")
                        .addHeader("Accept-Ranges", "bytes")
                        .setBody(Buffer().write(fullMp3))
                }
            }
        }
        server.start()

        // 1. Reproduce the legacy v1.0.3 failure mode: second period truncates to 10s -> duration drops to ~10s & cache unused
        val legacyClock = FakeClock(0L, true)
        val legacyPlayer = buildLegacyV103Player(legacyClock)
        val legacyUrl = server.url("/api/stream/legacy?quality=320&direct=1&trunc=1").toString()
        legacyPlayer.setMediaItems(listOf(makeMediaItem("legacy", legacyUrl, 90.0, "legacy_320")))
        legacyPlayer.prepare()
        legacyPlayer.play()
        assertTrue(advanceUntil(legacyClock) { legacyPlayer.playbackState == Player.STATE_READY })
        assertTrue(advanceUntil(legacyClock, 500, 100L) { legacyPlayer.currentPosition >= 12_000L })
        legacyPlayer.repeatMode = Player.REPEAT_MODE_ONE
        assertTrue("Legacy v1.0.3 should reproduce the ~10s duration drop",
            advanceUntil(legacyClock, 300, 100L) { legacyPlayer.duration in 5_000L..15_000L })
        assertEquals(0, streamCache.getCachedSpans("legacy_320").size)
        legacyPlayer.release()

        // 2. Verify fixed player keeps cache AND resists truncated duration clobbering
        reqCount.set(0)
        val fixedClock = FakeClock(0L, true)
        val fixedPlayer = buildFixedPlayer(fixedClock)
        val observedDurations = mutableListOf<Long>()
        fixedPlayer.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (fixedPlayer.duration > 0L) observedDurations += fixedPlayer.duration
            }
        })
        val fixedUrl = server.url("/api/stream/fixed?quality=320&direct=1&trunc=1").toString()
        fixedPlayer.setMediaItems(listOf(makeMediaItem("fixed", fixedUrl, 90.0, "fixed_320")))
        fixedPlayer.prepare()
        fixedPlayer.play()
        assertTrue(advanceUntil(fixedClock) { fixedPlayer.playbackState == Player.STATE_READY })
        assertTrue(advanceUntil(fixedClock, 500, 100L) { fixedPlayer.currentPosition >= 15_000L })
        fixedPlayer.repeatMode = Player.REPEAT_MODE_ONE
        advanceUntil(fixedClock, 150, 100L) { fixedPlayer.currentPosition >= 28_000L }

        assertTrue("Fixed build must never drop duration to ~10s (observed=$observedDurations)",
            observedDurations.all { it >= 85_000L })
        assertTrue("Fixed build duration must remain full length", fixedPlayer.duration >= 85_000L)
        assertTrue("Fixed build must write progressive stream to SimpleCache",
            streamCache.getCachedSpans("fixed_320").isNotEmpty())
        assertNull(fixedPlayer.playerError)
        fixedPlayer.release()
    }

    @Test
    fun repeatModeOne_chunkedMultiTrackQueue_toggleNearEnd_neverRestartsToZeroAndLoopsCleanly() {
        val song1Bytes = generateMp3Bytes(45)
        val song2Bytes = generateMp3Bytes(45)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = if (path.contains("c2")) song2Bytes else song1Bytes
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "audio/mpeg")
                    .setChunkedBody(Buffer().write(body), 8192)
            }
        }
        server.start()

        val clock = FakeClock(0L, true)
        val player = buildFixedPlayer(clock)
        val transitions = mutableListOf<String>()
        var repeatCount = 0
        player.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                transitions += "disc reason=$reason ${oldPosition.mediaItemIndex}:${oldPosition.positionMs}->${newPosition.mediaItemIndex}:${newPosition.positionMs} dur=${player.duration}"
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions += "trans id=${mediaItem?.mediaId},reason=$reason pos=${player.currentPosition} dur=${player.duration}"
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeatCount++
            }
        })

        val item1 = makeMediaItem("c1", server.url("/api/stream/c1?quality=320&direct=1").toString(), 45.0)
        val item2 = makeMediaItem("c2", server.url("/api/stream/c2?quality=320&direct=1").toString(), 45.0)
        player.setMediaItems(listOf(item1, item2))
        player.prepare()
        player.play()

        assertTrue(advanceUntil(clock) { player.playbackState == Player.STATE_READY })
        assertTrue("Chunked track must expose metadata duration instead of TIME_UNSET (got ${player.duration})", player.duration == 45_000L)

        // Advance right into the last <10s where ExoPlayer's reading period pre-advances to item2!
        assertTrue(advanceUntil(clock, 600, 100L) { player.currentPosition >= 37_500L })
        val posAtToggle = player.currentPosition
        player.repeatMode = Player.REPEAT_MODE_ONE

        // Pump just main looper so FakeClock does NOT race past EOF of the 45s track!
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "Toggling REPEAT_MODE_ONE near end of chunked track must not restart to 0 (before=$posAtToggle after=${player.currentPosition} events=$transitions)",
            player.currentPosition >= posAtToggle - 500L
        )
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue("Duration must never drop below 44s (got ${player.duration})", player.duration >= 44_000L)

        // Now let item1 reach end and verify it loops twice on item1 without touching item2
        assertTrue("Should complete 2 full REPEAT_MODE_ONE loops",
            advanceUntil(clock, 1200, 100L) { repeatCount >= 2 })
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue("Should never transition to c2 during REPEAT_MODE_ONE", transitions.none { it.startsWith("id=c2") })
        assertNull(player.playerError)
        player.release()
    }

    @Test
    fun repeatModeOne_with302Redirects_preservesStableUriCachesAndReusesCache() {
        val mp3Bytes = generateMp3Bytes(30)
        val cdnHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.startsWith("/api/stream/")) {
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", server.url("/cdn/ephemeral_tok_${System.nanoTime()}.mp3").toString())
                } else {
                    cdnHits.incrementAndGet()
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "audio/mpeg")
                        .setBody(Buffer().write(mp3Bytes))
                }
            }
        }
        server.start()

        val clock = FakeClock(0L, true)
        val player = buildFixedPlayer(clock)
        var repeatCount = 0
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeatCount++
            }
        })

        val item = makeMediaItem("r1", server.url("/api/stream/r1?quality=320&direct=1").toString(), 30.0, "r1_320")
        player.setMediaItems(listOf(item))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.play()

        assertTrue(advanceUntil(clock, 1200, 100L) { repeatCount >= 3 })
        assertTrue("Must cache redirected stream locally", streamCache.getCachedSpans("r1_320").isNotEmpty())
        val storedRedir = ContentMetadata.getRedirectedUri(streamCache.getContentMetadata("r1_320"))
        assertNull("StableUriDataSource must prevent ephemeral 302 redirect from sticking in cache", storedRedir)
        assertTrue("3 repeats should not require 4+ CDN downloads (cdnHits=${cdnHits.get()})", cdnHits.get() <= 2)
        assertNull(player.playerError)
        player.release()
    }

    @Test
    fun stress_mixedOfflineAndOnlineQueue_skipSeekCycleRepeat_noGlitchesOrErrors() {
        val onlineMp3 = generateMp3Bytes(35)
        val slightlyShorterMp3 = generateMp3Bytes(34)
        val offlineFile = File(context.cacheDir, "offline_stress_${System.nanoTime()}.mp3").apply {
            writeBytes(generateMp3Bytes(36))
        }
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val bytes = if (path.contains("short_meta")) slightlyShorterMp3 else onlineMp3
                    return if (path.contains("chunked")) {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "audio/mpeg")
                            .setChunkedBody(Buffer().write(bytes), 4096)
                    } else {
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Content-Type", "audio/mpeg")
                            .setBody(Buffer().write(bytes))
                    }
                }
            }
            server.start()

            val clock = FakeClock(0L, true)
            val player = buildFixedPlayer(clock)
            val songs = listOf(
                Song(id = "off1", title = "Offline", artist = "A", duration = 36.0, url = "/api/stream/off1"),
                Song(id = "on1", title = "Online Ranged", artist = "A", duration = 35.0, url = "/api/stream/on1"),
                Song(id = "on2", title = "Online Chunked", artist = "A", duration = 35.0, url = "/api/stream/on2"),
                Song(id = "on3", title = "Short Meta", artist = "A", duration = 35.0, url = "/api/stream/on3")
            )
            val queue = listOf(
                MediaItemMapper.toMediaItem(songs[0], AudioQuality.HIGH, localAudioFile = offlineFile),
                makeMediaItem("on1", server.url("/api/stream/on1?quality=320&direct=1").toString(), 35.0),
                makeMediaItem("on2", server.url("/api/stream/on2?quality=320&direct=1&chunked=1").toString(), 35.0),
                makeMediaItem("on3", server.url("/api/stream/on3?quality=320&direct=1&short_meta=1").toString(), 35.0)
            )

            player.setMediaItems(queue)
            player.prepare()
            player.play()
            assertTrue(advanceUntil(clock) { player.playbackState == Player.STATE_READY })

            // Play offline -> toggle REPEAT_MODE_ONE -> scrub -> let loop -> skipNext (while REPEAT_MODE_ONE!) -> verify track 1 -> cycle repeat
            assertTrue(advanceUntil(clock, 300, 100L) { player.currentPosition >= 6_000L })
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.seekTo(20_000L)
            assertTrue(advanceUntil(clock) { player.playbackState == Player.STATE_READY && player.currentPosition >= 19_000L })
            var repeats = 0
            val listener = object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeats++
                }
            }
            player.addListener(listener)
            assertTrue("Offline track should loop cleanly under REPEAT_MODE_ONE", advanceUntil(clock, 500, 100L) { repeats >= 1 })
            assertEquals(0, player.currentMediaItemIndex)

            // Skip next while REPEAT_MODE_ONE is active (simulating EviForwardingPlayer / PlaybackController skipNext)
            val nextIdx = (player.currentMediaItemIndex + 1) % player.mediaItemCount
            player.seekTo(nextIdx, 0L)
            player.play()
            assertTrue(advanceUntil(clock) { player.currentMediaItemIndex == 1 && player.playbackState == Player.STATE_READY })
            assertTrue("Track 1 duration intact", player.duration >= 34_000L)

            // Scrub forward, toggle repeat OFF -> ALL -> ONE rapidly across chunked track
            player.seekTo(12_000L)
            player.seekTo(2, 0L)
            assertTrue(advanceUntil(clock) { player.currentMediaItemIndex == 2 && player.playbackState == Player.STATE_READY })
            player.repeatMode = Player.REPEAT_MODE_OFF
            shadowOf(Looper.getMainLooper()).idle()
            player.repeatMode = Player.REPEAT_MODE_ALL
            shadowOf(Looper.getMainLooper()).idle()
            player.repeatMode = Player.REPEAT_MODE_ONE
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Must remain on chunked track 2 and expose full duration (got dur=${player.duration} idx=${player.currentMediaItemIndex})",
                player.currentMediaItemIndex == 2 && player.duration >= 34_000L)

            // Check slight duration difference (34s = 33,959ms actual vs 35s metadata) still loops cleanly without hang
            player.seekTo(3, 28_000L)
            assertTrue(advanceUntil(clock) { player.currentMediaItemIndex == 3 && player.playbackState == Player.STATE_READY })
            assertTrue("Track 3 actual duration should stay ~33.9s (got ${player.duration})", player.duration >= 33_900L)
            val beforeLoop = repeats
            assertTrue("Should transition repeat cleanly even when actual duration (33.96s) slightly differs from metadata (35s)",
                advanceUntil(clock, 400, 100L) { repeats > beforeLoop })
            assertEquals(3, player.currentMediaItemIndex)
            assertNull(player.playerError)
            player.release()
        } finally {
            offlineFile.delete()
        }
    }

    @Test
    fun fuzz_durationGuardAndControllerResolution_handles5000EdgeCases() {
        val rng = Random(20261126)
        repeat(5000) {
            val expectedSec = rng.nextInt(5, 600)
            val expectedUs = expectedSec * 1_000_000L
            val expectedMs = expectedSec * 1_000L
            val bogus10sUs = 10_000_000L
            val bogus10sMs = 10_000L

            if (expectedUs >= EviMediaSourceSupport.MIN_GUARD_THRESHOLD_US &&
                bogus10sUs < (expectedUs * EviMediaSourceSupport.MIN_VALID_DURATION_RATIO).toLong()
            ) {
                val guardedUs = EviMediaSourceSupport.computeSafeDurationUs(bogus10sUs, expectedUs, expectedUs)
                assertEquals(expectedUs, guardedUs)
                val resolvedMs = EviMediaSourceSupport.resolveEffectiveDurationMs(bogus10sMs, expectedMs, expectedMs)
                assertEquals(expectedMs, resolvedMs)
            }

            // Genuine short tracks (<15s) must keep their actual player duration
            val shortUs = 8_000_000L
            assertEquals(shortUs, EviMediaSourceSupport.computeSafeDurationUs(shortUs, 8_000_000L, C.TIME_UNSET))
            assertEquals(8_000L, EviMediaSourceSupport.resolveEffectiveDurationMs(8_000L, 8_000L, 0L))

            // TIME_UNSET falls back cleanly to expected/best duration
            assertEquals(expectedUs, EviMediaSourceSupport.computeSafeDurationUs(C.TIME_UNSET, expectedUs, C.TIME_UNSET))
            assertEquals(expectedMs, EviMediaSourceSupport.resolveEffectiveDurationMs(C.TIME_UNSET, expectedMs, 0L))
        }
    }
}
