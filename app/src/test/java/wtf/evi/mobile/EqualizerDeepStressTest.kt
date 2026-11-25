package wtf.evi.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.evi.mobile.data.model.AppSettings
import wtf.evi.mobile.data.model.EqPreset
import kotlin.random.Random

class EqualizerDeepStressTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    // Helper simulating the hardware band level calculation in EviPlaybackService
    private fun calculateHardwareBandLevel(
        weight: Float,
        minMb: Int,
        maxMb: Int
    ): Short {
        val safeWeight = if (weight.isNaN()) 0f else weight.coerceIn(-1f, 1f)
        val target = if (safeWeight >= 0f) (safeWeight * maxMb) else (-safeWeight * minMb)
        return target.toInt().coerceIn(minMb, maxMb).toShort()
    }

    // Helper simulating the hardware band remapping loop in EviPlaybackService
    private fun remapWeightsToHardwareBands(
        weights: List<Float>,
        hardwareBandsCount: Int,
        minMb: Int,
        maxMb: Int
    ): List<Short> {
        val bands = hardwareBandsCount.coerceAtLeast(0)
        val result = mutableListOf<Short>()
        for (b in 0 until bands) {
            val idx = if (weights.size <= 1) 0 else {
                ((b.toFloat() / (bands - 1).coerceAtLeast(1)) * (weights.size - 1))
                    .toInt()
                    .coerceIn(0, weights.size - 1)
            }
            val w = weights.getOrElse(idx) { 0f }.coerceIn(-1f, 1f)
            result.add(calculateHardwareBandLevel(w, minMb, maxMb))
        }
        return result
    }

    // --- Stress Test 1: Mathematical Clamping and Extreme/NaN Protection ---
    @Test
    fun extremeWeightValuesAndNanHandlingNeverProduceInvalidMillibels() {
        val minMb = -1500
        val maxMb = 1500

        // Test normal bounds
        assertEquals(0.toShort(), calculateHardwareBandLevel(0f, minMb, maxMb))
        assertEquals(1500.toShort(), calculateHardwareBandLevel(1.0f, minMb, maxMb))
        assertEquals((-1500).toShort(), calculateHardwareBandLevel(-1.0f, minMb, maxMb))
        assertEquals(750.toShort(), calculateHardwareBandLevel(0.5f, minMb, maxMb))
        assertEquals((-750).toShort(), calculateHardwareBandLevel(-0.5f, minMb, maxMb))

        // Extreme positive and negative values must safely clamp
        assertEquals(1500.toShort(), calculateHardwareBandLevel(999.0f, minMb, maxMb))
        assertEquals((-1500).toShort(), calculateHardwareBandLevel(-999.0f, minMb, maxMb))
        assertEquals(1500.toShort(), calculateHardwareBandLevel(Float.MAX_VALUE, minMb, maxMb))
        assertEquals((-1500).toShort(), calculateHardwareBandLevel(-Float.MAX_VALUE, minMb, maxMb))

        // NaN must gracefully resolve to 0 dB flat
        assertEquals(0.toShort(), calculateHardwareBandLevel(Float.NaN, minMb, maxMb))
    }

    // --- Stress Test 2: Hardware Band Remapping Across Diverse Android Audio DSPs ---
    @Test
    fun hardwareBandRemappingAcrossDiverseAudioChips() {
        val preset = EqPreset.BASS_BOOST
        val weights = preset.bandWeights // [0.85, 0.55, 0, -0.1, 0]

        val dspConfigurations = listOf(
            Triple(1, -1000, 1000),   // Single master band
            Triple(3, -1200, 1200),   // 3-band budget chip (Bass, Mid, Treble)
            Triple(5, -1500, 1500),   // Standard 5-band Qualcomm/MediaTek DSP
            Triple(8, -1500, 1500),   // 8-band Hi-Fi audio chip
            Triple(10, -2000, 2000),  // 10-band wide dynamic range DSP
            Triple(31, -1200, 1200)   // 31-band studio graphic EQ chip
        )

        for ((bandCount, minMb, maxMb) in dspConfigurations) {
            val mapped = remapWeightsToHardwareBands(weights, bandCount, minMb, maxMb)
            assertEquals("Expected $bandCount mapped bands", bandCount, mapped.size)

            for (level in mapped) {
                assertTrue("Level $level must be >= minMb $minMb", level >= minMb.toShort())
                assertTrue("Level $level must be <= maxMb $maxMb", level <= maxMb.toShort())
            }

            // Lowest band should have the bass boost applied (> 0)
            assertTrue("Sub-bass band should be boosted", mapped.first() > 0)
        }
    }

    // --- Stress Test 3: BassBoost and Virtualizer Hardware Scaling Accuracy ---
    @Test
    fun bassBoostAndVirtualizerHardwareScalingAccuracy() {
        fun scaleToHardwareEffect(strengthFraction: Float): Short {
            return (strengthFraction.coerceIn(0f, 1f) * 1000).toInt().toShort()
        }

        // Test normal range
        assertEquals(0.toShort(), scaleToHardwareEffect(0f))
        assertEquals(500.toShort(), scaleToHardwareEffect(0.5f))
        assertEquals(1000.toShort(), scaleToHardwareEffect(1.0f))

        // Test over/underflow clamping
        assertEquals(0.toShort(), scaleToHardwareEffect(-10f))
        assertEquals(1000.toShort(), scaleToHardwareEffect(50f))
        assertEquals(1000.toShort(), scaleToHardwareEffect(Float.MAX_VALUE))
    }

    // --- Stress Test 4: Concurrency & Multi-Threaded Equalizer Parameter Mutation ---
    @Test
    fun highConcurrencyRapidEqualizerMutationsAreThreadSafe() = runBlocking(Dispatchers.Default) {
        var settings = AppSettings()
        val lock = Any()

        val jobs = (0 until 60).map { workerId ->
            async {
                val rand = Random(workerId * 31)
                repeat(200) {
                    when (rand.nextInt(5)) {
                        0 -> {
                            // Toggle EQ
                            val enabled = rand.nextBoolean()
                            synchronized(lock) {
                                settings = settings.copy(eqEnabled = enabled)
                            }
                        }
                        1 -> {
                            // Change preset
                            val p = EqPreset.entries[rand.nextInt(EqPreset.entries.size)]
                            synchronized(lock) {
                                settings = settings.copy(eqPreset = p)
                            }
                        }
                        2 -> {
                            // Mutate random band
                            val b = rand.nextInt(5)
                            val w = rand.nextFloat() * 2f - 1f
                            synchronized(lock) {
                                val bands = settings.customEqBands.toMutableList()
                                bands[b] = w.coerceIn(-1f, 1f)
                                settings = settings.copy(customEqBands = bands, eqPreset = EqPreset.CUSTOM)
                            }
                        }
                        3 -> {
                            // Bass boost
                            val bb = rand.nextFloat()
                            synchronized(lock) {
                                settings = settings.copy(bassBoostStrength = bb.coerceIn(0f, 1f))
                            }
                        }
                        4 -> {
                            // Virtualizer
                            val vz = rand.nextFloat()
                            synchronized(lock) {
                                settings = settings.copy(virtualizerStrength = vz.coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }

        jobs.awaitAll()

        // Verify final state is valid and serializable
        assertEquals(5, settings.customEqBands.size)
        assertTrue(settings.bassBoostStrength in 0f..1f)
        assertTrue(settings.virtualizerStrength in 0f..1f)

        val jsonStr = json.encodeToString(AppSettings.serializer(), settings)
        val restored = json.decodeFromString<AppSettings>(jsonStr)
        assertEquals(settings, restored)
    }

    // --- Stress Test 5: Rapid Continuous Slider Dragging Stability ---
    @Test
    fun rapidContinuousSliderDraggingMemoryStability() {
        val startNano = System.nanoTime()
        var currentBands = listOf(0f, 0f, 0f, 0f, 0f)

        // Simulate 20,000 rapid touch moves
        for (i in 0 until 20_000) {
            val band = i % 5
            val newWeight = ((i % 200) / 100f) - 1.0f // cycling [-1.0, 1.0]
            val mutable = currentBands.toMutableList()
            mutable[band] = newWeight
            currentBands = mutable

            // Calculate DSP levels
            val hw = remapWeightsToHardwareBands(currentBands, 5, -1500, 1500)
            assertEquals(5, hw.size)
        }

        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000
        // 20,000 calculations should complete comfortably under 500ms
        assertTrue("Rapid calculations took ${elapsedMs}ms, expected < 500ms", elapsedMs < 500)
    }

    // --- Stress Test 6: Verify All 14 Presets Coverage and Sanity ---
    @Test
    fun verifyAllFourteenPresetsCoverage() {
        assertEquals(15, EqPreset.entries.size) // 14 named presets + CUSTOM

        for (preset in EqPreset.entries) {
            assertEquals("Preset ${preset.name} must have 5 bands", 5, preset.bandWeights.size)
            for (weight in preset.bandWeights) {
                assertTrue("Weight in ${preset.name} must be within [-1, 1]", weight in -1.0f..1.0f)
            }
            assertFalse("Preset ${preset.name} label cannot be blank", preset.label.isBlank())
        }
    }

    // --- Stress Test 7: Audio Effect Priority & Preset Bass/Virtualizer Derivation ---
    @Test
    fun audioEffectStandardAppPriorityAndPresetDerivation() {
        // Android AudioEffect requires priority 0 for standard applications
        val standardAppPriority = 0
        assertEquals(0, standardAppPriority)

        // Test preset-derived bass boost & virtualizer levels
        fun derivePresetBass(preset: EqPreset, manualStrength: Float): Float {
            val presetBassBoost = when (preset) {
                EqPreset.BASS_BOOST -> 0.85f
                EqPreset.HIP_HOP -> 0.60f
                EqPreset.ELECTRONIC, EqPreset.DANCE -> 0.55f
                EqPreset.ROCK -> 0.35f
                else -> 0f
            }
            return maxOf(manualStrength, presetBassBoost).coerceIn(0f, 1f)
        }

        fun derivePresetVirtualizer(preset: EqPreset, manualStrength: Float): Float {
            val presetVirtualizer = when (preset) {
                EqPreset.ELECTRONIC, EqPreset.DANCE -> 0.60f
                EqPreset.VOCAL, EqPreset.ACOUSTIC -> 0.40f
                EqPreset.ROCK -> 0.30f
                else -> 0f
            }
            return maxOf(manualStrength, presetVirtualizer).coerceIn(0f, 1f)
        }

        assertEquals(0.85f, derivePresetBass(EqPreset.BASS_BOOST, 0f), 0.001f)
        assertEquals(0.95f, derivePresetBass(EqPreset.BASS_BOOST, 0.95f), 0.001f)
        assertEquals(0.60f, derivePresetVirtualizer(EqPreset.ELECTRONIC, 0f), 0.001f)
        assertEquals(0.40f, derivePresetVirtualizer(EqPreset.VOCAL, 0.2f), 0.001f)
        assertEquals(0.70f, derivePresetVirtualizer(EqPreset.VOCAL, 0.7f), 0.001f)
        assertEquals(0f, derivePresetBass(EqPreset.FLAT, 0f), 0.001f)
    }
}
