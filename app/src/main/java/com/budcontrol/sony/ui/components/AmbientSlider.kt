package com.budcontrol.sony.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.ui.theme.*

@Composable
fun AmbientSlider(
    ambientLevel: Int,
    focusOnVoice: Boolean,
    windReduction: Boolean,
    ancMode: SonyCommands.AncMode,
    enabled: Boolean,
    onLevelChange: (Int) -> Unit,
    onFocusOnVoiceChange: (Boolean) -> Unit,
    onWindReductionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val showAmbient = ancMode == SonyCommands.AncMode.AMBIENT_SOUND
    val showWind = ancMode == SonyCommands.AncMode.NOISE_CANCELING

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "FINE TUNING",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(
                visible = showAmbient,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ambient Level", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                        Text(
                            "$ambientLevel",
                            style = MaterialTheme.typography.headlineMedium,
                            color = AmbientBlue
                        )
                    }

                    var sliderValue by remember(ambientLevel) { mutableFloatStateOf(ambientLevel.toFloat()) }

                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { onLevelChange(sliderValue.toInt()) },
                        valueRange = 0f..20f,
                        steps = 19,
                        enabled = enabled,
                        colors = SliderDefaults.colors(
                            thumbColor = AmbientBlue,
                            activeTrackColor = AmbientBlue,
                            inactiveTrackColor = DividerColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))

                    ToggleRow(
                        label = "Focus on Voice",
                        checked = focusOnVoice,
                        enabled = enabled,
                        onCheckedChange = onFocusOnVoiceChange
                    )
                }
            }

            AnimatedVisibility(
                visible = showWind,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                ToggleRow(
                    label = "Wind Noise Reduction",
                    checked = windReduction,
                    enabled = enabled,
                    onCheckedChange = onWindReductionChange
                )
            }

            AnimatedVisibility(
                visible = !showAmbient && !showWind,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Text(
                    text = "Select NC or Ambient mode to adjust settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmberPrimary,
                checkedTrackColor = AmberDim,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = DividerColor
            )
        )
    }
}
