package sh.hnet.gallery.model

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the Info sheet writes the camera's values (SPEC §7.1: "f/2,8, 1/250 s, ISO 50, 113 mm") in the app's language. */
object ExifText {
    private fun decimal(locale: Locale, pattern: String) = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale))

    /** f/1,8 – one decimal, none when whole (f/4). */
    fun aperture(fNumber: Double, locale: Locale): String = "f/" + decimal(locale, "0.#").format(fNumber)

    /** 1/250 s below a second, 2,5 s above. */
    fun exposure(seconds: Double, locale: Locale): String {
        if (seconds <= 0.0) return ""
        if (seconds >= 1.0) return decimal(locale, "0.#").format(seconds) + " s"
        // From 0.3 s, as a decimal: 0.8 s was "1/1 s" and 0.6 s "1/2 s"
        if (seconds >= 0.3) return decimal(locale, "0.#").format(seconds) + " s"
        val denominator = (1.0 / seconds).roundToLong()
        return "1/$denominator s"
    }

    fun iso(value: Int): String = "ISO $value"

    /** 2,6 (seconds of a motion photo's video, without the unit). */
    fun seconds(value: Double, locale: Locale): String = decimal(locale, "0.#").format(value)

    /** 113 mm – whole millimetres, one decimal below 10 (4,4 mm). */
    fun focalLength(mm: Double, locale: Locale): String = (if (mm < 10) decimal(locale, "0.#").format(mm) else mm.roundToInt().toString()) + " mm"

    /** 12,5 (megapixels of a width × height). */
    fun megapixels(width: Int, height: Int, locale: Locale): String = decimal(locale, "0.#").format(width.toLong() * height / 1_000_000.0)

    /** A coordinate's number for `format_coordinates`: five decimals, the hemisphere apart (49,29920 and N). */
    fun coordinate(value: Double, locale: Locale): String = decimal(locale, "0.00000").format(abs(value))

    /**
     * An EXIF date and its offset for `DateTimeOriginal` / `OffsetTimeOriginal` (LOGIKA §13.4):
     * "2026:11:19 14:32:00" and "+01:00".
     */
    fun exifDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, offsetMinutes: Int): Pair<String, String> {
        val date = "%04d:%02d:%02d %02d:%02d:00".format(Locale.ROOT, year, month, day, hour, minute)
        val sign = if (offsetMinutes < 0) "-" else "+"
        val minutes = abs(offsetMinutes)
        return date to "%s%02d:%02d".format(Locale.ROOT, sign, minutes / 60, minutes % 60)
    }
}
