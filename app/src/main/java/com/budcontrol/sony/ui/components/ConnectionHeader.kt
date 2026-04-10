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
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Sync
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
    onReleaseClick: () -> Unit,
    onReconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state.connectionStatus == ConnectionStatus.CONNECTING ||
        state.connectionStatus == ConnectionStatus.RECONNECTING

    val statusColor by animateColorAsState(
        when (state.connectionStatus) {
            ConnectionStatus.CONNECTED -> AncGreen
            ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> AmberPrimary
            ConnectionStatus.RELEASED -> AmbientBlue
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
                    ConnectionStatus.CONNECTED -> {
                        val battery = if (state.batteryAvg >= 0) " • ${state.batteryAvg}%" else ""
                        "Connected$battery"
                    }
                    ConnectionStatus.CONNECTING -> state.lastError ?: "Connecting…"
                    ConnectionStatus.RECONNECTING -> {
                        val attempt = if (state.connectAttempt > 0) " (attempt ${state.connectAttempt})" else ""
                        (state.lastError ?: "Reconnecting…") + attempt
                    }
                    ConnectionStatus.RELEASED -> "Released • Sony app can connect"
                    ConnectionStatus.DISCONNECTED -> {
                        state.lastError ?: "Tap to connect"
                    }
                }

                val isError = state.connectionStatus == ConnectionStatus.DISCONNECTED && state.lastError != null

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) BatteryRed.copy(alpha = 0.8f) else TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when (state.connectionStatus) {
                ConnectionStatus.CONNECTED -> {
                    IconButton(onClick = onReleaseClick) {
                        Icon(
                            Icons.Rounded.LinkOff,
                            contentDescription = "Release to Sony App",
                            tint = AmbientBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = onDisconnectClick) {
                        Icon(
                            Icons.Outlined.BluetoothConnected,
                            contentDescription = "Disconnect",
                            tint = statusColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                ConnectionStatus.RELEASED -> {
                    IconButton(onClick = onReconnectClick) {
                        Icon(
                            Icons.Rounded.Sync,
                            contentDescription = "Reconnect",
                            tint = AncGreen,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> {
                    IconButton(onClick = onDisconnectClick) {
                        Icon(
                            Icons.Outlined.BluetoothSearching,
                            contentDescription = "Cancel",
                            tint = statusColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                ConnectionStatus.DISCONNECTED -> {
                    IconButton(onClick = onConnectClick) {
                        Icon(
                            Icons.Outlined.Bluetooth,
                            contentDescription = "Connect",
                            tint = statusColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}
