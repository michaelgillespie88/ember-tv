package app.embertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.embertv.data.FoundTv

/** On the TV: the code a phone has to type in to pair. */
@Composable
fun TvPairingDialog(vm: AppViewModel) {
    val (phone, code) = vm.tvPairing ?: return
    Dialog(onDismissRequest = { vm.cancelTvPairing() }) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(EmberIcons.Tv, contentDescription = null, tint = Ember.Text, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(14.dp))
            SheetTitle(
                "Pair with $phone",
                eyebrow = "Play on TV",
                subtitle = "Type this code on your phone. It can then send things to this TV and work as its remote.",
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (digit in code) {
                    Box(
                        Modifier.size(width = 72.dp, height = 88.dp).glassSurface(RoundedCornerShape(16.dp), Color(0x1FFFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digit.toString(), style = Txt.LargeTitle.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold), color = Ember.Text)
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Column(Modifier.width(340.dp)) {
                SheetOption("Cancel", focusKey = "pair:cancel", autoFocus = true) { vm.cancelTvPairing() }
            }
        }
    }
}

/** On a phone: choose where things play, and pair with a TV. */
@Composable
fun CastSheet(vm: AppViewModel) {
    DisposableEffect(Unit) {
        vm.startFindingTvs()
        onDispose { vm.stopFindingTvs() }
    }
    var typing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    val target = vm.castTarget
    // Paired TVs first (even when not seen yet), then new ones found on the Wi-Fi.
    val paired = vm.pairedTvs.toList()
    val fresh = vm.foundTvs.filter { tv -> paired.none { it.tv.name == tv.name } }
    Dialog(onDismissRequest = { vm.showCastSheet = false }) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(440.dp)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle(
                "Play on",
                eyebrow = "Play on TV",
                subtitle = "Open Streammania on your TV, on the same Wi-Fi. Pick it here and anything you play goes to the TV.",
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetOption(
                    "This phone",
                    focusKey = "cast:phone",
                    leading = { dark -> Icon(EmberIcons.Home, null, tint = if (dark) Color.Black else Ember.Text, modifier = Modifier.size(22.dp)) },
                    trailing = { dark -> if (target == null) Icon(EmberIcons.Check, null, tint = if (dark) Color.Black else Ember.Text, modifier = Modifier.size(20.dp)) },
                ) {
                    vm.chooseTv(null)
                    vm.showCastSheet = false
                }
                for (p in paired) {
                    val seen = vm.foundTvs.any { it.name == p.tv.name }
                    SheetOption(
                        p.tv.name,
                        focusKey = "cast:${p.tv.name}",
                        subtitle = if (seen) "Paired · on this Wi-Fi" else "Paired · not seen right now",
                        leading = { dark -> Icon(EmberIcons.Tv, null, tint = if (dark) Color.Black else Ember.Text, modifier = Modifier.size(22.dp)) },
                        trailing = { dark ->
                            if (target?.tv?.name == p.tv.name) {
                                Icon(EmberIcons.Check, null, tint = if (dark) Color.Black else Ember.Text, modifier = Modifier.size(20.dp))
                            } else {
                                Text(
                                    "Forget",
                                    style = Txt.Caption,
                                    color = if (dark) Color(0xFF555555) else Ember.Dim,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.forgetTv(p) }.padding(6.dp),
                                )
                            }
                        },
                    ) {
                        vm.chooseTv(p.tv)
                        vm.showCastSheet = false
                    }
                }
                for (tv in fresh) {
                    SheetOption(
                        tv.name,
                        focusKey = "cast:new:${tv.name}",
                        subtitle = "Tap to pair",
                        leading = { dark -> Icon(EmberIcons.Tv, null, tint = if (dark) Color.Black else Ember.Dim, modifier = Modifier.size(22.dp)) },
                    ) {
                        vm.chooseTv(tv)
                        vm.showCastSheet = false
                    }
                }
                if (fresh.isEmpty()) {
                    Text(
                        "Looking for TVs on your Wi-Fi…",
                        style = Txt.Caption,
                        color = Ember.Faint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
                if (target != null) {
                    SheetOption(
                        "Remote control",
                        focusKey = "cast:remote",
                        primary = true,
                        leading = { _ -> Icon(EmberIcons.Remote, null, tint = Color.Black, modifier = Modifier.size(22.dp)) },
                    ) {
                        vm.showCastSheet = false
                        vm.showRemote = true
                    }
                }
                SheetOption("Enter the TV's address", focusKey = "cast:manual", subtitle = "If your TV doesn't show up") { typing = true }
            }
        }
    }
    if (typing) {
        TextEntryDialog(
            title = "TV address (shown in the TV's Settings)",
            value = address,
            onValueChange = { address = it },
            placeholder = "192.168.1.20:8771",
            onDone = {
                typing = false
                if (address.isNotBlank()) {
                    vm.addTvByAddress(address)
                    vm.showCastSheet = false
                }
            },
        )
    }
}

