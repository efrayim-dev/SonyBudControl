package com.budcontrol.sony.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.budcontrol.sony.wear.ui.WearDashboard

class WearMainActivity : ComponentActivity() {

    private lateinit var viewModel: WearViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[WearViewModel::class.java]

        setContent {
            val state by viewModel.state.collectAsState()
            WearDashboard(
                state = state,
                onCycleAnc = viewModel::cycleAnc,
                onSetAnc = viewModel::setAncMode,
                onRefresh = viewModel::refresh
            )
        }
    }
}
