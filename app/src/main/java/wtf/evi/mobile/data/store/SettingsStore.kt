package wtf.evi.mobile.data.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import wtf.evi.mobile.data.model.AccentColor
import wtf.evi.mobile.data.model.AppSettings
import wtf.evi.mobile.data.model.AppTheme
import wtf.evi.mobile.data.model.AudioQuality
import wtf.evi.mobile.data.model.EqPreset

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("evi_settings_prefs", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(next)).apply()
    }

    fun setTheme(theme: AppTheme) = update { it.copy(theme = theme) }
    fun setAccent(accent: AccentColor) = update { it.copy(accent = accent) }
    fun setAudioQuality(quality: AudioQuality) = update { it.copy(audioQuality = quality) }
    fun setEqEnabled(enabled: Boolean) = update { it.copy(eqEnabled = enabled) }
    fun setEqPreset(preset: EqPreset) = update { it.copy(eqPreset = preset) }
    fun setCustomEqBand(bandIndex: Int, weight: Float) = update { cur ->
        val bands = cur.customEqBands.toMutableList()
        while (bands.size < 5) bands.add(0f)
        if (bandIndex in bands.indices) {
            bands[bandIndex] = weight.coerceIn(-1f, 1f)
        }
        cur.copy(customEqBands = bands, eqPreset = EqPreset.CUSTOM)
    }
    fun setCustomEqBands(weights: List<Float>) = update {
        it.copy(customEqBands = weights.map { w -> w.coerceIn(-1f, 1f) }, eqPreset = EqPreset.CUSTOM)
    }
    fun setBassBoostStrength(strength: Float) = update { it.copy(bassBoostStrength = strength.coerceIn(0f, 1f)) }
    fun setVirtualizerStrength(strength: Float) = update { it.copy(virtualizerStrength = strength.coerceIn(0f, 1f)) }
    fun resetEqualizer() = update {
        it.copy(
            eqEnabled = true,
            eqPreset = EqPreset.FLAT,
            customEqBands = listOf(0f, 0f, 0f, 0f, 0f),
            bassBoostStrength = 0f,
            virtualizerStrength = 0f
        )
    }
    fun setAutoplayRadio(enabled: Boolean) = update { it.copy(autoplayRadio = enabled) }

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        update { cur ->
            val updated = (listOf(q) + cur.recentSearches.filterNot { it.equals(q, ignoreCase = true) }).take(10)
            cur.copy(recentSearches = updated)
        }
    }

    fun clearRecentSearches() = update { it.copy(recentSearches = emptyList()) }

    companion object {
        private const val KEY_SETTINGS = "settings_json"
    }
}
