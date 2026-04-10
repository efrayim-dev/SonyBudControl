package com.budcontrol.sony.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.budcontrol.sony.wear.WearDeviceState

private val WearAmber = Color(0xFFF5A623)
private val WearGreen = Color(0xFF4CAF50)
private val WearBlue = Color(0xFF42A5F5)
private val WearGray = Color(0xFF757575)
private val WearRed = Color(0xFFEF5350)
private val WearSurface = Color(0xFF1A1A1A)

@Composable
fun WearDashboard(
    state: WearDeviceState,
    onCycleAnc: () -> Unit,
    onSetAnc: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(
                    text = if (state.connected) state.deviceName.ifEmpty { "Connected" } else "Not Connected",
                    style = MaterialTheme.typography.title3,
                    color = if (state.connected) WearGreen else WearGray,
                    textAlign = TextAlign.Center
                )
            }

            if (state.batteryAvg >= 0) {
                item {
                    val color = when {
                        state.batteryAvg > 50 -> WearGreen
                        state.batteryAvg > 20 -> WearAmber
                        else -> WearRed
                    }
                    Text(
                        text = "${state.batteryAvg}%",
                        style = MaterialTheme.typography.display3,
                        color = color,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                val ancColor = when (state.ancMode) {
                    "NOISE_CANCELING" -> WearGreen
                    "WIND_NOISE_REDUCTION" -> WearBlue
                    "AMBIENT_SOUND" -> WearAmber
                    else -> WearGray
                }
                val ancLabel = when (state.ancMode) {
                    "NOISE_CANCELING" -> "NC"
                    "WIND_NOISE_REDUCTION" -> "Wind"
                    "AMBIENT_SOUND" -> "Ambient"
                    else -> "Off"
                }
                Chip(
                    onClick = onCycleAnc,
                    label = { Text(ancLabel, fontSize = 14.sp) },
                    secondaryLabel = { Text("Tap to cycle", fontSize = 11.sp, color = Color.Gray) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = ancColor.copy(alpha = 0.2f),
                        contentColor = ancColor
                    ),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    CompactChip(
                        onClick = { onSetAnc("NOISE_CANCELING") },
                        label = { Text("NC", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (state.ancMode == "NOISE_CANCELING")
                                WearGreen.copy(alpha = 0.3f) else WearSurface,
                            contentColor = if (state.ancMode == "NOISE_CANCELING")
                                WearGreen else Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = { onSetAnc("AMBIENT_SOUND") },
                        label = { Text("AMB", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (state.ancMode == "AMBIENT_SOUND")
                                WearAmber.copy(alpha = 0.3f) else WearSurface,
                            contentColor = if (state.ancMode == "AMBIENT_SOUND")
                                WearAmber else Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    CompactChip(
                        onClick = { onSetAnc("OFF") },
                        label = { Text("OFF", fontSize = 11.sp) },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (state.ancMode == "OFF")
                                WearGray.copy(alpha = 0.3f) else WearSurface,
                            contentColor = if (state.ancMode == "OFF")
                                WearGray else Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                CompactChip(
                    onClick = onRefresh,
                    label = { Text("Refresh", fontSize = 12.sp) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = WearSurface,
                        contentColor = WearBlue
                    )
                )
            }
        }
    }
}
