package app.embertv

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Many providers' 4K streams are HDR but don't say so, so the Fire TV never switches the TV into
 * HDR and the picture looks very dark. For 4K HEVC with no colour information, this tells the
 * decoder (and so the Fire TV) the picture is HDR: [transfer] picks which kind.
 */
object HdrHint {
    /** C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_ST2084, or C.COLOR_TRANSFER_SDR to leave streams alone. */
    @Volatile var transfer: Int = C.COLOR_TRANSFER_HLG

    /** The setting: "auto" (HLG for channels, HDR10 for films and series), "hlg", "hdr10" or "off". */
    fun choose(setting: String, live: Boolean) {
        transfer = when (setting) {
            "hlg" -> C.COLOR_TRANSFER_HLG
            "hdr10" -> C.COLOR_TRANSFER_ST2084
            "off" -> C.COLOR_TRANSFER_SDR
            else -> if (live) C.COLOR_TRANSFER_HLG else C.COLOR_TRANSFER_ST2084
        }
    }

    internal fun unlabelled4k(format: Format): Boolean {
        val noColour = format.colorInfo?.colorTransfer.let { it == null || it == Format.NO_VALUE }
        val hevc = format.sampleMimeType == MimeTypes.VIDEO_H265 ||
            format.codecs.orEmpty().let { it.startsWith("hvc1") || it.startsWith("hev1") }
        return noColour && hevc && format.height >= 2000
    }

    @OptIn(UnstableApi::class)
    fun renderersFactory(context: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            out.add(HintingVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs, eventHandler, eventListener))
        }
    }
}

@OptIn(UnstableApi::class)
private class HintingVideoRenderer(
    context: Context,
    selector: MediaCodecSelector,
    joiningMs: Long,
    handler: Handler,
    listener: VideoRendererEventListener,
) : MediaCodecVideoRenderer(context, selector, joiningMs, handler, listener, 50) {
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val mf = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate, deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
        val t = HdrHint.transfer
        if (t != C.COLOR_TRANSFER_SDR && HdrHint.unlabelled4k(format)) {
            mf.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            mf.setInteger(MediaFormat.KEY_COLOR_TRANSFER, if (t == C.COLOR_TRANSFER_HLG) MediaFormat.COLOR_TRANSFER_HLG else MediaFormat.COLOR_TRANSFER_ST2084)
            mf.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        return mf
    }
}
