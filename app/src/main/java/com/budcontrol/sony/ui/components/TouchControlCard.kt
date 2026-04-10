@file:OptIn(ExperimentalMaterial3Api::class)

package com.budcontrol.sony.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.ui.theme.*

@Composable
fun TouchControlCard(
    enabled: Boolean,
    leftMode: SonyCommands.ButtonMode,
    rightMode: SonyCommands.ButtonMode,
    onModesChanged: (SonyCommands.ButtonMode, SonyCommands.ButtonMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Gesture,
                    contentDescription = "Touch Controls",
                    tint = AmberPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "Touch Controls",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            }

            Spacer(Modifier.height(16.dp))

            ButtonModeSelector(
                label = "Left Ear",
                selected = leftMode,
                enabled = enabled,
                onSelected = { onModesChanged(it, rightMode) }
            )

            Spacer(Modifier.height(12.dp))

            ButtonModeSelector(
                label = "Right Ear",
                selected = rightMode,
                enabled = enabled,
                onSelected = { onModesChanged(leftMode, it) }
            )
        }
    }
}

@Composable
private fun ButtonModeSelector(
    label: String,
    selected: SonyCommands.ButtonMode,
    enabled: Boolean,
    onSelected: (SonyCommands.ButtonMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val modes = SonyCommands.ButtonMode.entries

    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it }
        ) {
            OutlinedTextField(
                value = selected.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    disabledTextColor = TextTertiary,
                    focusedBorderColor = AmberPrimary,
                    unfocusedBorderColor = DividerColor,
                    disabledBorderColor = DividerColor
                ),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = SonyDarkSurface
            ) {
                modes.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                mode.displayName,
                                color = if (mode == selected) AmberPrimary else TextPrimary
                            )
                        },
                        onClick = {
                            onSelected(mode)
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = when (mode) {
                                    SonyCommands.ButtonMode.OFF -> Icons.Rounded.Block
                                    SonyCommands.ButtonMode.AMBIENT_SOUND_CONTROL -> Icons.Rounded.HearingDisabled
                                    SonyCommands.ButtonMode.PLAYBACK_CONTROL -> Icons.Rounded.PlayCircle
                                    SonyCommands.ButtonMode.VOLUME_CONTROL -> Icons.AutoMirrored.Rounded.VolumeUp
                                },
                                contentDescription = null,
                                tint = if (mode == selected) AmberPrimary else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}
