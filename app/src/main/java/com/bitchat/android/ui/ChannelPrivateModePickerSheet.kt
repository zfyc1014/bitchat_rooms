package com.bitchat.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette

/**
 * Member picker for in-channel P2P private mode. Lists the current channel's members (unioned
 * with connected mesh peers) and applies the selection immediately without leaving the channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelPrivateModePickerSheet(
    participants: List<String>,
    nicknames: Map<String, String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BitchatBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp),
            ) {
                if (participants.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.channel_private_mode_picker_empty),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                            fontFamily = BitchatFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    items(participants, key = { it }) { peerID ->
                        val displayName = nicknames[peerID] ?: peerID
                        ChannelPrivateModeMemberRow(
                            displayName = displayName,
                            onClick = { onSelect(peerID) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            BitchatSheetTopBar(
                onClose = onDismiss,
                modifier = modifier.align(Alignment.TopCenter),
                title = {
                    BitchatSheetTitle(
                        text = stringResource(R.string.channel_private_mode_picker_title)
                    )
                }
            )
        }
    }
}

@Composable
private fun ChannelPrivateModeMemberRow(
    displayName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme
    val initial = displayName.firstOrNull()?.uppercase() ?: "?"
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(palette.accentOrange.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    fontFamily = BitchatFontFamily,
                    fontWeight = FontWeight.Bold,
                    color = palette.accentOrange,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "@$displayName",
                modifier = Modifier.weight(1f),
                fontFamily = BitchatFontFamily,
                color = colorScheme.onSurface,
                fontSize = 16.sp,
            )
        }
    }
}
