package com.vector.core.audio

/** Immutable private recording descriptor. Never saved in UI SavedStateHandle or logged. */
data class VoiceClip(val path: String, val duration: Int, val waveform: List<Int>) {
    override fun toString() = "VoiceClip([redacted])"
}

/** Telegram's packed 5-bit, least-significant-bit-first waveform. */
fun packWaveform(samples: List<Int>): ByteArray {
    val limited = samples.take(100)
    val bytes = ByteArray((limited.size * 5 + 7) / 8)
    limited.forEachIndexed { index, amplitude ->
        val value = amplitude.coerceIn(0, 31)
        val bit = index * 5
        for (offset in 0..4) if (value and (1 shl offset) != 0) {
            val at = bit + offset; bytes[at / 8] = (bytes[at / 8].toInt() or (1 shl (at % 8))).toByte()
        }
    }
    return bytes
}
fun unpackWaveform(bytes: ByteArray): List<Int> = (0 until (bytes.size * 8 / 5).coerceAtMost(100)).map { index ->
    var value = 0
    for (offset in 0..4) { val at = index * 5 + offset; if (bytes[at / 8].toInt() and (1 shl (at % 8)) != 0) value = value or (1 shl offset) }
    value
}

/** Compresses measured amplitudes, never invents samples. Silence stays zero. */
fun voiceWaveform(amplitudes: List<Int>): List<Int> {
    if (amplitudes.isEmpty()) return emptyList()
    val count = minOf(100, amplitudes.size)
    val peaks = (0 until count).map { index ->
        amplitudes.subList(index * amplitudes.size / count, ((index + 1) * amplitudes.size / count).coerceAtLeast(index * amplitudes.size / count + 1)).maxOrNull()?.coerceAtLeast(0) ?: 0
    }
    val maximum = peaks.maxOrNull()?.coerceAtLeast(1) ?: 1
    return peaks.map { (it.toLong() * 31 / maximum).toInt().coerceIn(0, 31) }
}

enum class VoiceGestureAction { None, Cancel, Lock, Send }
class VoiceGesture(private val cancelDistance: Float, private val lockDistance: Float) {
    var locked = false; private set
    var cancelled = false; private set
    private var released = false
    fun move(dx: Float, dy: Float): VoiceGestureAction {
        if (cancelled || locked || released) return VoiceGestureAction.None
        if (-dx >= cancelDistance && -dx > -dy) { cancelled = true; return VoiceGestureAction.Cancel }
        if (-dy >= lockDistance && -dy > -dx) { locked = true; return VoiceGestureAction.Lock }
        return VoiceGestureAction.None
    }
    fun release(): VoiceGestureAction {
        if (released) return VoiceGestureAction.None
        released = true
        return if (cancelled || locked) VoiceGestureAction.None else VoiceGestureAction.Send
    }
}
