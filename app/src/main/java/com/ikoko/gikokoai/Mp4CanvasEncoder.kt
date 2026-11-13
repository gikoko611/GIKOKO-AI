package com.ikoko.gikokoai

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Encodes RGB animation frames to an H.264 MP4 using the device's hardware/software MediaCodec. */
internal class Mp4CanvasEncoder(
    private val output: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int
) {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var finished = false

    init {
        require(width % 2 == 0 && height % 2 == 0) { "H.264 dimensions must be even." }
        output.parentFile?.mkdirs()
        val mime = "video/avc"
        val encoderName = MediaCodecListCompat.findEncoder(mime)
        codec = if (encoderName == null) MediaCodec.createEncoderByType(mime) else MediaCodec.createByCodecName(encoderName)
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, 1_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun writeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        check(!finished) { "Encoder already finished." }
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex < 0) throw IllegalStateException("Video encoder input queue is full.")
        val input = codec.getInputBuffer(inputIndex) ?: throw IllegalStateException("Encoder input buffer unavailable.")
        input.clear()
        val bytes = toNv12(bitmap)
        if (input.remaining() < bytes.size) throw IllegalStateException("Encoder input buffer is too small.")
        input.put(bytes)
        codec.queueInputBuffer(inputIndex, 0, bytes.size, presentationTimeUs, 0)
        drain(false)
    }

    fun finish() {
        if (finished) return
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        finished = true
        release()
    }

    fun abort() {
        if (finished) return
        finished = true
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
        output.delete()
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end || idle++ > 30) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IllegalStateException("Encoder output format changed twice.")
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        if (!muxerStarted) throw IllegalStateException("MP4 muxer has not started.")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun release() {
        try { codec.stop() } catch (_: Exception) { }
        try { codec.release() } catch (_: Exception) { }
        if (muxerStarted) try { muxer.stop() } catch (_: Exception) { }
        try { muxer.release() } catch (_: Exception) { }
    }

    private fun toNv12(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(width * height)
        val scaled = if (bitmap.width == width && bitmap.height == height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        val out = ByteArray(width * height * 3 / 2)
        var yIndex = 0
        var uvIndex = width * height
        for (row in 0 until height) {
            for (col in 0 until width) {
                val c = pixels[row * width + col]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIndex++] = y.coerceIn(0, 255).toByte()
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    out[uvIndex++] = u.coerceIn(0, 255).toByte()
                    out[uvIndex++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }
}

/** Queries the codec list without depending on a specific vendor codec name. */
private object MediaCodecListCompat {
    fun findEncoder(mime: String): String? = try {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
            ?.name
    } catch (_: Exception) { null }
}
