package sh.hnet.gallery.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The grid's steps (SPEC §4.4): two, three, four and five columns grouped by day, then the month view
 * (two columns added by the owner, 19 Nov 2026). One step is remembered for every grid of the app.
 */
enum class GridStep(val columns: Int, val months: Boolean) {
    HUGE(2, false),
    LARGE(3, false),
    MEDIUM(4, false),
    SMALL(5, false),
    MONTHS(7, true);

    companion object {
        val DEFAULT = MEDIUM

        fun of(name: String?): GridStep = entries.firstOrNull { it.name == name } ?: DEFAULT

        /** The step whose column count is nearest to [columns] (a pinch settles on it, SPEC §4.4). */
        fun nearest(columns: Float): GridStep = entries.minBy { kotlin.math.abs(it.columns - columns) }
    }
}

/** One cell or full-width row of a grid: a day's or a month's header, or an item's tile. */
sealed interface TimelineEntry {
    /** A stable key: the same day, month or item keeps its key through every change of the list. */
    val key: String

    /** The month the entry belongs to, for the fast scroller's bubble. */
    val month: YearMonth

    data class Day(val date: LocalDate) : TimelineEntry {
        override val key: String get() = "d$date"
        override val month: YearMonth get() = YearMonth.from(date)
    }

    data class Month(override val month: YearMonth) : TimelineEntry {
        override val key: String get() = "m$month"
    }

    data class Tile(val item: MediaItem, val date: LocalDate) : TimelineEntry {
        override val key: String get() = item.id.toString()
        override val month: YearMonth get() = YearMonth.from(date)
    }
}

/**
 * A grid's entries with headers, and where each item is in them.
 *
 * @param indexOf The entry index of every item's tile, by MediaStore id
 * @param months Grouped by month (the month view), else by day
 */
class Timeline(val entries: List<TimelineEntry>, val indexOf: Map<Long, Int>, val months: Boolean) {
    companion object {
        val EMPTY = Timeline(emptyList(), emptyMap(), months = false)

        /**
         * Groups [items] by the day they were taken – in the zone they were taken in, from the photo's
         * own offset when [offsetMinutes] knows it, else in [zone] (LOGIKA §2.4) – or by month, newest
         * first. Within a day the newest comes first; a day reached twice (travel across zones) is one group.
         * [oldestFirst] turns all of it round (an album's "Oldest first", SPEC §9.4).
         */
        fun build(items: List<MediaItem>, months: Boolean, zone: ZoneId, oldestFirst: Boolean = false, offsetMinutes: (Long) -> Int?): Timeline {
            val newest = items.map { item -> item to dateOf(item, zone, offsetMinutes(item.id)) }
                .sortedWith(compareByDescending<Pair<MediaItem, LocalDate>> { it.second }.thenComparator { a, b -> MediaItem.NEWEST_FIRST.compare(a.first, b.first) })
            val dated = if (oldestFirst) newest.asReversed() else newest
            val entries = ArrayList<TimelineEntry>(dated.size + dated.size / 8 + 1)
            val indexOf = HashMap<Long, Int>(dated.size * 2)
            var lastDay: LocalDate? = null
            var lastMonth: YearMonth? = null
            for ((item, date) in dated) {
                if (months) {
                    val month = YearMonth.from(date)
                    if (month != lastMonth) entries.add(TimelineEntry.Month(month))
                    lastMonth = month
                } else {
                    if (date != lastDay) entries.add(TimelineEntry.Day(date))
                    lastDay = date
                }
                indexOf[item.id] = entries.size
                entries.add(TimelineEntry.Tile(item, date))
            }
            return Timeline(entries, indexOf, months)
        }

        /**
         * A grid without headers (SPEC-albums §9.7, §9.10: by file name, in the order added): the items as given,
         * each its own tile; [date] only feeds the fast scroller.
         */
        fun plain(items: List<MediaItem>, zone: ZoneId): Timeline {
            val entries = ArrayList<TimelineEntry>(items.size)
            val indexOf = HashMap<Long, Int>(items.size * 2)
            for (item in items) {
                indexOf[item.id] = entries.size
                entries.add(TimelineEntry.Tile(item, Instant.ofEpochMilli(item.taken).atZone(zone).toLocalDate()))
            }
            return Timeline(entries, indexOf, months = false)
        }

        /** The day an item was taken on, in its own zone when known. */
        fun dateOf(item: MediaItem, zone: ZoneId, offsetMinutes: Int?): LocalDate {
            val instant = Instant.ofEpochMilli(item.taken)
            return if (offsetMinutes != null) instant.atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toLocalDate() else instant.atZone(zone).toLocalDate()
        }
    }
}

/**
 * The place under each day's header (SPEC §4.3, LOGIKA §17.5): the settlement most of the day's located items come
 * from; exactly two settlements give both, the more frequent first; none, no line. By the day's key.
 */
object DayPlaces {
    fun of(entries: List<TimelineEntry>, settlementOf: (Long) -> Int?): Map<String, IntArray> {
        val result = HashMap<String, IntArray>()
        var day: String? = null
        var counts = HashMap<Int, Int>()
        fun close() {
            val key = day ?: return
            if (counts.isEmpty()) return
            val ranked = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            result[key] = if (ranked.size == 2) intArrayOf(ranked[0].key, ranked[1].key) else intArrayOf(ranked[0].key)
        }
        for (entry in entries) {
            when (entry) {
                is TimelineEntry.Day -> {
                    close()
                    day = entry.key
                    counts = HashMap()
                }
                is TimelineEntry.Tile -> if (day != null) settlementOf(entry.item.id)?.let { counts[it] = (counts[it] ?: 0) + 1 }
                else -> {
                    close()
                    day = null
                }
            }
        }
        close()
        return result
    }
}
