package com.budcontrol.sony.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.NoiseAware
import androidx.compose.material.icons.rounded.NoiseControlOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.ui.theme.*

@Composable
fun AncSelector(
    currentMode: SonyCommands.AncMode,
    enabled: Boolean,
    onModeSelected: (SonyCommands.AncMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "NOISE CONTROL",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AncModeButton(
                    mode = SonyCommands.AncMode.NOISE_CANCELING,
                    icon = Icons.Rounded.NoiseAware,
                    label = "NC",
                    isSelected = currentMode == SonyCommands.AncMode.NOISE_CANCELING,
                    accentColor = AncGreen,
                    enabled = enabled,
                    onClick = { onModeSelected(SonyCommands.AncMode.NOISE_CANCELING) },
                    modifier = Modifier.weight(1f)
                )
                AncModeButton(
                    mode = SonyCommands.AncMode.AMBIENT_SOUND,
                    icon = Icons.Rounded.Headphones,
                    label = "Ambient",
                    isSelected = currentMode == SonyCommands.AncMode.AMBIENT_SOUND,
                    accentColor = AmbientBlue,
                    enabled = enabled,
                    onClick = { onModeSelected(SonyCommands.AncMode.AMBIENT_SOUND) },
                    modifier = Modifier.weight(1f)
                )
                AncModeButton(
                    mode = SonyCommands.AncMode.OFF,
                    icon = Icons.Rounded.NoiseControlOff,
                    label = "Off",
                    isSelected = currentMode == SonyCommands.AncMode.OFF,
                    accentColor = AncOffGray,
                    enabled = enabled,
                    onClick = { onModeSelected(SonyCommands.AncMode.OFF) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AncModeButton(
    mode: SonyCommands.AncMode,
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        if (isSelected) accentColor.copy(alpha = 0.15f) else SonyCardSurfaceAlt,
        animationSpec = tween(200),
        label = "ancBg"
    )
    val borderColor by animateColorAsState(
        if (isSelected) accentColor else DividerColor,
        animationSpec = tween(200),
        label = "ancBorder"
    )
    val contentColor by animateColorAsState(
        if (isSelected) accentColor else TextSecondary,
        animationSpec = tween(200),
        label = "ancContent"
    )

    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(80.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = bgColor),
        border = BorderStroke(1.5.dp, borderColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}
