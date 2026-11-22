package sh.hnet.gallery.model

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A video's trim (SPEC §15.7, LOGIKA §20.6): the part kept, from [startMs] to [endMs] of a video [durationMs] long. The
 * kept part is never shorter than [MIN_MS] (or the whole video, when that is shorter).
 */
data class Trim(val startMs: Long, val endMs: Long, val durationMs: Long) {
    /** Anything cut off. */
    val changed: Boolean get() = startMs > 0 || endMs < durationMs

    val lengthMs: Long get() = endMs - startMs

    /** The start moved to [ms], kept before the end by the shortest part and within the video. */
    fun withStart(ms: Long): Trim = copy(startMs = ms.coerceIn(0, (endMs - shortest).coerceAtLeast(0)))

    /** The end moved to [ms], kept after the start by the shortest part and within the video. */
    fun withEnd(ms: Long): Trim = copy(endMs = ms.coerceIn((startMs + shortest).coerceAtMost(durationMs), durationMs))

    private val shortest: Long get() = minOf(MIN_MS, durationMs)

    companion object {
        const val MIN_MS = 1_000L

        /** The whole video: nothing cut. */
        fun whole(durationMs: Long) = Trim(0, durationMs, durationMs)

        /** A place on the strip [width] wide to the time there. */
        fun msAt(x: Float, width: Float, durationMs: Long): Long =
            if (width <= 0f) 0L else (x / width * durationMs).toLong().coerceIn(0, durationMs)

        /** A time to its place on the strip. */
        fun xOf(ms: Long, width: Float, durationMs: Long): Float = if (durationMs <= 0) 0f else ms.toFloat() / durationMs * width

        /** The times of the strip's [count] frames: each slot's middle. */
        fun frameTimes(durationMs: Long, count: Int): List<Long> = (0 until count).map { (durationMs * (2 * it + 1)) / (2L * count) }

        /**
         * A time to the tenth of a second (SPEC §15.7), rounded: "0:03,2" in Polish, "0:03.2" in English; hours when
         * the video has them ("1:02:03.4").
         */
        fun timeText(ms: Long, locale: Locale): String {
            val tenths = (ms.coerceAtLeast(0) + 50) / 100
            val seconds = tenths / 10
            val tenth = tenths % 10
            val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            val h = seconds / 3600
            val m = (seconds / 60) % 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d%c%d".format(Locale.ROOT, h, m, s, separator, tenth)
            else "%d:%02d%c%d".format(Locale.ROOT, m, s, separator, tenth)
        }
    }
}
