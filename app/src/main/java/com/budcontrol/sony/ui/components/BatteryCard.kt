package com.budcontrol.sony.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.bluetooth.DeviceState
import com.budcontrol.sony.ui.theme.*

@Composable
fun BatteryCard(
    state: DeviceState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "BATTERY",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                BatteryItem(label = "Left", level = state.batteryLeft, charging = state.leftCharging)
                BatteryItem(label = "Right", level = state.batteryRight, charging = state.rightCharging)
                BatteryItem(label = "Case", level = state.batteryCase, charging = state.caseCharging)
            }
        }
    }
}

@Composable
private fun BatteryItem(
    label: String,
    level: Int,
    charging: Boolean
) {
    val color = when {
        level < 0 -> TextTertiary
        level <= 15 -> BatteryRed
        level <= 40 -> BatteryYellow
        else -> BatteryGreen
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            BatteryIcon(level = level, color = color, modifier = Modifier.size(40.dp, 24.dp))
            if (charging) {
                Icon(
                    Icons.Rounded.BatteryChargingFull,
                    contentDescription = "Charging",
                    tint = ChargingBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = if (level >= 0) "$level%" else "—",
            style = MaterialTheme.typography.titleMedium,
            color = if (level >= 0) TextPrimary else TextTertiary
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun BatteryIcon(level: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.85f
        val tipW = w * 0.08f
        val tipH = h * 0.4f

        // Battery outline
        drawRoundRect(
            color = color.copy(alpha = 0.4f),
            topLeft = Offset.Zero,
            size = Size(bodyW, h),
            cornerRadius = CornerRadius(4f)
        )

        // Battery fill
        val fillFraction = (level.coerceIn(0, 100) / 100f)
        if (fillFraction > 0) {
            drawRoundRect(
                color = color,
                topLeft = Offset(2f, 2f),
                size = Size((bodyW - 4f) * fillFraction, h - 4f),
                cornerRadius = CornerRadius(3f)
            )
        }

        // Battery tip
        drawRoundRect(
            color = color.copy(alpha = 0.4f),
            topLeft = Offset(bodyW, (h - tipH) / 2),
            size = Size(tipW, tipH),
            cornerRadius = CornerRadius(2f)
        )
    }
}
