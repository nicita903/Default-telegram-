package com.vector.messenger

import android.media.*
import java.io.File

/** Small local fixtures only. No external media, network, account or session data. */
internal fun writeSyntheticVideo(file: File) {
    val codec = MediaCodec.createEncoderByType("video/avc")
    val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var muxing = false
    try {
        val format = MediaFormat.createVideoFormat("video/avc", 128, 128).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        var frame = 0; var track = -1; var done = false
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!done && System.nanoTime() < deadline) {
            if (frame <= 30) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    if (frame == 30) codec.queueInputBuffer(input, 0, 0, frame * 100_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else {
                        val buffer = requireNotNull(codec.getInputBuffer(input)); buffer.clear()
                        repeat(128 * 128) { buffer.put(80.toByte()) }
                        repeat(128 * 128 / 2) { buffer.put(128.toByte()) }
                        codec.queueInputBuffer(input, 0, 128 * 128 * 3 / 2, frame * 100_000L, 0)
                    }
                    frame++
                }
            }
            when (val output = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true }
                else -> if (output >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(output))
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        check(muxing); muxer.writeSampleData(track, buffer, info)
                    }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
        }
        check(done && muxing) { "Synthetic encoder did not complete" }
    } finally {
        runCatching { codec.stop() }; codec.release()
        if (muxing) muxer.stop()
        muxer.release()
    }
}

internal fun writeSyntheticGif(file: File) {
    val hex = "47494638396101000100800000000000ffffff" +
        "21ff0b4e45545343415045322e300301000000" +
        "21f904000a0000002c0000000001000100000202440100" +
        "21f904000a0000002c00000000010001000002024c01003b"
    file.writeBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
}
