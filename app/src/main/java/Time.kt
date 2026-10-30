package com.motion

import java.util.Locale

// All time in Motion is an integer number of ticks.
// 254,016,000,000 ticks per second divides exactly into every broadcast
// frame rate (including 29.97 and 23.976) and into microseconds (for Media3).
const val TICKS_PER_SECOND = 254_016_000_000L
const val TICKS_PER_MS = 254_016_000L
const val TICKS_PER_US = 254_016L

data class FrameRate(val num: Int, val den: Int) {
    val fps: Double get() = num.toDouble() / den
    val ticksPerFrame: Long get() = TICKS_PER_SECOND * den / num

    fun label(): String =
        if (den == 1) "$num fps" else String.format(Locale.US, "%.3f fps", fps)

    companion object {
        val FPS_23976 = FrameRate(24000, 1001)
        val FPS_24 = FrameRate(24, 1)
        val FPS_25 = FrameRate(25, 1)
        val FPS_2997 = FrameRate(30000, 1001)
        val FPS_30 = FrameRate(30, 1)
        val FPS_60 = FrameRate(60, 1)
        val ALL = listOf(FPS_23976, FPS_24, FPS_25, FPS_2997, FPS_30, FPS_60)
    }
}

fun secondsToTicks(s: Double): Long = Math.round(s * TICKS_PER_SECOND)

fun ticksToSeconds(t: Long): Double = t.toDouble() / TICKS_PER_SECOND

fun msToTicks(ms: Long): Long = ms * TICKS_PER_MS

fun ticksToMs(t: Long): Long = t / TICKS_PER_MS

fun ticksToUs(t: Long): Long = t / TICKS_PER_US

fun frameOf(t: Long, fr: FrameRate): Long = t / fr.ticksPerFrame

fun ticksOfFrame(frame: Long, fr: FrameRate): Long = frame * fr.ticksPerFrame

fun secondsLabel(t: Long): String = String.format(Locale.US, "%.2fs", ticksToSeconds(t))

// Non-drop-frame display (29.97 uses a nominal 30 frames per counted second).
fun timecode(t: Long, fr: FrameRate): String {
    val tpf = fr.ticksPerFrame
    val totalFrames = if (t < 0L) 0L else t / tpf
    val nominal = Math.round(fr.fps).toInt().coerceAtLeast(1)
    val ff = (totalFrames % nominal).toInt()
    val totalSec = totalFrames / nominal
    val ss = (totalSec % 60).toInt()
    val mm = ((totalSec / 60) % 60).toInt()
    val hh = (totalSec / 3600).toInt()
    return String.format(Locale.US, "%02d:%02d:%02d:%02d", hh, mm, ss, ff)
}
