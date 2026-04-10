@file:OptIn(ExperimentalMaterial3Api::class)

package com.budcontrol.sony.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.bluetooth.DeviceState
import com.budcontrol.sony.protocol.SonyCommands
import com.budcontrol.sony.ui.components.*
import com.budcontrol.sony.ui.theme.*

@SuppressLint("MissingPermission")
@Composable
fun DashboardScreen(
    state: DeviceState,
    pairedDevices: List<BluetoothDevice>,
    showDevicePicker: Boolean,
    sonyAppInstalled: Boolean,
    accessibilityRunning: Boolean,
    onShowDevicePicker: () -> Unit,
    onDismissDevicePicker: () -> Unit,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onAncMode: (SonyCommands.AncMode) -> Unit,
    onAmbientLevel: (Int) -> Unit,
    onFocusOnVoice: (Boolean) -> Unit,
    onWindReduction: (Boolean) -> Unit,
    onEqPreset: (SonyCommands.EqPreset) -> Unit,
    onSpeakToChat: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val controlsEnabled = state.isConnected || accessibilityRunning

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = SonyBlack,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "BudControl",
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary
                    )
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SonyBlack,
                    scrolledContainerColor = SonyDarkSurface
                ),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Setup card if accessibility isn't enabled
            if (!accessibilityRunning) {
                SetupCard(
                    sonyAppInstalled = sonyAppInstalled,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings
                )
            }

            ConnectionHeader(
                state = state,
                onConnectClick = {
                    if (accessibilityRunning) onRefresh() else onShowDevicePicker()
                },
                onDisconnectClick = onDisconnect
            )

            AncSelector(
                currentMode = state.ancMode,
                enabled = controlsEnabled,
                onModeSelected = onAncMode
            )

            AmbientSlider(
                ambientLevel = state.ambientLevel,
                focusOnVoice = state.focusOnVoice,
                windReduction = state.windReduction,
                ancMode = state.ancMode,
                enabled = controlsEnabled,
                onLevelChange = onAmbientLevel,
                onFocusOnVoiceChange = onFocusOnVoice,
                onWindReductionChange = onWindReduction
            )

            BatteryCard(state = state)

            EqualizerCard(
                currentPreset = state.eqPreset,
                enabled = controlsEnabled,
                onPresetSelected = onEqPreset
            )

            SpeakToChatCard(
                enabled = controlsEnabled,
                speakToChatOn = state.speakToChat,
                onToggle = onSpeakToChat
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDevicePicker) {
        DevicePickerSheet(
            devices = pairedDevices,
            onDeviceSelected = onConnectDevice,
            onDismiss = onDismissDevicePicker
        )
    }
}

@Composable
private fun SetupCard(
    sonyAppInstalled: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Accessibility,
                    contentDescription = null,
                    tint = AmberPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Setup Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                "BudControl works by automating the Sony app. " +
                    "Enable the accessibility service to let BudControl " +
                    "tap buttons in the Sony app for you.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            if (!sonyAppInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = BatteryRed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Sony Sound Connect / Headphones Connect app not found. Install it first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BatteryRed
                    )
                }
            }

            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AmberPrimary)
            ) {
                Text(
                    "Open Accessibility Settings",
                    color = SonyBlack,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                "Find \"BudControl\" in the list and enable it. " +
                    "Only the Sony app is monitored.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DevicePickerSheet(
    devices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SonyDarkSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                "Select Device",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(16.dp))

            if (devices.isEmpty()) {
                Text(
                    "No paired Sony devices found.\nPair your earbuds in Android Bluetooth settings first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                devices.forEach { device ->
                    Card(
                        onClick = { onDeviceSelected(device) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = SonyCardSurface),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.name ?: "Unknown",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    device.address,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextTertiary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
