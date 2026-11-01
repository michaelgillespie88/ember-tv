package app.embertv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Small line icons drawn in code, so the app needs no icon library. Icon() tints them. */
object EmberIcons {
    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
            .build()

    private fun solid(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.White), pathBuilder = block)
            .build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        close()
    }

    private fun PathBuilder.star() {
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) 9.5f else 4.2f
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = 12f + radius * cos(angle).toFloat()
            val y = 12.6f + radius * sin(angle).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    val Home = outline("home") {
        moveTo(4f, 10.5f); lineTo(12f, 4f); lineTo(20f, 10.5f)
        moveTo(6f, 9f); lineTo(6f, 20f); lineTo(18f, 20f); lineTo(18f, 9f)
        moveTo(10f, 20f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 20f)
    }

    val Football = outline("football") {
        circle(12f, 12f, 9f)
        moveTo(12f, 8.2f); lineTo(15.4f, 10.6f); lineTo(14.1f, 14.6f); lineTo(9.9f, 14.6f); lineTo(8.6f, 10.6f); close()
        moveTo(12f, 8.2f); lineTo(12f, 3f)
        moveTo(15.4f, 10.6f); lineTo(20.6f, 9.4f)
        moveTo(14.1f, 14.6f); lineTo(17.2f, 19.3f)
        moveTo(9.9f, 14.6f); lineTo(6.8f, 19.3f)
        moveTo(8.6f, 10.6f); lineTo(3.4f, 9.4f)
    }

    val Close = outline("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }

    val Lock = outline("lock") {
        moveTo(6.5f, 11f); lineTo(17.5f, 11f); lineTo(17.5f, 20f); lineTo(6.5f, 20f); close()
        moveTo(8.8f, 11f); lineTo(8.8f, 8f)
        arcTo(3.2f, 3.2f, 0f, false, true, 15.2f, 8f)
        lineTo(15.2f, 11f)
    }

    val Backspace = outline("backspace") {
        moveTo(9f, 6f); lineTo(20f, 6f); lineTo(20f, 18f); lineTo(9f, 18f); lineTo(3.5f, 12f); close()
        moveTo(12f, 9.5f); lineTo(17f, 14.5f); moveTo(17f, 9.5f); lineTo(12f, 14.5f)
    }

    val Check = outline("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }

    val More = solid("more") {
        circle(5.5f, 12f, 1.8f)
        circle(12f, 12f, 1.8f)
        circle(18.5f, 12f, 1.8f)
    }

    val Moon = outline("moon") {
        moveTo(20f, 14.5f)
        arcTo(8.5f, 8.5f, 0f, true, true, 9.5f, 4f)
        arcTo(6.8f, 6.8f, 0f, false, false, 20f, 14.5f)
        close()
    }

    val Live = outline("live") {
        moveTo(3f, 7f); lineTo(21f, 7f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(8f, 3f); lineTo(12f, 7f); lineTo(16f, 3f)
    }

    val Guide = outline("guide") {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(3f, 18f); lineTo(21f, 18f)
        moveTo(8f, 4f); lineTo(8f, 20f)
    }

    val Movies = outline("movies") {
        moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(8f, 4f); lineTo(8f, 20f)
        moveTo(16f, 4f); lineTo(16f, 20f)
        moveTo(4f, 9f); lineTo(8f, 9f)
        moveTo(4f, 15f); lineTo(8f, 15f)
        moveTo(16f, 9f); lineTo(20f, 9f)
        moveTo(16f, 15f); lineTo(20f, 15f)
    }

    val Series = outline("series") {
        moveTo(3f, 15f); lineTo(3f, 4f); lineTo(17f, 4f)
        moveTo(7f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(7f, 19f); close()
    }

    val Star = outline("star") { star() }
    val StarFilled = solid("star-filled") { star() }

    /** Picture zoom: a screen with arrows pointing out to its corners. */
    val Zoom = outline("zoom") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(7f, 9f); lineTo(7f, 12f)
        moveTo(7f, 9f); lineTo(10f, 9f)
        moveTo(17f, 15f); lineTo(17f, 12f)
        moveTo(17f, 15f); lineTo(14f, 15f)
    }

    val Search = outline("search") {
        circle(10.5f, 10.5f, 6.5f)
        moveTo(15.5f, 15.5f); lineTo(20.5f, 20.5f)
    }

    /**
     * A solid gear in the Apple style (like the Settings app): eight soft, rounded teeth and a round
     * hole. Solid so it's easy to spot across a room on a TV.
     */
    val Settings: ImageVector = ImageVector.Builder("settings", 24.dp, 24.dp, 24f, 24f)
        .path(fill = SolidColor(Color.White), pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
            val steps = 192
            val norm = kotlin.math.tanh(2.6)
            for (i in 0 until steps) {
                val a = i * 2.0 * Math.PI / steps
                // tanh of a cosine gives flat-topped teeth with rounded corners.
                val r = 9.2 + 1.55 * kotlin.math.tanh(2.6 * cos(8 * a + Math.PI / 2)) / norm
                val x = (12.0 + r * cos(a)).toFloat()
                val y = (12.0 + r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            // The hole in the middle.
            circle(12f, 12f, 3.6f)
        }
        .build()


    val Back = outline("back") {
        moveTo(20f, 12f); lineTo(5f, 12f)
        moveTo(11f, 5f); lineTo(4f, 12f); lineTo(11f, 19f)
    }

    val ChannelList = outline("channel-list") {
        moveTo(9f, 6f); lineTo(21f, 6f)
        moveTo(9f, 12f); lineTo(21f, 12f)
        moveTo(9f, 18f); lineTo(21f, 18f)
        moveTo(4f, 6f); lineTo(4.5f, 6f)
        moveTo(4f, 12f); lineTo(4.5f, 12f)
        moveTo(4f, 18f); lineTo(4.5f, 18f)
    }

    val ChevronUp = outline("up") { moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f) }
    val ChevronDown = outline("down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }

    val Play = solid("play") { moveTo(7f, 4.5f); lineTo(20f, 12f); lineTo(7f, 19.5f); close() }

    val Pause = solid("pause") {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }

    val Next = solid("next") {
        moveTo(5f, 5f); lineTo(15f, 12f); lineTo(5f, 19f); close()
        moveTo(16f, 5f); lineTo(19f, 5f); lineTo(19f, 19f); lineTo(16f, 19f); close()
    }

    /** Apple-style skip back: an open circle with the arrow at the top. The seconds go in the middle. */
    val SkipBack = outline("skip-back") {
        moveTo(12.5f, 4f)
        arcTo(8f, 8f, 0f, true, true, 5.07f, 8f)
        moveTo(14.5f, 1.5f); lineTo(11.5f, 4f); lineTo(14.5f, 6.5f)
    }

    /** Apple-style skip forward: the mirror of [SkipBack]. */
    val SkipForward = outline("skip-forward") {
        moveTo(11.5f, 4f)
        arcTo(8f, 8f, 0f, true, false, 18.93f, 8f)
        moveTo(9.5f, 1.5f); lineTo(12.5f, 4f); lineTo(9.5f, 6.5f)
    }

    /** Rewind and fast forward: two solid triangles, as on the Apple remote. */
    val Rewind = solid("rewind") {
        moveTo(11.5f, 6f); lineTo(3f, 12f); lineTo(11.5f, 18f); close()
        moveTo(20.5f, 6f); lineTo(12f, 12f); lineTo(20.5f, 18f); close()
    }

    val FastForward = solid("fast-forward") {
        moveTo(12.5f, 6f); lineTo(21f, 12f); lineTo(12.5f, 18f); close()
        moveTo(3.5f, 6f); lineTo(12f, 12f); lineTo(3.5f, 18f); close()
    }

    /** A plain left chevron. */
    val ChevronLeft = outline("chevron-left") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }

    /** A plain right chevron, for "next". */
    val ChevronRight = outline("chevron-right") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }

    /** Play and pause together, for the remote's one play/pause button. */
    val PlayPause = solid("play-pause") {
        moveTo(2.5f, 6f); lineTo(11f, 12f); lineTo(2.5f, 18f); close()
        moveTo(13f, 6f); lineTo(16f, 6f); lineTo(16f, 18f); lineTo(13f, 18f); close()
        moveTo(18.5f, 6f); lineTo(21.5f, 6f); lineTo(21.5f, 18f); lineTo(18.5f, 18f); close()
    }

    /** Three sliders, for picture quality. */
    val Sliders = outline("sliders") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 5f); lineTo(9f, 9f)
        moveTo(15f, 10f); lineTo(15f, 14f)
        moveTo(7f, 15f); lineTo(7f, 19f)
    }

    /** A globe, for the spoken language. */
    val Language = outline("language") {
        circle(12f, 12f, 9f)
        moveTo(12f, 3f)
        arcTo(4f, 9f, 0f, false, true, 12f, 21f)
        arcTo(4f, 9f, 0f, false, true, 12f, 3f)
        moveTo(3f, 12f); lineTo(21f, 12f)
    }

    val Subtitles = outline("subtitles") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(7f, 15f); lineTo(11f, 15f)
        moveTo(13f, 15f); lineTo(17f, 15f)
        moveTo(7f, 11f); lineTo(9f, 11f)
        moveTo(11f, 11f); lineTo(17f, 11f)
    }

    /** A circled exclamation mark, for things that went wrong. */
    val Alert = outline("alert") {
        circle(12f, 12f, 9f)
        moveTo(12f, 7.5f); lineTo(12f, 13f)
        moveTo(12f, 16.4f); lineTo(12f, 16.5f)
    }

    val Restart = outline("restart") {
        moveTo(4f, 12f)
        arcTo(8f, 8f, 0f, true, true, 6.5f, 17.8f)
        moveTo(4f, 7f); lineTo(4f, 12f); lineTo(9f, 12f)
    }

    /** Multi-view: four pictures. */
    val MultiView = outline("multiview") {
        moveTo(3f, 5f); lineTo(11f, 5f); lineTo(11f, 11f); lineTo(3f, 11f); close()
        moveTo(13f, 5f); lineTo(21f, 5f); lineTo(21f, 11f); lineTo(13f, 11f); close()
        moveTo(3f, 13f); lineTo(11f, 13f); lineTo(11f, 19f); lineTo(3f, 19f); close()
        moveTo(13f, 13f); lineTo(21f, 13f); lineTo(21f, 19f); lineTo(13f, 19f); close()
    }

    /** TV: a screen on a stand (Play on TV). */
    val Tv = outline("tv") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 16f); lineTo(3f, 16f); close()
        moveTo(9f, 20f); lineTo(15f, 20f)
        moveTo(12f, 16f); lineTo(12f, 20f)
    }

    /** Remote control. */
    val Remote = outline("remote") {
        moveTo(8f, 2.5f); lineTo(16f, 2.5f); lineTo(16f, 21.5f); lineTo(8f, 21.5f); close()
        circle(12f, 8f, 2f)
        moveTo(10.5f, 13f); lineTo(13.5f, 13f)
        moveTo(10.5f, 16f); lineTo(13.5f, 16f)
    }

    /** Open elsewhere: a box with an arrow leaving its corner. */
    val OpenExternal = outline("open-external") {
        moveTo(11f, 5f); lineTo(5f, 5f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 13f)
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f)
        moveTo(20f, 4f); lineTo(11.5f, 12.5f)
    }

    /** Picture-in-picture: a screen with a small window in the corner. */
    val Pip = outline("pip") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(12.5f, 12f); lineTo(18f, 12f); lineTo(18f, 16f); lineTo(12.5f, 16f); close()
    }

    /** Add: a plus. */
    val Plus = outline("plus") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    /** Full screen: four corners pointing out. */
    val Expand = outline("expand") {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
    }

    /** Leave full screen: four corners pointing in. */
    val Shrink = outline("shrink") {
        moveTo(4f, 9f); lineTo(9f, 9f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(15f, 9f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(15f, 15f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(9f, 15f); lineTo(4f, 15f)
    }

    /** Catch-up: a clock with its hand turning back. */
    val Catchup = outline("catchup") {
        moveTo(4.6f, 9f)
        arcTo(8f, 8f, 0f, true, true, 4f, 12f)
        moveTo(4.6f, 4.8f); lineTo(4.6f, 9f); lineTo(8.8f, 9f)
        moveTo(12f, 8f); lineTo(12f, 12.5f); lineTo(15f, 14.2f)
    }

    /** Back to the start of the programme. */
    val FromStart = solid("from-start") {
        moveTo(5f, 5f); lineTo(7.5f, 5f); lineTo(7.5f, 19f); lineTo(5f, 19f); close()
        moveTo(19f, 5f); lineTo(19f, 19f); lineTo(9f, 12f); close()
    }

    val Bell = outline("bell") {
        moveTo(6f, 16.5f); lineTo(6f, 11f)
        arcTo(6f, 6f, 0f, false, true, 18f, 11f)
        lineTo(18f, 16.5f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close()
        moveTo(10f, 20.5f); lineTo(14f, 20.5f)
    }
}
