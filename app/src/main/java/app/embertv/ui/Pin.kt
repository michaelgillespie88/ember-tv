package app.embertv.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

const val PIN_LENGTH = 4

/**
 * Asks for a 4-digit PIN with an on-screen keypad (D-pad or touch); remote number keys work too.
 * [onPin] gets the digits once all four are in and returns false to reject them and try again.
 * [extra] adds a button under the keypad, such as "Turn off PIN".
 */
@Composable
fun PinDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    extra: Pair<String, () -> Unit>? = null,
    onPin: (String) -> Boolean,
) {
    val isTv = LocalIsTv.current
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    val check by rememberUpdatedState(onPin)

    LaunchedEffect(entered) {
        if (entered.length == PIN_LENGTH) {
            delay(120)
            if (!check(entered)) {
                error = "That PIN isn't right"
                attempt++
                entered = ""
            }
        }
    }
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        for (x in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(x, spring(stiffness = 4000f))
    }

    fun press(digit: Int) {
        if (entered.length < PIN_LENGTH) {
            error = null
            entered += digit.toString()
        }
    }

    fun backspace() {
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogBackdropBlur()
        Column(
            Modifier
                .width(if (isTv) 400.dp else 340.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(dialogPanelColor())
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (val code = ev.nativeKeyEvent.keyCode) {
                        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                            press(code - AndroidKeyEvent.KEYCODE_0)
                            true
                        }
                        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> {
                            press(code - AndroidKeyEvent.KEYCODE_NUMPAD_0)
                            true
                        }
                        AndroidKeyEvent.KEYCODE_DEL -> {
                            backspace()
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 24.dp, vertical = if (isTv) 24.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(EmberIcons.Lock, contentDescription = null, tint = Ember.Dim, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(title, style = Txt.Title, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = Txt.Caption, color = Ember.Dim, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(if (isTv) 16.dp else 20.dp))
            Row(
                Modifier.offset(x = shake.value.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeat(PIN_LENGTH) { i ->
                    val filled = i < entered.length
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(if (filled) Ember.Text else Color.Transparent)
                            .border(1.5.dp, if (error != null) Ember.Live else Ember.Dim, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(error ?: " ", style = Txt.Caption, color = Ember.Live)
            Spacer(Modifier.height(if (isTv) 8.dp else 12.dp))

            val keySize = if (isTv) 50.dp else 68.dp
            val gap = if (isTv) 8.dp else 14.dp
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (k in row) {
                            PinKey(
                                size = keySize,
                                focusKey = "pin:$k",
                                autoFocus = isTv && k == 1,
                                onClick = {
                                    when (k) {
                                        -1 -> onDismiss()
                                        -2 -> backspace()
                                        else -> press(k)
                                    }
                                },
                            ) { focused ->
                                val color = if (focused) Color.Black else Ember.Text
                                when (k) {
                                    -1 -> Text("Cancel", style = Txt.Caption.copy(fontSize = 13.sp), color = if (focused) Color.Black else Ember.Dim)
                                    -2 -> Icon(EmberIcons.Backspace, contentDescription = "Delete", tint = color, modifier = Modifier.size(22.dp))
                                    else -> Text(k.toString(), style = Txt.Title.copy(fontSize = if (isTv) 22.sp else 28.sp), color = color)
                                }
                            }
                        }
                    }
                }
            }
            if (extra != null) {
                Spacer(Modifier.height(if (isTv) 14.dp else 18.dp))
                if (isTv) {
                    TvButton(extra.first, onClick = extra.second)
                } else {
                    PillButton(extra.first, modifier = Modifier.fillMaxWidth(), onClick = extra.second)
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    size: androidx.compose.ui.unit.Dp,
    focusKey: String,
    autoFocus: Boolean,
    onClick: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    FocusBox(
        modifier = Modifier.size(size),
        focusKey = focusKey,
        shape = CircleShape,
        focusedScale = 1.1f,
        color = Color(0x1FFFFFFF),
        focusedColor = Color.White,
        autoFocus = autoFocus,
        onClick = onClick,
    ) { focused ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content(focused) }
    }
}

/**
 * Choosing a new PIN: type it, then type it again. [onSet] gets the confirmed digits.
 * [onTurnOff], when given, offers removing the PIN instead.
 */
@Composable
fun SetPinDialog(onDismiss: () -> Unit, onTurnOff: (() -> Unit)?, onSet: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    val chosen = first
    if (chosen == null) {
        PinDialog(
            title = "Choose a PIN",
            subtitle = if (mismatch) "The PINs didn't match. Try again." else "Four digits you'll remember",
            onDismiss = onDismiss,
            extra = onTurnOff?.let { "Turn off PIN" to it },
        ) { pin ->
            first = pin
            true
        }
    } else {
        // A fresh dialog, so the dots start empty.
        androidx.compose.runtime.key(chosen) {
            PinDialog(title = "Enter it again", subtitle = "To make sure", onDismiss = onDismiss) { pin ->
                if (pin == chosen) {
                    onSet(pin)
                } else {
                    mismatch = true
                    first = null
                }
                true
            }
        }
    }
}
