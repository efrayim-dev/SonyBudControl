package com.budcontrol.sony.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.budcontrol.sony.ui.theme.*

@Composable
fun WideAreaTapCard(
    enabled: Boolean,
    wideAreaTapOn: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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
            Icon(
                Icons.Rounded.TouchApp,
                contentDescription = "Wide Area Tap",
                tint = if (wideAreaTapOn) AmberPrimary else TextTertiary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Wide Area Tap", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(
                    "Tap near your ear to control",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Switch(
                checked = wideAreaTapOn,
                onCheckedChange = onToggle,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AmberPrimary,
                    checkedTrackColor = AmberDim,
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = DividerColor
                )
            )
        }
    }
}
