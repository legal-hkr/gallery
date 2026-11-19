package sh.hnet.gallery.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** The video player's rules as numbers (SPEC §8.2, LOGIKA §14). */
object Playback {
    /** Videos up to this length loop; longer ones stop on their last frame (SPEC §8.2). */
    const val LOOP_MAX_MS = 10_000L

    /** The speeds offered (SPEC §8.2), 1× the default. */
    val SPEEDS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f)

    /** The chrome hides this long after the last touch while a video plays (owner's decision D9; 4 s as the family's short snackbar, the owner after testing 3 s). */
    const val CHROME_HIDE_MS = 4_000L

    fun loops(durationMs: Long): Boolean = durationMs in 1..LOOP_MAX_MS

    /** One frame's length in milliseconds; 24 frames a second when the file doesn't say. */
    fun frameMs(frameRate: Float?): Long {
        val rate = frameRate?.takeIf { it in 1f..1000f } ?: 24f
        return (1000f / rate).toLong().coerceAtLeast(1)
    }

    /** A speed as the chip and the menu write it: "0,25", "1", "1,5" in Polish. */
    fun speedText(speed: Float, locale: Locale): String = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(speed)

    /** Frames a second as Info writes them: "30", "29,97". */
    fun fpsText(rate: Float, locale: Locale): String = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale)).format(rate)

    /**
     * A video's location as MP4 keeps it (ISO 6709, `©xyz` / `location`): "+40.7580-073.9855/",
     * "+40.7580-073.9855+012.000/"; null when it isn't one.
     */
    fun iso6709(text: String?): Pair<Double, Double>? {
        val match = Regex("""^([+-]\d{1,2}(?:\.\d+)?)([+-]\d{1,3}(?:\.\d+)?)""").find(text?.trim() ?: return null) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }
}
