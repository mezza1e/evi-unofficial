package wtf.evi.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wtf.evi.mobile.data.model.AppSettings
import wtf.evi.mobile.data.model.EqPreset
import wtf.evi.mobile.ui.theme.LocalEviColors
import kotlin.math.roundToInt

private val BAND_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
private val BAND_DESCRIPTIONS = listOf("Sub-bass", "Bass", "Mids", "High-mids", "Treble")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqualizerBottomSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onToggleEq: (Boolean) -> Unit,
    onSelectPreset: (EqPreset) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onBassBoostChange: (Float) -> Unit,
    onVirtualizerChange: (Float) -> Unit,
    onResetToFlat: () -> Unit
) {
    val colors = LocalEviColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Equalizer",
                        tint = if (settings.eqEnabled) colors.accent else colors.textMuted
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Audio Equalizer",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (settings.eqEnabled) "${settings.eqPreset.label} mode active" else "Equalizer bypassed",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onResetToFlat, enabled = settings.eqEnabled) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = "Reset to Flat",
                            tint = if (settings.eqEnabled) colors.textSecondary else colors.textMuted
                        )
                    }
                    Switch(
                        checked = settings.eqEnabled,
                        onCheckedChange = onToggleEq
                    )
                }
            }

            HorizontalDivider(
                color = colors.border,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // Preset Selection
            Text(
                text = "Presets",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EqPreset.entries.forEach { preset ->
                    val isSelected = settings.eqPreset == preset
                    FilterChip(
                        selected = isSelected,
                        onClick = { if (settings.eqEnabled) onSelectPreset(preset) },
                        enabled = settings.eqEnabled,
                        label = {
                            Text(
                                text = preset.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.accent.copy(alpha = 0.22f),
                            selectedLabelColor = colors.accent
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5-Band Equalizer Sliders
            Text(
                text = "Frequency Bands (dB)",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            val currentBands = if (settings.eqPreset == EqPreset.CUSTOM) {
                settings.customEqBands
            } else {
                settings.eqPreset.bandWeights
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceElevated.copy(alpha = 0.6f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (b in 0 until 5) {
                    val weight = currentBands.getOrElse(b) { 0f }.coerceIn(-1f, 1f)
                    val dbValue = (weight * 10f).roundToInt()
                    val dbText = if (dbValue > 0) "+$dbValue dB" else "$dbValue dB"

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = BAND_LABELS[b],
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (settings.eqEnabled) colors.textPrimary else colors.textMuted
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${BAND_DESCRIPTIONS[b]})",
                                    fontSize = 11.sp,
                                    color = colors.textMuted
                                )
                            }
                            Text(
                                text = dbText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (dbValue != 0 && settings.eqEnabled) colors.accent else colors.textSecondary
                            )
                        }
                        Slider(
                            value = (weight + 1f) / 2f, // maps [-1, 1] to [0, 1]
                            onValueChange = { norm ->
                                if (settings.eqEnabled) {
                                    val newWeight = (norm * 2f) - 1f
                                    onBandChange(b, newWeight)
                                }
                            },
                            enabled = settings.eqEnabled,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.accent,
                                activeTrackColor = colors.accent,
                                inactiveTrackColor = colors.textPrimary.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Audio Enhancement Sliders (Bass Boost & Surround)
            Text(
                text = "Audio Enhancements",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceElevated.copy(alpha = 0.6f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Bass Boost
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Bass Boost",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (settings.eqEnabled) colors.textPrimary else colors.textMuted
                        )
                        Text(
                            text = "${(settings.bassBoostStrength * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (settings.bassBoostStrength > 0f && settings.eqEnabled) colors.accent else colors.textSecondary
                        )
                    }
                    Slider(
                        value = settings.bassBoostStrength,
                        onValueChange = onBassBoostChange,
                        enabled = settings.eqEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.textPrimary.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Surround Virtualizer
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Surround Virtualizer",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (settings.eqEnabled) colors.textPrimary else colors.textMuted
                        )
                        Text(
                            text = "${(settings.virtualizerStrength * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (settings.virtualizerStrength > 0f && settings.eqEnabled) colors.accent else colors.textSecondary
                        )
                    }
                    Slider(
                        value = settings.virtualizerStrength,
                        onValueChange = onVirtualizerChange,
                        enabled = settings.eqEnabled,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.textPrimary.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
