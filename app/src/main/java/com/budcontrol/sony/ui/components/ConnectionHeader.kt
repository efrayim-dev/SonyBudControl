package com.budcontrol.sony.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.bluetooth.ConnectionStatus
import com.budcontrol.sony.bluetooth.DeviceState
import com.budcontrol.sony.ui.theme.*

@Composable
fun ConnectionHeader(
    state: DeviceState,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state.connectionStatus == ConnectionStatus.CONNECTING ||
        state.connectionStatus == ConnectionStatus.RECONNECTING

    val statusColor by animateColorAsState(
        when (state.connectionStatus) {
            ConnectionStatus.CONNECTED -> AncGreen
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> AmberPrimary
            ConnectionStatus.DISCONNECTED -> if (state.lastError != null) BatteryRed else AncOffGray
        },
        label = "statusColor"
    )

    val pulseAlpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = if (isActive) pulseAlpha else 1f))
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.deviceName ?: "No Device",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(Modifier.height(2.dp))

                val statusText = when (state.connectionStatus) {
                    ConnectionStatus.CONNECTED -> "Connected"
                    ConnectionStatus.CONNECTING -> state.lastError ?: "Connecting…"
                    ConnectionStatus.RECONNECTING -> {
                        val attempt = if (state.connectAttempt > 0) " (attempt ${state.connectAttempt})" else ""
                        (state.lastError ?: "Reconnecting…") + attempt
                    }
                    ConnectionStatus.DISCONNECTED -> {
                        state.lastError ?: "Tap to connect"
                    }
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.connectionStatus == ConnectionStatus.DISCONNECTED && state.lastError != null)
                        BatteryRed.copy(alpha = 0.8f) else TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = {
                    if (state.isConnected) onDisconnectClick() else onConnectClick()
                }
            ) {
                Icon(
                    imageVector = when (state.connectionStatus) {
                        ConnectionStatus.CONNECTED -> Icons.Outlined.BluetoothConnected
                        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Icons.Outlined.BluetoothSearching
                        ConnectionStatus.DISCONNECTED -> Icons.Outlined.Bluetooth
                    },
                    contentDescription = "Connection",
                    tint = statusColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
