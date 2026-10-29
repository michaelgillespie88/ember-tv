package app.embertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.embertv.data.credentialsFromLink
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

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
                    "Tip: if you paste a full playlist link into the server box, the username and password are filled in for you.",
                    style = Txt.Body,
                    color = Ember.Faint,
                )
            }
            Spacer(Modifier.width(64.dp))
            Column(Modifier.width(440.dp)) {
                TvTextField(
                    label = "Server address",
                    value = server,
                    onValueChange = { text ->
                        server = text
                        val creds = credentialsFromLink(text)
                        if (creds != null) {
                            if (user.isEmpty()) user = creds.first
                            if (pass.isEmpty()) pass = creds.second
                        }
                    },
                    placeholder = "http://example.com:8080",
                    keyboardType = KeyboardType.Uri,
                    autoFocus = true,
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                TvTextField(
                    label = "Username",
                    value = user,
                    onValueChange = { user = it },
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                )
                TvTextField(
                    label = "Password",
                    value = pass,
                    onValueChange = { pass = it },
                    password = true,
                    imeAction = ImeAction.Done,
                    onImeAction = { vm.login(server, user, pass) },
                )
                val error = vm.loginError
                if (error != null) {
                    Text(error, style = Txt.Body, color = Color(0xFFFF8A80), modifier = Modifier.padding(bottom = 16.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvButton(
                        text = if (vm.loggingIn) "Signing in…" else "Sign in",
                        icon = "→",
                        onClick = { vm.login(server, user, pass) },
                    )
                }
            }
        }
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

@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    keyboardType: KeyboardType = KeyboardType.Text,
    autoFocus: Boolean = false,
    onImeAction: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { requester.requestFocus() }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        if (label.isNotEmpty()) {
            Text(label, style = Txt.Caption, color = if (focused) Ember.Text else Ember.Dim)
            Spacer(Modifier.height(8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Ember.Text, fontSize = 18.sp),
            cursorBrush = SolidColor(Ember.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(shape)
                        .background(if (focused) Ember.CardHi else Ember.Card)
                        .then(if (focused) Modifier.border(2.dp, Ember.Focus, shape) else Modifier)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = TextStyle(color = Ember.Faint, fontSize = 18.sp))
                    }
                    inner()
                }
            },
        )
    }
}
