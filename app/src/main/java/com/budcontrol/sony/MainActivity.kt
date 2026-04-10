package com.budcontrol.sony

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.budcontrol.sony.ui.DashboardScreen
import com.budcontrol.sony.ui.theme.SonyBudControlTheme
import com.budcontrol.sony.viewmodel.SonyViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: SonyViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.autoConnectLast()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[SonyViewModel::class.java]

        requestPermissionsIfNeeded()

        setContent {
            SonyBudControlTheme {
                val state by viewModel.deviceState.collectAsState()
                val pairedDevices by viewModel.pairedDevices.collectAsState()
                val showPicker by viewModel.showDevicePicker.collectAsState()

                DashboardScreen(
                    state = state,
                    pairedDevices = pairedDevices,
                    showDevicePicker = showPicker,
                    onShowDevicePicker = viewModel::showDevicePicker,
                    onDismissDevicePicker = viewModel::dismissDevicePicker,
                    onConnectDevice = viewModel::connectToDevice,
                    onDisconnect = viewModel::disconnect,
                    onAncMode = viewModel::setAncMode,
                    onAmbientLevel = viewModel::setAmbientLevel,
                    onFocusOnVoice = viewModel::setFocusOnVoice,
                    onWindReduction = viewModel::setWindReduction,
                    onEqPreset = viewModel::setEqPreset,
                    onSpeakToChat = viewModel::setSpeakToChat,
                    onRefresh = { viewModel.refreshStatus() }
                )
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.autoConnectLast()
        }
    }
}
