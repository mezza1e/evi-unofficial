package wtf.evi.mobile.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import okhttp3.OkHttpClient
import wtf.evi.mobile.data.model.EVI_USER_AGENT

/**
 * Shared Media3 data-source, extractor, and duration-guard helpers used by [EviPlaybackService]
 * and integration tests.
 *
 * Key guarantees:
 * 1. [CacheDataSource] does NOT set `FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS`, because
 *    `ProgressiveMediaPeriod` always opens requests with `DataSpec.length == C.LENGTH_UNSET`.
 *    Omitting that flag ensures progressive audio streams actually cache to [SimpleCache] and
 *    subsequent `REPEAT_MODE_ONE` loops read from disk instead of reopening upstream HTTP streams.
 * 2. [DefaultExtractorsFactory] keeps CBR seeking enabled (even when `Content-Length` is unknown
 *    on chunked responses) and omits `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING` so partial/truncated
 *    loads never overwrite `ProgressiveMediaSource.timelineDurationUs` with a bogus short duration
 *    (such as ~10s) and `seekToCurrentPosition` during repeat toggles never clamps to 0ms.
 * 3. [DurationGuardedMediaSource] prevents any secondary period or truncated seek map from shrinking
 *    a track's reported timeline duration far below its known metadata or previously verified duration.
 */
@OptIn(UnstableApi::class)
object EviMediaSourceSupport {

    internal const val MIN_GUARD_THRESHOLD_US = 15_000_000L // 15s
    internal const val MIN_VALID_DURATION_RATIO = 0.70

    /**
     * Wraps [OkHttpDataSource] so [getUri] always reports the original request URI rather than
     * any ephemeral CDN 302 redirect target. Prevents [CacheDataSource] from persisting expiring
     * redirect URLs (`exo_redir`) into `SimpleCache` metadata.
     */
    class StableUriDataSource(
        private val delegate: DataSource
    ) : DataSource {
        private var requestUri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            requestUri = dataSpec.uri
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return delegate.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = requestUri ?: delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() {
            try {
                delegate.close()
            } finally {
                requestUri = null
            }
        }
    }

    fun buildDataSourceFactory(
        context: Context,
        streamingHttpClient: OkHttpClient,
        streamCache: SimpleCache
    ): DataSource.Factory {
        val okHttpFactory = OkHttpDataSource.Factory(streamingHttpClient)
            .setUserAgent(EVI_USER_AGENT)

        val upstreamHttpFactory = DataSource.Factory {
            StableUriDataSource(okHttpFactory.createDataSource())
        }

        val cacheWriteSinkFactory = CacheDataSink.Factory()
            .setCache(streamCache)
            .setFragmentSize(2L * 1024L * 1024L)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(upstreamHttpFactory)
            .setCacheWriteDataSinkFactory(cacheWriteSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Outer DefaultDataSource routes file:// offline downloads directly to FileDataSource
        // and http(s):// streams to CacheDataSource.
        return DefaultDataSource.Factory(context, cacheDataSourceFactory)
    }

    fun buildExtractorsFactory(): DefaultExtractorsFactory {
        return DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
    }

    fun buildMediaSourceFactory(
        context: Context,
        streamingHttpClient: OkHttpClient,
        streamCache: SimpleCache
    ): MediaSource.Factory {
        val delegate = DefaultMediaSourceFactory(
            buildDataSourceFactory(context, streamingHttpClient, streamCache),
            buildExtractorsFactory()
        )
        return DurationGuardedMediaSourceFactory(delegate)
    }

    internal fun computeSafeDurationUs(
        reportedDurationUs: Long,
        expectedDurationUs: Long,
        bestObservedDurationUs: Long
    ): Long {
        val anchorUs = when {
            bestObservedDurationUs > 0L && expectedDurationUs > 0L ->
                maxOf(bestObservedDurationUs, expectedDurationUs)
            bestObservedDurationUs > 0L -> bestObservedDurationUs
            expectedDurationUs > 0L -> expectedDurationUs
            else -> C.TIME_UNSET
        }

        if (reportedDurationUs == C.TIME_UNSET || reportedDurationUs <= 0L) {
            return anchorUs
        }

        if (expectedDurationUs >= MIN_GUARD_THRESHOLD_US &&
            reportedDurationUs < (expectedDurationUs * MIN_VALID_DURATION_RATIO).toLong()
        ) {
            return anchorUs
        }

        if (bestObservedDurationUs >= MIN_GUARD_THRESHOLD_US &&
            reportedDurationUs < (bestObservedDurationUs * MIN_VALID_DURATION_RATIO).toLong()
        ) {
            return bestObservedDurationUs
        }

        return reportedDurationUs
    }

    internal fun resolveEffectiveDurationMs(
        playerDurationMs: Long,
        metadataDurationMs: Long,
        previousDurationMs: Long
    ): Long {
        val safeMeta = metadataDurationMs.coerceAtLeast(0L)
        val safePrev = previousDurationMs.coerceAtLeast(0L)
        val anchor = maxOf(safeMeta, safePrev)

        if (playerDurationMs <= 0L || playerDurationMs == C.TIME_UNSET) {
            return anchor
        }

        if (safeMeta >= 15_000L && playerDurationMs < (safeMeta * MIN_VALID_DURATION_RATIO).toLong()) {
            return anchor
        }

        if (safePrev >= 15_000L && playerDurationMs < (safePrev * MIN_VALID_DURATION_RATIO).toLong()) {
            return safePrev
        }

        return playerDurationMs
    }

    class DurationGuardedMediaSourceFactory(
        private val delegate: MediaSource.Factory
    ) : MediaSource.Factory {

        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
            delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
            delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        override fun getSupportedTypes(): IntArray = delegate.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val expectedDurationUs = mediaItem.mediaMetadata.durationMs
                ?.takeIf { it > 0L }
                ?.times(1000L)
                ?: C.TIME_UNSET
            val expectedIsLive = mediaItem.liveConfiguration != MediaItem.LiveConfiguration.UNSET
            return DurationGuardedMediaSource(
                delegate = delegate.createMediaSource(mediaItem),
                expectedDurationUs = expectedDurationUs,
                expectedIsLive = expectedIsLive
            )
        }
    }

