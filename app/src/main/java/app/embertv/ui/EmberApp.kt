package app.embertv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.embertv.data.nowSeconds
import kotlinx.coroutines.delay

@Composable
fun EmberApp(vm: AppViewModel, isTv: Boolean) {
    EmberTheme {
        val now by produceState(nowSeconds()) {
            while (true) {
                delay(20_000)
                value = nowSeconds()
            }
        }
        CompositionLocalProvider(LocalAppVm provides vm, LocalNow provides now, LocalIsTv provides isTv) {
            Box(Modifier.fillMaxSize().background(Ember.Bg)) {
                // Registered first so every screen's own back handling takes priority.
                BackHandler(enabled = vm.stack.size > 1) { vm.pop() }

                val restoring = vm.restoreFocusKey
                LaunchedEffect(restoring) {
                    if (restoring != null) {
                        delay(1500)
                        if (vm.restoreFocusKey == restoring) vm.restoreFocusKey = null
                    }
                }

                if (vm.repo == null) {
                    if (isTv) LoginScreen(vm) else PhoneLoginScreen(vm)
                } else if (vm.pickingProfile) {
                    ProfilePicker(vm)
                } else {
                    when (val screen = vm.stack.last()) {
                        Screen.Home -> if (isTv) HomeScreen(vm) else PhoneHome(vm)
                        is Screen.MovieDetail -> if (isTv) MovieDetailScreen(vm, screen.movie) else PhoneMovieDetail(vm, screen.movie)
                        is Screen.SeriesDetail -> if (isTv) SeriesDetailScreen(vm, screen.series) else PhoneSeriesDetail(vm, screen.series)
                        is Screen.PlayLive -> LivePlayerScreen(vm, screen.channels, screen.index)
                        is Screen.PlayVod -> VodPlayerScreen(vm, screen.items, screen.index, screen.startMs)
                        is Screen.Catchup -> CatchupScreen(vm, screen.channel)
                    }
                }

                // Programme reminders
                LaunchedEffect(Unit) {
                    while (true) {
                        vm.checkReminders(nowSeconds())
                        delay(15_000)
                    }
                }
                vm.dueReminder?.let { ReminderDialog(vm, it) }

                // TV screensaver after a few idle minutes
                if (isTv) {
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(10_000)
                            vm.checkIdle()
                        }
                    }
                    AnimatedVisibility(visible = vm.screensaver, enter = fadeIn(tween(1200)), exit = fadeOut(tween(300))) {
                        Screensaver(vm)
                    }
                }

                // Sleep timer
                val sleepAt = vm.sleepAt
                LaunchedEffect(sleepAt) {
                    if (sleepAt != null) {
                        delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
                        vm.sleepNow()
                    }
                }

                if (vm.update != null && vm.repo != null) UpdateDialog(vm)

                // Choosing HD / FHD / 4K
                vm.choosingVersion?.let { VersionPickerDialog(vm, it) }

                // "Open in another player" from inside a player
                vm.pickingOtherPlayer?.let { pending ->
                    PlayerPickerDialog(
                        vm = vm,
                        title = "Open in…",
                        subtitle = "Streammania's player closes first so the other app can use your provider connection.",
                        includeBuiltIn = false,
                        onDismiss = { vm.pickingOtherPlayer = null },
                    ) { pkg -> vm.launchElsewhere(pending, pkg) }
                }

                // Followed teams: goal alerts, and "Where's it on?"
                if (vm.repo != null && !vm.pickingProfile) {
                    TeamAlertBanner(vm)
                    vm.findingMatch?.let { MatchFinderDialog(vm, it) }
                }

                NoticeOverlay(vm)
            }
        }
    }
}

@Composable
private fun BoxScope.NoticeOverlay(vm: AppViewModel) {
    val notice = vm.notice
    // Keeps the text on screen while the pill fades out.
    val lastText = remember { arrayOf("") }
    if (notice != null) lastText[0] = notice.text
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2200)
            vm.clearNotice(notice)
        }
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 28.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xEE24242B))
                .padding(horizontal = 22.dp, vertical = 12.dp)
        ) {
            Text(lastText[0], style = Txt.Label, color = Ember.Text)
        }
    }
}

@Composable
private fun ReminderDialog(vm: AppViewModel, r: app.embertv.data.Reminder) {
    val isTv = LocalIsTv.current
    val now = LocalNow.current
    Dialog(onDismissRequest = { vm.dismissReminder() }) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 520.dp else 440.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChannelLogo(r.channelIcon, r.channelName, Modifier.size(width = 96.dp, height = 64.dp))
            Spacer(Modifier.height(16.dp))
            SheetTitle(
                r.title,
                eyebrow = if (r.start > now) "Starting soon" else "Starting now",
                subtitle = "${r.channelName}  ·  ${formatClock(r.start)} – ${formatClock(r.end)}",
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.width(if (isTv) 340.dp else 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetOption("Watch now", focusKey = "reminder:watch", autoFocus = isTv, primary = true) { vm.watchReminder(r) }
                SheetOption("Dismiss", focusKey = "reminder:dismiss") { vm.dismissReminder() }
            }
        }
    }
}

@Composable
private fun UpdateDialog(vm: AppViewModel) {
    val info = vm.update ?: return
    val isTv = LocalIsTv.current
    val progress = vm.updateProgress
    Dialog(onDismissRequest = { if (progress == null) vm.dismissUpdate() }) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 520.dp else 440.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(dialogPanelColor())
                .padding(if (isTv) 8.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetTitle(
                "Update available",
                eyebrow = "Streammania ${info.versionName}",
                subtitle = "You have ${app.embertv.BuildConfig.VERSION_NAME}. Your login, profiles and favourites are kept.",
            )
            Spacer(Modifier.height(24.dp))
            if (progress != null) {
                Text("Downloading… ${(progress * 100).toInt()}%", style = Txt.Label)
                Spacer(Modifier.height(10.dp))
                ProgressBar(progress, Modifier.width(340.dp))
            } else {
                Column(Modifier.width(if (isTv) 340.dp else 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetOption("Install now", focusKey = "update:install", autoFocus = isTv, primary = true) { vm.installUpdate() }
                    SheetOption("Later", focusKey = "update:later") { vm.dismissUpdate() }
                }
            }
        }
    }
}
