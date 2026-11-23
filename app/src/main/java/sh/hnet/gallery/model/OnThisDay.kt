package sh.hnet.gallery.model

import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * "On this day" (SPEC §4.9, LOGIKA §22.2–§22.3): photos taken on today's day and month in earlier years – the day as
 * the timeline reckons it, in the photo's own zone when known (D44) –, from the timeline's sources only (D45), without
 * screenshots or videos (D46). Up to [LIMIT], the years spread: one from each year, newest year first, then the next
 * round. On 28 February of a common year, 29 February of leap years belongs to it too.
 *
 * @param years Every year that has some, newest first
 */
data class OnThisDay(val today: LocalDate, val items: List<Memory>, val years: List<Int>) {
    /** A photo of the card, with the year it was taken (its "years ago"). */
    data class Memory(val item: MediaItem, val year: Int)

    companion object {
        const val LIMIT = 20

        /** The card's photos for [today], or null when there are none. */
        fun of(items: List<MediaItem>, today: LocalDate, zone: ZoneId, offsetMinutes: (Long) -> Int?, screenshot: (MediaItem) -> Boolean): OnThisDay? {
            val byYear = items.asSequence()
                .filter { !it.video && !it.trashed && !screenshot(it) }
                .mapNotNull { item ->
                    val date = Timeline.dateOf(item, zone, offsetMinutes(item.id))
                    if (date.year < today.year && sameDay(date, today)) Memory(item, date.year) else null
                }
                .groupBy { it.year }
            if (byYear.isEmpty()) return null
            val years = byYear.keys.sortedDescending()
            // Each year's newest first, then round by round over the years, newest year first
            val queues = years.map { year -> byYear.getValue(year).sortedByDescending { it.item.taken }.toMutableList() }
            val chosen = ArrayList<Memory>(LIMIT)
            while (chosen.size < LIMIT && queues.any { it.isNotEmpty() }) {
                for (queue in queues) {
                    if (chosen.size == LIMIT) break
                    if (queue.isNotEmpty()) chosen += queue.removeAt(0)
                }
            }
            return OnThisDay(today, chosen, years)
        }

        /** How far around today the widget looks when today has none (SPEC §21.2): a week either way. */
        const val AROUND_DAYS = 7

        /**
         * The spans of time (epoch milliseconds) to ask the media for: around today's day in each earlier year back to
         * [FIRST_YEAR], [AROUND_DAYS] and a day more either way – a day for the zones a photo may have been taken in.
         */
        fun windows(today: LocalDate, zone: ZoneId): List<LongRange> = (today.year - 1 downTo FIRST_YEAR).map { year ->
            val day = dayIn(today, year)
            val from = day.minusDays(AROUND_DAYS + 1L).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(AROUND_DAYS + 2L).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            from..to
        }

        /** Today's day in [year] (28 February for 29 February in a common year). */
        fun dayIn(today: LocalDate, year: Int): LocalDate =
            if (today.monthValue == 2 && today.dayOfMonth == 29 && !Year.isLeap(year.toLong())) LocalDate.of(year, 2, 28) else today.withYear(year)

        /**
         * Photos within [AROUND_DAYS] of today's day in earlier years, today's own left out (the widget's fallback): the
         * same choice of photos as [of], nearest day first.
         */
        fun around(items: List<MediaItem>, today: LocalDate, zone: ZoneId, offsetMinutes: (Long) -> Int?, screenshot: (MediaItem) -> Boolean): List<Memory> =
            items.asSequence()
                .filter { !it.video && !it.trashed && !screenshot(it) }
                .mapNotNull { item ->
                    val date = Timeline.dateOf(item, zone, offsetMinutes(item.id))
                    if (sameDay(date, today)) return@mapNotNull null
                    // Today's day in the photo's year or the years either side, earlier years only: a photo of 30 December
                    // is near 3 January of the next year (review of S11); that day's year tells how long ago
                    (date.year - 1..date.year + 1).filter { it < today.year }
                        .map { year -> year to kotlin.math.abs(ChronoUnit.DAYS.between(dayIn(today, year), date)) }
                        .minByOrNull { it.second }
                        ?.takeIf { it.second <= AROUND_DAYS }
                        ?.let { (year, distance) -> distance to Memory(item, year) }
                }
                .sortedWith(compareBy<Pair<Long, Memory>> { it.first }.thenByDescending { it.second.item.taken })
                .map { it.second }
                .toList()

        private const val FIRST_YEAR = 1970

        /** The same day and month; a leap day counts on 28 February of a year without one. */
        fun sameDay(date: LocalDate, today: LocalDate): Boolean {
            if (date.monthValue == today.monthValue && date.dayOfMonth == today.dayOfMonth) return true
            return !today.isLeapYear && today.monthValue == 2 && today.dayOfMonth == 28 && date.monthValue == 2 && date.dayOfMonth == 29
        }
    }
}

/**
 * What the On this day widget shows (SPEC §21.2): a photo from today's day in an earlier year; else one from around it
 * ([OnThisDay.AROUND_DAYS]); else any photo of the timeline, with its date; else nothing. The choice among them stays
 * the same all day for one widget ([seed]) and changes at midnight.
 */
sealed interface WidgetPick {
    /** The photos a tap opens the viewer over, and the one shown. */
    val shown: List<MediaItem>

    data class Today(override val shown: List<MediaItem>, val index: Int, val yearsAgo: Int) : WidgetPick

    data class Around(override val shown: List<MediaItem>, val index: Int, val yearsAgo: Int) : WidgetPick

    data class AnyDay(val item: MediaItem, val date: LocalDate) : WidgetPick {
        override val shown get() = listOf(item)
    }

    data object Nothing : WidgetPick {
        override val shown get() = emptyList<MediaItem>()
    }

    companion object {
        /**
         * @param near The timeline's photos around today's day in earlier years (those [OnThisDay.windows] found)
         * @param recent Some of the timeline's photos, for the last fallback
         */
        fun of(near: List<MediaItem>, recent: List<MediaItem>, today: LocalDate, zone: ZoneId, offsetMinutes: (Long) -> Int?, screenshot: (MediaItem) -> Boolean, seed: Long): WidgetPick {
            OnThisDay.of(near, today, zone, offsetMinutes, screenshot)?.let { day ->
                val index = Math.floorMod(seed, day.items.size.toLong()).toInt()
                return Today(day.items.map { it.item }, index, today.year - day.items[index].year)
            }
            val around = OnThisDay.around(near, today, zone, offsetMinutes, screenshot)
            if (around.isNotEmpty()) {
                val index = Math.floorMod(seed, around.size.toLong()).toInt()
                return Around(around.map { it.item }, index, today.year - around[index].year)
            }
            val any = recent.filter { !it.video && !it.trashed && !screenshot(it) }
            if (any.isNotEmpty()) {
                val item = any[Math.floorMod(seed, any.size.toLong()).toInt()]
                return AnyDay(item, Timeline.dateOf(item, zone, offsetMinutes(item.id)))
            }
            return Nothing
        }

        /** The day's seed for one widget: the same all day, another tomorrow and for another widget. */
        fun seed(today: LocalDate, widget: Int): Long = today.toEpochDay() * 7919 + widget * 104729L
    }
}
