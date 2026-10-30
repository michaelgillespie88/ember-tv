package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.embertv.OtherPlayer
import app.embertv.OtherPlayers

/**
 * Lists the video apps on this device. With [includeBuiltIn] it's the Settings choice (ticked = current);
 * without, it's a one-off "open this in…" from inside a player.
 */
@Composable
fun PlayerPickerDialog(
    vm: AppViewModel,
    title: String,
    subtitle: String,
    includeBuiltIn: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val isTv = LocalIsTv.current
    val context = LocalContext.current
    val players = remember { OtherPlayers.installed(context) }
    val rows: List<OtherPlayer> = (if (includeBuiltIn) listOf(OtherPlayer("", "Streammania (built-in)")) else emptyList()) + players

    Dialog(onDismissRequest = onDismiss) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 520.dp else 440.dp)
                .heightIn(max = if (isTv) 520.dp else 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle(title, subtitle = subtitle)
            Spacer(Modifier.height(20.dp))
            if (players.isEmpty()) {
                Text(
                    if (isTv) "No other video players are installed. Install VLC from the Amazon Appstore (search for VLC), then come back here."
                    else "No other video players are installed. Install VLC from the Play Store, then come back here.",
                    style = Txt.Body,
                    color = Ember.Dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }
            LazyColumn(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                itemsIndexed(rows, key = { _, p -> p.packageName.ifEmpty { "builtin" } }) { i, p ->
                    val selected = includeBuiltIn && p.packageName == vm.externalPlayer
                    SheetOption(
                        title = p.label,
                        focusKey = "player:${p.packageName}",
                        autoFocus = isTv && (selected || (i == 0 && rows.none { includeBuiltIn && it.packageName == vm.externalPlayer })),
                        leading = { dark ->
                            Icon(
                                if (p.packageName.isEmpty()) EmberIcons.Play else EmberIcons.OpenExternal,
                                contentDescription = null,
                                tint = if (dark) Color.Black else Ember.Dim,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        trailing = if (selected) { dark -> Text("✓", style = Txt.Label, color = if (dark) Color.Black else Ember.Accent) } else null,
                    ) { onPick(p.packageName) }
                }
                item(key = "close") {
                    Spacer(Modifier.height(6.dp))
                    SheetOption("Close", focusKey = "player:close", autoFocus = isTv && rows.isEmpty(), onClick = onDismiss)
                }
            }
            if (!includeBuiltIn && players.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("To always use one, choose it in Settings → Video player.", style = Txt.Caption, color = Ember.Faint)
            }
        }
    }
}