    private class DurationGuardedMediaSource(
        delegate: MediaSource,
        private val expectedDurationUs: Long,
        private val expectedIsLive: Boolean
    ) : WrappingMediaSource(delegate) {

        @Volatile
        private var bestObservedDurationUs: Long = C.TIME_UNSET

        override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
            if (newTimeline.isEmpty) {
                super.onChildSourceInfoRefreshed(newTimeline)
                return
            }
            val window = newTimeline.getWindow(0, Timeline.Window())
            val reportedUs = window.durationUs
            val safeDurationUs = computeSafeDurationUs(reportedUs, expectedDurationUs, bestObservedDurationUs)

            if (reportedUs != C.TIME_UNSET && reportedUs > 0L &&
                (expectedDurationUs < MIN_GUARD_THRESHOLD_US || reportedUs >= (expectedDurationUs * MIN_VALID_DURATION_RATIO).toLong()) &&
                (bestObservedDurationUs == C.TIME_UNSET || reportedUs >= (bestObservedDurationUs * MIN_VALID_DURATION_RATIO).toLong())
            ) {
                bestObservedDurationUs = maxOf(bestObservedDurationUs.coerceAtLeast(0L), reportedUs)
            }

            // ProgressiveMediaPeriod sets isLive=true whenever HTTP uses Transfer-Encoding: chunked
            // (unknown Content-Length) before EOF. If metadata shows a finite track (!expectedIsLive),
            // clear the spurious live state and enforce safeDurationUs so seekbar & looping stay stable.
            val timelineToEmit = if (!expectedIsLive && !window.isPlaceholder &&
                safeDurationUs != C.TIME_UNSET &&
                (safeDurationUs != reportedUs || window.isLive || window.isDynamic)
            ) {
                DurationOverrideTimeline(newTimeline, safeDurationUs)
            } else {
                newTimeline
            }
            super.onChildSourceInfoRefreshed(timelineToEmit)
        }
    }

    private class DurationOverrideTimeline(
        timeline: Timeline,
        private val safeDurationUs: Long
    ) : ForwardingTimeline(timeline) {

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            super.getWindow(windowIndex, window, defaultPositionProjectionUs)
            if (windowIndex == 0) {
                window.durationUs = safeDurationUs
                window.isDynamic = false
                window.liveConfiguration = null
            }
            return window
        }

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            super.getPeriod(periodIndex, period, setIds)
            if (periodIndex == 0 && (period.durationUs == C.TIME_UNSET || period.durationUs < safeDurationUs)) {
                period.durationUs = safeDurationUs
            }
            return period
        }
    }
}
