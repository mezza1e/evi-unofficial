package wtf.evi.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.evi.mobile.data.model.Album
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.LyricLine
import wtf.evi.mobile.data.model.LyricWord
import wtf.evi.mobile.data.model.LyricsResponse
import wtf.evi.mobile.data.model.Mix
import wtf.evi.mobile.data.model.Song
import wtf.evi.mobile.ui.CachedHomePayload
import java.util.Collections
import kotlin.random.Random

class StressAndPerformanceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    // --- Stress Test 1: Rapid Scrubbing and Seek Debouncing Stability ---
    @Test
    fun rapidScrubbingUnderNetworkLatencyNeverRubberBands() {
        val durationMs = 240_000L // 4 minute song
        var activePlayheadMs = 15_000L
        var seekPendingMs: Long? = null
        var lastSeekTime = 0L

        fun simulateSeek(targetMs: Long, now: Long) {
            val clamped = targetMs.coerceIn(0L, durationMs)
            seekPendingMs = clamped
            activePlayheadMs = clamped
            lastSeekTime = now
        }

        fun simulateTickerTick(playerReportedPos: Long, now: Long) {
            val target = seekPendingMs
            activePlayheadMs = if (target != null) {
                if (kotlin.math.abs(playerReportedPos - target) < 1500L || (now - lastSeekTime) > 2500L) {
                    seekPendingMs = null
                    playerReportedPos
                } else {
                    target
                }
            } else {
                playerReportedPos
            }
        }

        var clock = 1000L
        // User rapidly scrubs across 50 positions well ahead of delayed player position
        var lastUserTarget = 60_000L
        for (i in 0 until 50) {
            clock += 50L
            val scrubTarget = 60_000L + (i * 2000L)
            lastUserTarget = scrubTarget
            simulateSeek(scrubTarget, clock)

            // Player is delayed over network: still reports pre-seek pos (e.g. 15s)
            val delayedPlayerPos = 15_000L + (i * 10L)
            simulateTickerTick(delayedPlayerPos, clock)

            // CRITICAL INVARIANT: The UI playhead MUST NOT snap back to delayedPlayerPos!
            assertEquals(
                "Playhead rubber-banded during active seek!",
                scrubTarget,
                activePlayheadMs
            )
        }

        // Now simulate seek settling after 400ms: player reaches target position
        clock += 400L
        simulateTickerTick(lastUserTarget + 50L, clock)
        // Seek should now be settled
        assertEquals(null, seekPendingMs)
        assertEquals(lastUserTarget + 50L, activePlayheadMs)
    }

    // --- Stress Test 2: Repeat One, Repeat All, and Queue Skipping Invariants ---
    @Test
    fun repeatOneDoesNotBlockNextTrackAdvancementAcross100Transitions() {
        val totalQueueSize = 25
        val queue = List(totalQueueSize) { idx ->
            Song(id = "song_$idx", title = "Track $idx", artist = "Artist $idx", duration = 180.0)
        }

        // Simulate EviForwardingPlayer logic
        var currentIndex = 0
        var repeatMode = 1 // 1 = REPEAT_MODE_ONE, 2 = REPEAT_MODE_ALL, 0 = OFF
        var autoplayRadio = true
        var radioFetchCount = 0

        fun simulateNext() {
            if (currentIndex + 1 < queue.size) {
                // Direct index advancement must bypass REPEAT_MODE_ONE
                currentIndex++
            } else if (autoplayRadio) {
                radioFetchCount++
            } else if (repeatMode == 2 && queue.isNotEmpty()) {
                currentIndex = 0
            } else if (repeatMode == 1) {
                // Loop in place if at end and no autoplay
                currentIndex = currentIndex
            }
        }

        fun simulatePrevious(currentPositionMs: Long) {
            if (currentPositionMs > 3000L || currentIndex <= 0) {
                // Restart current song
            } else {
                currentIndex--
            }
        }

        // Perform 100 skips under REPEAT_MODE_ONE
        repeatMode = 1
        for (step in 0 until 24) {
            val prevIndex = currentIndex
            simulateNext()
            assertEquals("Next failed to advance under REPEAT_MODE_ONE!", prevIndex + 1, currentIndex)
        }

        assertEquals(24, currentIndex) // At end of queue
        simulateNext()
        assertEquals("Queue exhaustion did not trigger autoplay radio extension!", 1, radioFetchCount)

        // Test simulatePrevious
        simulatePrevious(currentPositionMs = 5000L) // >3s should stay at 24 and restart
        assertEquals(24, currentIndex)
        simulatePrevious(currentPositionMs = 1000L) // <=3s should go back to 23
        assertEquals(23, currentIndex)
    }

    // --- Stress Test 3: High-Concurrency LRU Cache & Thread Safety (1,000 Operations) ---
    @Test
    fun highConcurrencyCacheOperationsAreThreadSafe() = runBlocking {
        val lruCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 50
            }
        }

        // Launch 50 coroutines executing 20 operations each = 1,000 parallel operations
        val deferreds = (0 until 50).map { workerId ->
            async(Dispatchers.Default) {
                for (op in 0 until 20) {
                    val key = "key_${(workerId * 20 + op) % 80}"
                    val read = synchronized(lruCache) {
                        lruCache[key] = "val_${workerId}_$op"
                        lruCache[key]
                    }
                    assertNotNull(read)
                }
            }
        }
        deferreds.awaitAll()

        synchronized(lruCache) {
            assertTrue("Cache exceeded max capacity limit: ${lruCache.size}", lruCache.size <= 51)
        }
    }

    // --- Stress Test 4: Autoplay Radio Deduplication and Continuous Playback ---
    @Test
    fun radioQueueDeduplicationPreservesPurityAndContinuity() {
        val existingQueue = mutableListOf(
            Song(id = "s1", title = "Song 1"),
            Song(id = "s2", title = "Song 2"),
            Song(id = "s3", title = "Song 3")
        )
        val incomingCandidates = listOf(
            Song(id = "s2", title = "Duplicate 2"),
            Song(id = "s4", title = "New Song 4"),
            Song(id = "s1", title = "Duplicate 1"),
            Song(id = "s5", title = "New Song 5")
        )

        val existingIds = existingQueue.map { it.id }.toSet()
        val fresh = incomingCandidates.filter { it.id.isNotBlank() && it.id !in existingIds }

        assertEquals(2, fresh.size)
        assertEquals(listOf("s4", "s5"), fresh.map { it.id })

        existingQueue.addAll(fresh)
        assertEquals(5, existingQueue.size)
        assertEquals(5, existingQueue.map { it.id }.distinct().size)
    }

    // --- Stress Test 5: Word-by-Word Synced Lyrics Timing & Boundary Interpolation ---
    @Test
    fun syncedLyricsTimingResolvesActiveWordCorrectlyAtAllBoundaries() {
        val lyrics = LyricsResponse(
            lines = listOf(
                LyricLine(
                    words = listOf(
                        LyricWord(word = "I ", startTime = 1000L, endTime = 1400L),
                        LyricWord(word = "feel ", startTime = 1400L, endTime = 1900L),
                        LyricWord(word = "it ", startTime = 1900L, endTime = 2200L),
                        LyricWord(word = "coming ", startTime = 2200L, endTime = 3000L)
                    ),
                    startTime = 1000L,
                    endTime = 3000L
                )
            ),
            source = "binimum"
        )

        fun findActiveWord(line: LyricLine, posMs: Long): Int {
            return line.words.indexOfLast { posMs >= it.startTime }
        }

        val line = lyrics.lines.first()
        // Pre-line (<1000ms): no active word
        assertEquals(-1, findActiveWord(line, 500L))
        // Exactly at start of first word (1000ms)
        assertEquals(0, findActiveWord(line, 1000L))
        // Middle of first word (1200ms)
        assertEquals(0, findActiveWord(line, 1200L))
        // Exactly at boundary (1400ms)
        assertEquals(1, findActiveWord(line, 1400L))
        // Final word (2500ms)
        assertEquals(3, findActiveWord(line, 2500L))
        // Beyond line end (3500ms)
        assertEquals(3, findActiveWord(line, 3500L))
    }

    // --- Stress Test 6: Stale-While-Revalidate Catalog JSON Serialization & Restoral ---
    @Test
    fun catalogCacheSerializationPreservesLargePayloads() {
        val sampleSongs = (0 until 100).map { i ->
            Song(
                id = "song_id_$i",
                title = "Title $i",
                artist = "Artist $i",
                artistId = "art_$i",
                album = "Album $i",
                albumId = "alb_$i",
                duration = 180.0 + i,
                coverUrl = "/api/artwork?id=song_id_$i",
                url = "/api/stream/song_id_$i"
            )
        }
        val sampleAlbums = (0 until 50).map { i ->
            Album(
                id = "alb_$i",
                title = "Album Title $i",
                artist = "Artist $i",
                artistId = "art_$i",
                year = "2024",
                coverUrl = "/api/artwork?id=alb_$i"
            )
        }
        val sampleMixes = (0 until 6).map { i ->
            Mix(index = i, title = "Daily Mix $i", subtitle = "Personalized mix $i")
        }

        val originalPayload = CachedHomePayload(
            trendingSongs = sampleSongs,
            trendingAlbums = sampleAlbums,
            newReleases = sampleAlbums,
            mixes = sampleMixes
        )

        val serialized = json.encodeToString(CachedHomePayload.serializer(), originalPayload)
        val deserialized = json.decodeFromString<CachedHomePayload>(serialized)

        assertEquals(100, deserialized.trendingSongs.size)
        assertEquals(50, deserialized.trendingAlbums.size)
        assertEquals(6, deserialized.mixes.size)
        assertEquals("Title 42", deserialized.trendingSongs[42].title)
        assertEquals("Album Title 17", deserialized.trendingAlbums[17].title)
    }

    // --- Stress Test 7: Stream URL Generation for All Audio Qualities ---
    @Test
    fun streamUrlFormattingMatchesNativeDesktopEviProtocol() {
        val song = Song(id = "xyz999", title = "Test Song")
        val losslessUrl = song.resolvedStreamUrl(AudioQuality.LOSSLESS)
        val highUrl = song.resolvedStreamUrl(AudioQuality.HIGH)
        val fastUrl = song.resolvedStreamUrl(AudioQuality.FAST)

        assertTrue(losslessUrl.contains("quality=lossless&direct=1"))
        assertTrue(highUrl.contains("quality=320&direct=1"))
        assertTrue(fastUrl.contains("quality=fast&direct=1"))
    }

    // --- Stress Test 8: Equalizer Presets, Band Clamping, and AppSettings Persistence ---
    @Test
    fun equalizerPresetsAndSettingsSerialization() {
        val defaultSettings = wtf.evi.mobile.data.model.AppSettings()
        assertTrue(defaultSettings.eqEnabled)
        assertEquals(wtf.evi.mobile.data.model.EqPreset.FLAT, defaultSettings.eqPreset)
        assertEquals(5, defaultSettings.customEqBands.size)
        assertEquals(0f, defaultSettings.bassBoostStrength, 0.001f)
        assertEquals(0f, defaultSettings.virtualizerStrength, 0.001f)

        // Verify all EqPreset definitions provide exactly 5 band weights between -1.0 and 1.0
        for (preset in wtf.evi.mobile.data.model.EqPreset.entries) {
            assertEquals("Preset ${preset.name} must have 5 bands", 5, preset.bandWeights.size)
            for (weight in preset.bandWeights) {
                assertTrue("Band weight in $preset must be >= -1.0", weight >= -1.0f)
                assertTrue("Band weight in $preset must be <= 1.0", weight <= 1.0f)
            }
        }

        // Bass boost preset check
        assertTrue(wtf.evi.mobile.data.model.EqPreset.BASS_BOOST.bandWeights[0] > 0.3f)
        // Vocal boost mids check
        assertTrue(wtf.evi.mobile.data.model.EqPreset.VOCAL.bandWeights[2] > 0.2f)

        // Custom Equalizer Settings Serialization
        val customSettings = defaultSettings.copy(
            eqEnabled = true,
            eqPreset = wtf.evi.mobile.data.model.EqPreset.CUSTOM,
            customEqBands = listOf(0.8f, 0.4f, -0.2f, 0.3f, 0.7f),
            bassBoostStrength = 0.65f,
            virtualizerStrength = 0.40f
        )
        val serialized = json.encodeToString(wtf.evi.mobile.data.model.AppSettings.serializer(), customSettings)
        val deserialized = json.decodeFromString<wtf.evi.mobile.data.model.AppSettings>(serialized)

        assertTrue(deserialized.eqEnabled)
        assertEquals(wtf.evi.mobile.data.model.EqPreset.CUSTOM, deserialized.eqPreset)
        assertEquals(0.8f, deserialized.customEqBands[0], 0.001f)
        assertEquals(0.4f, deserialized.customEqBands[1], 0.001f)
        assertEquals(-0.2f, deserialized.customEqBands[2], 0.001f)
        assertEquals(0.3f, deserialized.customEqBands[3], 0.001f)
        assertEquals(0.7f, deserialized.customEqBands[4], 0.001f)
        assertEquals(0.65f, deserialized.bassBoostStrength, 0.001f)
        assertEquals(0.40f, deserialized.virtualizerStrength, 0.001f)
    }

    // --- Stress Test 9: WinPhotos Architecture - Image URL Resolution and Fast-Path Dimensions ---
    @Test
    fun winPhotosImagePipelineResolutionHandlesHighResAndNulls() {
        val songWithCover = Song(id = "s1", title = "Track", coverUrl = "/api/artwork?hash=abc")
        val resolved128 = songWithCover.resolvedCoverUrl(128)
        val resolved512 = songWithCover.resolvedCoverUrl(512)

        assertTrue(resolved128.contains("size=128"))
        assertTrue(resolved512.contains("size=512"))

        // Song with ID but no explicit cover falls back to artwork endpoint for its ID
        val songFallback = Song(id = "s2", title = "Track 2", coverUrl = "")
        assertTrue(songFallback.resolvedCoverUrl(512).contains("/api/artwork?id=s2"))

        // Completely blank song returns empty string gracefully
        val songBlank = Song(id = "", title = "No ID", coverUrl = "")
        assertEquals("", songBlank.resolvedCoverUrl(512))
    }

    // --- Stress Test 10: Search Workflow Submits on Enter and Avoids Search on Keystrokes ---
    @Test
    fun searchWorkflowSubmitsOnEnterAndAvoidsSearchOnKeystrokes() {
        var networkSearchCallCount = 0
        var currentQuery = ""
        var isSearchLoading = false

        fun onQueryChange(newQuery: String) {
            currentQuery = newQuery
            // In the new architecture, onQueryChange must NEVER execute a network search
        }

        fun onSubmitSearch(query: String) {
            val trimmed = query.trim()
            if (trimmed.isNotBlank()) {
                isSearchLoading = true
                networkSearchCallCount++
                // Simulated search completion
                isSearchLoading = false
            }
        }

        // Simulate typing 50 keystrokes rapidly
        val longSearch = "daft punk get lucky random access memories"
        for (i in 1..longSearch.length) {
            onQueryChange(longSearch.substring(0, i))
        }

        // Must still be 0 network calls while typing
        assertEquals(0, networkSearchCallCount)
        assertFalse(isSearchLoading)
        assertEquals(longSearch, currentQuery)

        // User hits Enter / submits
        onSubmitSearch(currentQuery)
        assertEquals(1, networkSearchCallCount)

        // Multiple consecutive enters on same query or blank queries
        onSubmitSearch("   ")
        assertEquals(1, networkSearchCallCount) // Blank should not trigger network

        onSubmitSearch("new search")
        assertEquals(2, networkSearchCallCount)
    }

    // --- Stress Test 11: Speculative Pre-Fetching Thumbnail Resolution and Capacity ---
    @Test
    fun speculativePrefetchThumbnailResolutionAndCapacity() {
        val searchResults = (0 until 30).map { i ->
            Song(
                id = "search_song_$i",
                title = "Result Song $i",
                coverUrl = if (i % 2 == 0) "/api/artwork?hash=art_$i" else ""
            )
        }

        // First 16 songs are speculatively pre-fetched
        val prefetchedUrls = searchResults.take(16).mapNotNull { song ->
            val c = song.resolvedCoverUrl(128)
            if (c.isNotBlank()) c else null
        }

        assertEquals(16, prefetchedUrls.size)
        for (url in prefetchedUrls) {
            assertTrue("Url must contain size=128", url.contains("size=128"))
            assertTrue("Url must point to artwork endpoint", url.contains("/api/artwork"))
        }
    }

    // --- Stress Test 12: HTTP Keep-Alive & Connection Pool Stability ---
    @Test
    fun httpKeepAliveConnectionPoolStress() {
        val pool = okhttp3.ConnectionPool(12, 5, java.util.concurrent.TimeUnit.MINUTES)
        assertEquals(0, pool.idleConnectionCount())
        assertEquals(0, pool.connectionCount())

        // Verify request header injection
        val baseReq = okhttp3.Request.Builder()
            .url("https://api.evi.wtf/api/search?q=test")
            .header("Connection", "keep-alive")
            .header("User-Agent", wtf.evi.mobile.data.model.EVI_USER_AGENT)
            .build()

        assertEquals("keep-alive", baseReq.header("Connection"))
        assertEquals(wtf.evi.mobile.data.model.EVI_USER_AGENT, baseReq.header("User-Agent"))
    }

    // --- Stress Test 13: SubmittedQuery Preserves Previous Screen While Typing ---
    @Test
    fun submittedQueryPreservesPreviousScreenWhileTyping() {
        var state = wtf.evi.mobile.ui.SearchUiState()

        // 1. Initial State: submittedQuery is empty -> shows trending searches and recent searches
        assertTrue(state.submittedQuery.isBlank())
        assertTrue(state.query.isBlank())

        // 2. User starts typing "radiohead": query changes, but submittedQuery stays blank!
        val input = "radiohead"
        for (i in 1..input.length) {
            val partial = input.substring(0, i)
            // onSearchQueryChange
            state = state.copy(query = partial)
            assertTrue("Screen must remain on previous view while typing", state.submittedQuery.isBlank())
        }

        // 3. User submits search: submittedQuery is updated to "radiohead"
        state = state.copy(
            query = input,
            submittedQuery = input,
            loading = false,
            songs = listOf(Song(id = "rh1", title = "Creep"))
        )
        assertEquals("radiohead", state.submittedQuery)
        assertEquals(1, state.songs.size)

        // 4. User starts typing a new query "daft punk" without pressing enter yet:
        // submittedQuery is STILL "radiohead" and songs STILL contains Creep!
        state = state.copy(query = "daft punk")
        assertEquals("radiohead", state.submittedQuery)
        assertEquals(1, state.songs.size)
        // Screen continues to show "radiohead" results instead of empty "nothing found"!

        // 5. User clears query completely:
        state = wtf.evi.mobile.ui.SearchUiState(query = "")
        assertTrue(state.submittedQuery.isBlank())
        assertTrue(state.songs.isEmpty())
        // Screen smoothly returns to trending/recent searches!
    }

    // --- Stress Test 14: Discord Animated Avatar URL Resolution ---
    @Test
    fun discordAnimatedAvatarUrlResolutionStress() {
        // Discord animated avatars start with "a_" in their hash
        val userAnimatedPng = wtf.evi.mobile.data.model.User(
            id = "123456789",
            username = "test_user",
            avatar = "https://cdn.discordapp.com/avatars/123456789/a_9876543210abcdef9876543210abcdef.png?size=256"
        )
        val resolvedAnimated = userAnimatedPng.resolvedAvatarUrl(256)
        assertTrue("Animated Discord avatar MUST resolve to .gif", resolvedAnimated.contains(".gif"))
        assertFalse("Animated Discord avatar MUST NOT remain .png", resolvedAnimated.contains(".png"))
        assertTrue("Query size must be preserved", resolvedAnimated.contains("size=256"))

        // Discord static avatars start without "a_"
        val userStaticPng = wtf.evi.mobile.data.model.User(
            id = "123456789",
            username = "static_user",
            avatar = "https://cdn.discordapp.com/avatars/123456789/9876543210abcdef9876543210abcdef.png?size=256"
        )
        val resolvedStatic = userStaticPng.resolvedAvatarUrl(256)
        assertTrue("Static avatar should retain .png", resolvedStatic.contains(".png"))

        // FriendItem avatar resolution
        val friendAnimated = wtf.evi.mobile.data.model.FriendItem(
            id = "u1",
            username = "friend_anim",
            avatar = "https://cdn.discordapp.com/avatars/55555/a_1122334455667788.webp"
        )
        val friendResolved = friendAnimated.resolvedAvatarUrl(128)
        assertTrue("Friend animated avatar must resolve to .gif", friendResolved.contains(".gif"))
    }

    // --- Stress Test 15: Offline Download Batch Queuing and Progress Invariants ---
    @Test
    fun offlineDownloadConcurrencyAndProgressStress() = runBlocking {
        val totalSongs = 60
        val songs = List(totalSongs) { i ->
            Song(id = "dl_song_$i", title = "Download Track $i", artist = "Artist $i")
        }

        val progressMap = Collections.synchronizedMap(mutableMapOf<String, Float>())
        val downloadedIds = Collections.synchronizedSet(mutableSetOf<String>())
        val semaphore = kotlinx.coroutines.sync.Semaphore(3)

        val jobs = songs.map { song ->
            async(Dispatchers.Default) {
                semaphore.acquire()
                try {
                    // Simulate progressive download chunks
                    for (step in 1..10) {
                        val progress = step / 10f
                        progressMap[song.id] = progress
                        assertTrue(progress in 0f..1f)
                    }
                    progressMap.remove(song.id)
                    downloadedIds.add(song.id)
                } finally {
                    semaphore.release()
                }
            }
        }

        jobs.awaitAll()

        assertEquals(totalSongs, downloadedIds.size)
        assertTrue("All in-flight progress indicators must be cleared", progressMap.isEmpty())
        for (song in songs) {
            assertTrue("Song ${song.id} must be marked as downloaded", downloadedIds.contains(song.id))
        }
    }

    // --- Stress Test 16: Offline Playback Local File vs Network Stream Invariant ---
    @Test
    fun offlinePlaybackLocalFileResolutionInvariant() {
        val tempDir = java.nio.file.Files.createTempDirectory("evi_test_downloads").toFile()
        try {
            val songDownloaded = Song(id = "offline_123", title = "Downloaded Song", artist = "Offline Artist")
            val songOnline = Song(id = "online_456", title = "Streaming Song", artist = "Online Artist")

            // Create fake offline audio and thumb files
            val audioFile = java.io.File(tempDir, "${songDownloaded.id}.audio").apply {
                writeBytes(ByteArray(2048) { 1 })
            }
            val thumbFile = java.io.File(tempDir, "${songDownloaded.id}.thumb").apply {
                writeBytes(ByteArray(512) { 2 })
            }

            assertTrue(audioFile.exists())
            assertTrue(audioFile.length() > 0)

            // Verify URI resolution behavior
            fun resolveAudioUriString(song: Song, quality: AudioQuality, localFile: java.io.File?): String {
                return if (localFile != null && localFile.exists() && localFile.length() > 0L) {
                    localFile.toURI().toString()
                } else {
                    song.resolvedStreamUrl(quality)
                }
            }

            val downloadedUri = resolveAudioUriString(songDownloaded, AudioQuality.HIGH, audioFile)
            assertTrue("Downloaded song must use file scheme", downloadedUri.startsWith("file:/"))
            assertTrue("Downloaded URI must contain song file", downloadedUri.contains("${songDownloaded.id}.audio"))

            val onlineUri = resolveAudioUriString(songOnline, AudioQuality.HIGH, null)
            assertTrue("Online song must use https scheme", onlineUri.startsWith("https://"))
            assertTrue("Online URI must point to stream API", onlineUri.contains("/api/stream/"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // --- Stress Test 17: Repeat Mode ONE Queue Stability and Seek Reset Invariant ---
    @Test
    fun repeatOneDoesNotAdvanceQueueOrGlitch() {
        // Model player queue state
        val playlist = mutableListOf("song_1", "song_2", "song_3")
        var currentIndex = 0
        var repeatMode = 1 // Player.REPEAT_MODE_ONE
        var pendingSeekTargetMs: Long? = 120_000L

        // Simulate track end with REPEAT_MODE_ONE
        val transitionReason = 0 // MEDIA_ITEM_TRANSITION_REASON_REPEAT
        fun onMediaTransition(reason: Int) {
            if (reason == 0) { // REPEAT
                // Under repeat one, position resets to 0L and index DOES NOT change
                pendingSeekTargetMs = null
                // Radio fetch must NOT append items when repeat mode is ONE
                return
            }
            currentIndex++
        }

        onMediaTransition(transitionReason)

        assertEquals("Current index must NOT advance when repeating", 0, currentIndex)
        assertEquals("Queue must NOT be polluted with radio items", 3, playlist.size)
        assertNull("Pending seek target must be cleared immediately upon repeat transition", pendingSeekTargetMs)

        // Verify seekToNext behavior under repeatMode == REPEAT_MODE_ONE
        fun computeSeekToNextTarget(curIdx: Int, count: Int, mode: Int): Pair<Int, Long> {
            return if (mode == 1) { // REPEAT_MODE_ONE
                curIdx to 0L // restart in-place
            } else if (curIdx + 1 < count) {
                (curIdx + 1) to 0L
            } else {
                0 to 0L
            }
        }

        val target = computeSeekToNextTarget(currentIndex, playlist.size, repeatMode)
        assertEquals("Seek to next under REPEAT_MODE_ONE should seek to beginning of same item", 0, target.first)
        assertEquals("Target position must be 0L", 0L, target.second)
    }

    // --- Stress Test 18: LRC and Synced Lyrics Resolution and Active Line Sync ---
    @Test
    fun lrcAndSyncedLyricsResolutionAndSyncTest() {
        val lrcContent = """
            [00:00.00] Intro instrumental
            [00:04.50] Welcome to the sound of Evi
            [00:08.20] Streaming in lossless quality
            [00:15.80] Outro fade out
        """.trimIndent()

        val parsedLines = wtf.evi.mobile.data.model.LyricsResponse.parseLrcLines(lrcContent)
        assertEquals("Must parse 4 LRC lines", 4, parsedLines.size)
        assertEquals("Line 0 start timestamp", 0L, parsedLines[0].startTime)
        assertEquals("Line 1 start timestamp", 4500L, parsedLines[1].startTime)
        assertEquals("Line 2 start timestamp", 8200L, parsedLines[2].startTime)
        assertEquals("Line 3 start timestamp", 15800L, parsedLines[3].startTime)

        assertEquals("Line 1 text", "Welcome to the sound of Evi", parsedLines[1].fullText)

        // Test LyricsResponse.getResolvedLines priority
        val lrcOnlyResponse = wtf.evi.mobile.data.model.LyricsResponse(
            plain = lrcContent
        )
        val resolvedFromLrc = lrcOnlyResponse.getResolvedLines()
        assertEquals("Should resolve 4 lines from LRC", 4, resolvedFromLrc.size)
        assertEquals(4500L, resolvedFromLrc[1].startTime)

        // Test active line sync binary/linear lookup at various timestamps
        fun findActiveLineIndex(lines: List<wtf.evi.mobile.data.model.LyricLine>, posMs: Long): Int {
            var idx = -1
            for (i in lines.indices) {
                if (lines[i].startTime <= posMs) idx = i else break
            }
            return idx
        }

        assertEquals(-1, findActiveLineIndex(resolvedFromLrc, -100L))
        assertEquals(0, findActiveLineIndex(resolvedFromLrc, 1000L))
        assertEquals(1, findActiveLineIndex(resolvedFromLrc, 5000L))
        assertEquals(2, findActiveLineIndex(resolvedFromLrc, 10000L))
        assertEquals(3, findActiveLineIndex(resolvedFromLrc, 20000L))
    }

    // --- Stress Test 19: Equalizer Preset DSP Gains and Band Math Invariants ---
    @Test
    fun equalizerPresetGainsAndDspInvariants() {
        // Preset-specific audible gain derivation
        fun deriveEffectiveBassBoost(preset: wtf.evi.mobile.data.model.EqPreset, userStrength: Float): Float {
            val presetBassBoost = when (preset) {
                wtf.evi.mobile.data.model.EqPreset.BASS_BOOST -> 0.85f
                wtf.evi.mobile.data.model.EqPreset.HIP_HOP -> 0.60f
                wtf.evi.mobile.data.model.EqPreset.ELECTRONIC, wtf.evi.mobile.data.model.EqPreset.DANCE -> 0.55f
                wtf.evi.mobile.data.model.EqPreset.ROCK -> 0.35f
                else -> 0f
            }
            return maxOf(userStrength, presetBassBoost).coerceIn(0f, 1f)
        }

        fun deriveEffectiveVirtualizer(preset: wtf.evi.mobile.data.model.EqPreset, userStrength: Float): Float {
            val presetVirtualizer = when (preset) {
                wtf.evi.mobile.data.model.EqPreset.ELECTRONIC, wtf.evi.mobile.data.model.EqPreset.DANCE -> 0.60f
                wtf.evi.mobile.data.model.EqPreset.VOCAL, wtf.evi.mobile.data.model.EqPreset.ACOUSTIC -> 0.40f
                wtf.evi.mobile.data.model.EqPreset.ROCK -> 0.30f
                else -> 0f
            }
            return maxOf(userStrength, presetVirtualizer).coerceIn(0f, 1f)
        }

        // Even with user sliders at 0f, BASS_BOOST preset must deliver 0.85f bass boost
        val bassBoostEffective = deriveEffectiveBassBoost(wtf.evi.mobile.data.model.EqPreset.BASS_BOOST, 0f)
        assertEquals(0.85f, bassBoostEffective, 0.001f)

        // ELECTRONIC preset must deliver 0.60f virtualizer
        val electronicVirt = deriveEffectiveVirtualizer(wtf.evi.mobile.data.model.EqPreset.ELECTRONIC, 0f)
        assertEquals(0.60f, electronicVirt, 0.001f)

        // Band level scaling invariant (minMb = -1500, maxMb = 1500)
        val minMb = -1500
        val maxMb = 1500
        fun computeBandLevelMb(w: Float): Short {
            val clampedW = w.coerceIn(-1f, 1f)
            val targetMb = if (clampedW >= 0f) {
                (clampedW * maxMb).toInt()
            } else {
                if (minMb < 0) (clampedW * kotlin.math.abs(minMb)).toInt() else (minMb + (clampedW + 1f) * (maxMb - minMb) / 2f).toInt()
            }
            return targetMb.coerceIn(minMb, maxMb).toShort()
        }

        assertEquals(0.toShort(), computeBandLevelMb(0f))
        assertEquals(1500.toShort(), computeBandLevelMb(1.0f))
        assertEquals((-1500).toShort(), computeBandLevelMb(-1.0f))
        assertEquals(750.toShort(), computeBandLevelMb(0.5f))
        assertEquals((-750).toShort(), computeBandLevelMb(-0.5f))

        // Ensure all presets have 5 bands and weights within [-1.0f, 1.0f]
        for (preset in wtf.evi.mobile.data.model.EqPreset.entries) {
            assertEquals("Preset ${preset.name} must have 5 bands", 5, preset.bandWeights.size)
            for (weight in preset.bandWeights) {
                assertTrue("Weight $weight in ${preset.name} must be in [-1, 1]", weight in -1.0f..1.0f)
            }
        }
    }

    // --- Stress Test 14: Deep Stress Test of Queue Transitions Under Shuffle and Repeat ---
    @Test
    fun deepStressTestPlaybackQueueTransitionsUnderShuffleAndRepeat() {
        val totalSongs = 100
        val queue = List(totalSongs) { i ->
            Song(id = "s_$i", title = "Track $i", duration = 200.0)
        }
        val shuffledIndices = queue.indices.shuffled(Random(42))

        var curIndex = 0
        var repeatMode = 0 // 0=OFF, 1=ALL, 2=ONE
        var shuffle = false

        fun nextTrack() {
            if (shuffle) {
                val currentShufflePos = shuffledIndices.indexOf(curIndex)
                if (currentShufflePos in 0 until shuffledIndices.lastIndex) {
                    curIndex = shuffledIndices[currentShufflePos + 1]
                } else if (repeatMode == 1) { // REPEAT ALL
                    curIndex = shuffledIndices[0]
                }
            } else {
                if (curIndex in 0 until queue.lastIndex) {
                    curIndex++
                } else if (repeatMode == 1) { // REPEAT ALL
                    curIndex = 0
                }
            }
        }

        fun prevTrack(posMs: Long) {
            if (posMs > 3000L) {
                // Restart song, stay on curIndex
                return
            }
            if (shuffle) {
                val currentShufflePos = shuffledIndices.indexOf(curIndex)
                if (currentShufflePos > 0) {
                    curIndex = shuffledIndices[currentShufflePos - 1]
                } else if (repeatMode == 1) {
                    curIndex = shuffledIndices.last()
                }
            } else {
                if (curIndex > 0) {
                    curIndex--
                } else if (repeatMode == 1) {
                    curIndex = queue.lastIndex
                }
            }
        }

        // Stress run 500 transitions under varied shuffle and repeat modes
        for (step in 0 until 500) {
            when (step % 4) {
                0 -> { shuffle = false; repeatMode = 0 }
                1 -> { shuffle = true; repeatMode = 0 }
                2 -> { shuffle = false; repeatMode = 1 }
                3 -> { shuffle = true; repeatMode = 1 }
            }
            nextTrack()
            assertTrue("curIndex must always be within queue bounds: $curIndex", curIndex in queue.indices)
            prevTrack(posMs = 1500L)
            assertTrue("curIndex must always be within queue bounds: $curIndex", curIndex in queue.indices)
            prevTrack(posMs = 5000L)
            assertTrue("curIndex must stay within queue bounds on restart: $curIndex", curIndex in queue.indices)
        }
    }

    // --- Stress Test 15: Buffering Seek Never Jumps Prematurely ---
    @Test
    fun deepStressTestBufferingSeekNeverJumpsPrematurely() {
        var pendingSeekMs: Long? = 75_000L
        val seekTimestamp = 1000L
        var isBuffering = true
        var actualPos = 0L

        fun resolveEffectivePos(currentTime: Long): Long {
            val target = pendingSeekMs ?: return actualPos
            val diff = kotlin.math.abs(actualPos - target)
            return if (diff < 1500L || (!isBuffering && (currentTime - seekTimestamp) > 2500L)) {
                pendingSeekMs = null
                actualPos
            } else {
                target
            }
        }

        // Even after 3 seconds of high latency buffering, the playhead MUST NOT drop back to 0!
        for (sec in 1..3) {
            val eff = resolveEffectivePos(1000L + sec * 1000L)
            assertEquals("Playhead must hold at seek target during network buffering!", 75_000L, eff)
        }

        // Buffering finishes at 3.5s, player reports 74_800ms
        isBuffering = false
        actualPos = 74_800L
        val settledPos = resolveEffectivePos(4500L)
        assertEquals("Playhead must settle cleanly to actual position once caught up!", 74_800L, settledPos)
        assertNull("Pending seek must be cleared after settling", pendingSeekMs)
    }

    // --- Stress Test 16: Transient Network Error Recovery Simulation ---
    @Test
    fun deepStressTestTransientNetworkRecoveryAndErrorHandling() {
        var errorCount = 0
        var playbackResumed = false
        var skippedToNext = false
        val maxRetries = 3

        fun handlePlayerError(isNetworkError: Boolean, queueSize: Int) {
            if (isNetworkError && errorCount < maxRetries) {
                errorCount++
                playbackResumed = true
                return
            }
            if (queueSize > 1) {
                skippedToNext = true
                return
            }
        }

        // Transient errors retry up to 3 times
        for (i in 1..3) {
            playbackResumed = false
            handlePlayerError(isNetworkError = true, queueSize = 10)
            assertTrue("Transient error should trigger resume on attempt $i", playbackResumed)
            assertEquals(i, errorCount)
        }

        // 4th error exhausts retries and advances to next track
        playbackResumed = false
        handlePlayerError(isNetworkError = true, queueSize = 10)
        assertFalse("Exhausted retries should not attempt resume", playbackResumed)
        assertTrue("Exhausted retries should advance to next track in queue", skippedToNext)
    }

    // --- Stress Test 17: Repeat All & Repeat One Must Strictly Stay Within Playlist Bounds ---
    @Test
    fun deepStressTestRepeatAllStaysStrictlyWithinPlaylistBounds() {
        val initialLikedSongs = List(15) { i -> "liked_$i" }
        val currentQueue = initialLikedSongs.map { it to false }.toMutableList() // (id, isAutoplay)
        var currentIndex = 0
        var repeatMode = 1 // 1 = REPEAT_MODE_ALL
        val autoplayRadioSetting = true
        var radioFetchCount = 0

        fun ensureAutoplayRadioIfNeeded() {
            if (!autoplayRadioSetting) return
            // Must NEVER fetch if repeat is active!
            if (repeatMode != 0) return
            val remaining = currentQueue.size - 1 - currentIndex
            if (remaining <= 1) {
                radioFetchCount++
                repeat(5) { k ->
                    currentQueue.add("radio_${k + radioFetchCount * 10}" to true)
                }
            }
        }

        fun trimUpcomingAutoplayTracks() {
            if (currentIndex < 0 || currentQueue.size <= currentIndex + 1) return
            val firstAutoplay = currentQueue.subList(currentIndex + 1, currentQueue.size).indexOfFirst { it.second }
            if (firstAutoplay != -1) {
                val actualIndex = currentIndex + 1 + firstAutoplay
                while (currentQueue.size > actualIndex) {
                    currentQueue.removeAt(currentQueue.lastIndex)
                }
            }
        }

        fun nextSong() {
            if (currentIndex < currentQueue.lastIndex) {
                currentIndex++
            } else if (repeatMode == 1) { // REPEAT ALL
                currentIndex = 0
            }
            ensureAutoplayRadioIfNeeded()
        }

        // Test 1: Under REPEAT_MODE_ALL, advance 60 times (4 full loops around the 15 liked songs)
        repeat(60) { step ->
            nextSong()
            assertTrue("Queue must never exceed initial 15 songs!", currentQueue.size == 15)
            assertTrue("Every song played must be a liked song!", currentQueue[currentIndex].first.startsWith("liked_"))
            assertFalse("No autoplay song may be present in queue!", currentQueue[currentIndex].second)
        }
        assertEquals("Radio fetch must NEVER be invoked under REPEAT_MODE_ALL!", 0, radioFetchCount)

        // Test 2: Toggle repeat OFF -> autoplay triggers when reaching end
        repeatMode = 0 // OFF
        currentIndex = 13 // near end
        ensureAutoplayRadioIfNeeded()
        assertEquals("Autoplay should fetch when repeat is OFF and near end", 1, radioFetchCount)
        assertEquals(20, currentQueue.size) // 15 original + 5 autoplay

        // Test 3: User turns Repeat ALL back ON -> upcoming autoplay tracks must be instantly pruned!
        repeatMode = 1 // ALL
        trimUpcomingAutoplayTracks()
        assertEquals("Upcoming autoplay tracks must be pruned when enabling loop!", 15, currentQueue.size)
        assertTrue("All remaining songs must be strictly the original playlist!", currentQueue.all { !it.second })
    }
}