/** On a phone: type in the code the TV is showing. */
@Composable
fun PairCodeDialog(vm: AppViewModel, tv: FoundTv) {
    PinDialog(
        title = "Enter the code on ${tv.name}",
        subtitle = "Your TV is showing a 4-digit code",
        onDismiss = { vm.cancelPairing() },
    ) { code ->
        vm.enterPairingCode(code)
        true
    }
}

/** On a phone: a remote for the TV it's paired with, in the style of the Apple TV remote. */
@Composable
fun RemoteScreen(vm: AppViewModel) {
    val target = vm.castTarget
    BackHandler { vm.showRemote = false }
    if (target == null) {
        vm.showRemote = false
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Ember.Bg)
            // The same warm glow as the rest of the app, behind tinted glass buttons.
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF2A1810), Color.Transparent), radius = 1400f))
            .clickable(enabled = false) {},
    ) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("REMOTE", style = Txt.Caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp), color = Ember.Dim)
                    Text(target.tv.name, style = Txt.LargeTitle, maxLines = 1)
                }
                IconCircleButton(EmberIcons.Close, "Close", glass = true) { vm.showRemote = false }
            }
            Spacer(Modifier.weight(1f))
            DPad(onPress = vm::pressRemote)
            Spacer(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteRound("back", onPress = vm::pressRemote) { Icon(EmberIcons.Back, "Back", tint = Ember.Text, modifier = Modifier.size(28.dp)) }
                RemoteRound("home", onPress = vm::pressRemote) { Icon(EmberIcons.Home, "Home", tint = Ember.Text, modifier = Modifier.size(28.dp)) }
                RemoteRound("playpause", onPress = vm::pressRemote) {
                    Icon(EmberIcons.PlayPause, "Play or pause", tint = Ember.Text, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteRound("rewind", onPress = vm::pressRemote) { Icon(EmberIcons.Rewind, "Rewind", tint = Ember.Text, modifier = Modifier.size(26.dp)) }
                ChannelRocker(onPress = vm::pressRemote)
                RemoteRound("forward", onPress = vm::pressRemote) { Icon(EmberIcons.FastForward, "Fast forward", tint = Ember.Text, modifier = Modifier.size(26.dp)) }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Streammania has to be open on the TV",
                style = Txt.Caption,
                color = Ember.Faint,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DPad(onPress: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    fun press(key: String) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onPress(key)
    }
    Box(
        Modifier.size(280.dp).glassSurface(CircleShape, Color(0x1AFFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        // Four arrow pads around the edge.
        PadArrow(Alignment.TopCenter, 0f) { press("up") }
        PadArrow(Alignment.BottomCenter, 180f) { press("down") }
        PadArrow(Alignment.CenterStart, -90f) { press("left") }
        PadArrow(Alignment.CenterEnd, 90f) { press("right") }
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        Box(
            Modifier
                .size(120.dp)
                // The white select button, the same as the OK shown in the app's hints.
                .clip(CircleShape)
                .background(if (pressed) Color(0xFFD8D8DE) else Color(0xF2FFFFFF))
                .clickable(interactionSource = source, indication = null) { press("ok") },
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", style = Txt.Label.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = Color.Black)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PadArrow(align: Alignment, angle: Float, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .align(align)
            .size(80.dp)
            .clip(CircleShape)
            .background(if (pressed) Color(0x22FFFFFF) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(EmberIcons.ChevronUp, null, tint = Ember.Dim, modifier = Modifier.size(30.dp).rotate(angle))
    }
}

@Composable
private fun RemoteRound(key: String, size: Dp = 68.dp, onPress: (String) -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .glassSurface(CircleShape, if (pressed) Color(0x40FFFFFF) else Color(0x1AFFFFFF))
            .clickable(interactionSource = source, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPress(key)
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ChannelRocker(onPress: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.width(68.dp).glassSurface(RoundedCornerShape(34.dp), Color(0x1AFFFFFF)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for ((key, icon) in listOf("chup" to EmberIcons.ChevronUp, "chdown" to EmberIcons.ChevronDown)) {
            val source = remember(key) { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(if (pressed) Color(0x26FFFFFF) else Color.Transparent)
                    .clickable(interactionSource = source, indication = null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPress(key)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (key == "chdown") {
                    Text("CH", style = Txt.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Ember.Faint, modifier = Modifier.align(Alignment.TopCenter))
                }
                Icon(icon, null, tint = Ember.Text, modifier = Modifier.size(26.dp))
            }
        }
    }
}
