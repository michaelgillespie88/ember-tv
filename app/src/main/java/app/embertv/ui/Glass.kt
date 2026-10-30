package app.embertv.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import app.embertv.data.Category

// ---------------- Frosted glass ----------------
//
// The content behind the glass is recorded into a graphics layer (backdropSource). Each glass
// surface (frosted) redraws the part of that layer it sits over, blurred, then lays a tint and a
// soft highlight on top. Real blur needs Android 12+; older phones get a stronger plain tint.

private val blurSupported = Build.VERSION.SDK_INT >= 31

class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var frame by mutableIntStateOf(0)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marks the content that glass surfaces should show through. Glass must sit outside this. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        val layer = backdrop.layer
        if (!blurSupported || layer.isReleased) {
            drawContent()
            return@drawWithContent
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
        // Let the glass know the content changed (scrolled, loaded…) so it redraws too.
        backdrop.frame++
    }

/**
 * Frosted glass: blurred content from [backdrop], a [tint] and a faint top highlight.
 * With no backdrop (or before Android 12) it falls back to a solid translucent fill.
 */
fun Modifier.frosted(
    backdrop: Backdrop?,
    shape: Shape,
    tint: Color = Color(0x8C101014),
    blurRadius: Dp = 28.dp,
    edge: Boolean = true,
): Modifier = composed {
    var position by remember { mutableStateOf(Offset.Zero) }
    val blurLayer = rememberGraphicsLayer()
    val canBlur = backdrop != null && blurSupported
    this
        .onGloballyPositioned { position = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (canBlur && backdrop != null) {
                @Suppress("UNUSED_VARIABLE") val tick = backdrop.frame
                val source = backdrop.layer
                if (!source.isReleased && !blurLayer.isReleased) {
                    val radius = blurRadius.toPx()
                    blurLayer.renderEffect = BlurEffect(radiusX = radius, radiusY = radius, edgeTreatment = TileMode.Clamp)
                    blurLayer.record {
                        drawRect(Ember.Bg)
                        translate(backdrop.origin.x - position.x, backdrop.origin.y - position.y) {
                            drawLayer(source)
                        }
                    }
                    drawLayer(blurLayer)
                }
                drawRect(tint)
            } else {
                drawRect(tint.copy(alpha = (tint.alpha + 0.35f).coerceAtMost(0.94f)))
            }
            // A soft sheen along the top edge, like light catching glass.
            drawRect(Brush.verticalGradient(listOf(Color(0x14FFFFFF), Color.Transparent), endY = size.height * 0.6f))
        }
        .then(if (edge) Modifier.border(0.6.dp, Color(0x29FFFFFF), shape) else Modifier)
}

/**
 * The glass look for controls that sit on top of content (buttons, chips, player controls):
 * translucent fill, hairline edge and sheen. No blur, so it works anywhere, including over video.
 */
fun Modifier.glassSurface(shape: Shape, fill: Color = Color(0x1FFFFFFF)): Modifier = this
    .clip(shape)
    .background(fill)
    .background(Brush.verticalGradient(listOf(Color(0x1AFFFFFF), Color.Transparent)))
    .border(0.6.dp, Color(0x33FFFFFF), shape)

// ---------------- Dialogs ----------------

/** Panel colour for pop-ups: translucent on phones (the screen behind is blurred), solid on TVs. */
@Composable
fun dialogPanelColor(): Color = if (LocalIsTv.current) Ember.Panel else Color(0xD91A1A1F)

/** Blurs the screen behind a pop-up on phones running Android 12 or newer. Call inside a Dialog. */
@Composable
fun DialogBackdropBlur() {
    if (LocalIsTv.current || !blurSupported) return
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        val attrs = window.attributes
        attrs.blurBehindRadius = 60
        window.attributes = attrs
        window.setDimAmount(0.3f)
    }
}

// ---------------- Phone layout plumbing ----------------

/** Space taken by the glass header and tab bar, so lists can scroll underneath them. */
val LocalContentPadding = staticCompositionLocalOf { PaddingValues(0.dp) }

/** Category chips a tab wants shown in the glass header. */
class ChipsSpec(
    val categories: List<Category>,
    val selectedId: String,
    val state: LazyListState,
    val onSelect: (Category) -> Unit,
)

val LocalHeaderChips = compositionLocalOf<MutableState<ChipsSpec?>?> { null }
