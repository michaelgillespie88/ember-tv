package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.credentialsFromLink
import kotlinx.coroutines.delay

private const val SERVER = 0
private const val USER = 1
private const val PASS = 2
private const val SIGN_IN = 3

@Composable
fun LoginScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }
    var focusNext by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(4) { FocusRequester() } }

    // After a pop-up editor closes, move on to the next box (or the Sign in button).
    LaunchedEffect(focusNext) {
        val target = focusNext ?: return@LaunchedEffect
        delay(200)
        runCatching { requesters[target].requestFocus() }
        focusNext = null
    }

    fun finishEditing(next: Int) {
        editing = null
        focusNext = next
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A1408), Ember.Bg), radius = 1400f))
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Wordmark(large = true)
                Spacer(Modifier.height(28.dp))
                Text("Sign in to your IPTV service", style = Txt.Title)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Enter the Xtream Codes details from your provider: a server address, a username and a password. " +
                        "They're saved on this TV only.",
                    style = Txt.Body,
                    color = Ember.Dim,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Use ▲ ▼ to pick a box and press OK to type in it.",
                    style = Txt.Body,
                    color = Ember.Faint,
                )
                Spacer(Modifier.height(28.dp))
                Text("Version ${app.embertv.BuildConfig.VERSION_NAME}", style = Txt.Caption, color = Ember.Faint)
            }
            Spacer(Modifier.width(64.dp))
            Column(Modifier.width(440.dp)) {
                EntryRow(
                    label = "Server address",
                    value = server,
                    placeholder = "http://example.com:8080",
                    autoFocus = true,
                    requester = requesters[SERVER],
                    onClick = { editing = SERVER },
                )
                EntryRow(
                    label = "Username",
                    value = user,
                    requester = requesters[USER],
                    onClick = { editing = USER },
                )
                EntryRow(
                    label = "Password",
                    value = pass,
                    masked = true,
                    requester = requesters[PASS],
                    onClick = { editing = PASS },
                )
                val error = vm.loginError
                if (error != null) {
                    Text(error, style = Txt.Body, color = Color(0xFFFF8A80), modifier = Modifier.padding(bottom = 16.dp))
                }
                Spacer(Modifier.height(6.dp))
                TvButton(
                    text = if (vm.loggingIn) "Signing in…" else "Sign in",
                    icon = "→",
                    requester = requesters[SIGN_IN],
                    onClick = { vm.login(server, user, pass) },
                )
            }
        }
    }

    when (editing) {
        SERVER -> TextEntryDialog(
            title = "Server address",
            value = server,
            onValueChange = { text ->
                server = text
                // A pasted playlist link carries the username and password too.
                val creds = credentialsFromLink(text)
                if (creds != null) {
                    if (user.isEmpty()) user = creds.first
                    if (pass.isEmpty()) pass = creds.second
                }
            },
            placeholder = "http://example.com:8080",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            onDone = { finishEditing(if (user.isNotEmpty() && pass.isNotEmpty()) SIGN_IN else USER) },
        )
        USER -> TextEntryDialog(
            title = "Username",
            value = user,
            onValueChange = { user = it },
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            onDone = { finishEditing(PASS) },
        )
        PASS -> TextEntryDialog(
            title = "Password",
            value = pass,
            onValueChange = { pass = it },
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            onDone = { finishEditing(SIGN_IN) },
        )
    }
}

@Composable
fun Wordmark(large: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(if (large) 22.dp else 14.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFFFC27A), Ember.Accent, Ember.AccentDeep)))
        )
        Spacer(Modifier.width(if (large) 12.dp else 9.dp))
        Text(
            "ember",
            fontSize = if (large) 34.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            color = Ember.Text,
        )
    }
}
