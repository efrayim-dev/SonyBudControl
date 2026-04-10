package com.budcontrol.sony.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.ui.theme.*

@Composable
fun EqualizerCard(
    currentPreset: SonyCommands.EqPreset,
    enabled: Boolean,
    onPresetSelected: (SonyCommands.EqPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = listOf(
        SonyCommands.EqPreset.OFF,
        SonyCommands.EqPreset.BASS_BOOST,
        SonyCommands.EqPreset.TREBLE_BOOST,
        SonyCommands.EqPreset.VOCAL,
        SonyCommands.EqPreset.BRIGHT,
        SonyCommands.EqPreset.EXCITED,
        SonyCommands.EqPreset.MELLOW,
        SonyCommands.EqPreset.RELAXED,
        SonyCommands.EqPreset.SPEECH
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "EQUALIZER",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = currentPreset.displayName,
                style = MaterialTheme.typography.headlineMedium,
                color = AmberPrimary
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    EqChip(
                        preset = preset,
                        isSelected = preset == currentPreset,
                        enabled = enabled,
                        onClick = { onPresetSelected(preset) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EqChip(
    preset: SonyCommands.EqPreset,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        if (isSelected) AmberPrimary.copy(alpha = 0.15f) else SonyCardSurfaceAlt,
        animationSpec = tween(200),
        label = "eqBg"
    )
    val borderColor by animateColorAsState(
        if (isSelected) AmberPrimary else DividerColor,
        animationSpec = tween(200),
        label = "eqBorder"
    )
    val textColor by animateColorAsState(
        if (isSelected) AmberPrimary else TextSecondary,
        animationSpec = tween(200),
        label = "eqText"
    )

    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.outlinedCardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = preset.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
