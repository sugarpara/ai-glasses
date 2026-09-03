package com.example.glasses.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.glasses.audio.BluetoothAudioOutput
import com.example.glasses.ui.theme.AppGreen
import com.example.glasses.ui.theme.AppOrange

@Composable
internal fun BluetoothAudioStatusBanner(
    output: BluetoothAudioOutput,
    onRecover: () -> Unit,
    modifier: Modifier = Modifier,
    darkBackground: Boolean = false,
) {
    val accent = if (output.connected) AppGreen else AppOrange
    val contentColor = if (darkBackground) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        color = if (darkBackground) Color.White.copy(alpha = 0.08f) else accent.copy(alpha = 0.12f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Headphones,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = if (output.connected) {
                    output.deviceName?.let { "眼镜音频已连接 · $it" } ?: "眼镜音频已连接"
                } else {
                    "眼镜音频未连接"
                },
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            if (!output.connected) {
                TextButton(onClick = onRecover) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Text("恢复音频", modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }
}
