package com.bitchat.android.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.nostr.CustomRelayStore
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.ui.theme.BitchatFontFamily

/**
 * Manage user-added third-party relay servers. Each entry is persisted and injected
 * into the process-wide relay manager so a self-hosted bitChat server replicates
 * channel (kind 42 / 20000 / 20001) and DM delivery.
 */
@Composable
fun CustomRelaySection() {
    val colorScheme = MaterialTheme.colorScheme
    val relayManager = remember { NostrRelayManager.shared }
    val relays by relayManager.relays.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    // Re-read the custom set whenever the relay list state changes (add/remove
    // both trigger updateRelaysList()).
    val customUrls = remember(relays) { relayManager.customRelays().sorted() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.SettingsEthernet,
                    contentDescription = null,
                    tint = Color(0xFF007AFF)
                )
                Text(
                    "Third-party relay",
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                "Connect a self-hosted bitChat relay to replicate channel delivery on your own server.",
                fontFamily = BitchatFontFamily,
                fontSize = 11.sp,
                color = colorScheme.onSurface.copy(alpha = 0.7f)
            )

            if (customUrls.isEmpty()) {
                Text(
                    "No third-party relays configured.",
                    fontFamily = BitchatFontFamily,
                    fontSize = 11.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                customUrls.forEach { url ->
                    val status = relays.firstOrNull { it.url == url }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                url,
                                fontFamily = BitchatFontFamily,
                                fontSize = 12.sp
                            )
                            val connected = status?.isConnected == true
                            Text(
                                if (connected) "Connected" else "Disconnected",
                                fontFamily = BitchatFontFamily,
                                fontSize = 11.sp,
                                color = if (connected) Color(0xFF00C851) else colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        TextButton(onClick = {
                            relayManager.removeCustomRelay(url)
                            CustomRelayStore.remove(url)
                            feedback = "Removed $url"
                        }) {
                            Text("Remove", fontFamily = BitchatFontFamily)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Relay URL", fontFamily = BitchatFontFamily) },
                placeholder = { Text("ws://your-server:8080", fontFamily = BitchatFontFamily) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    val normalized = relayManager.addCustomRelay(input)
                    if (normalized != null) {
                        CustomRelayStore.add(normalized)
                        feedback = "Added $normalized"
                        input = ""
                    } else {
                        feedback = "Invalid relay URL"
                    }
                }) {
                    Text("Add relay", fontFamily = BitchatFontFamily)
                }
            }

            feedback?.let {
                Text(
                    it,
                    fontFamily = BitchatFontFamily,
                    fontSize = 11.sp,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
