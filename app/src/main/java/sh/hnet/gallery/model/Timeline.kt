package sh.hnet.gallery.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The grid's steps (SPEC §4.4): three, four and five columns grouped by day, then the month view.
 * One step is remembered for every grid of the app.
 */
enum class GridStep(val columns: Int, val months: Boolean) {
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
         */
        fun build(items: List<MediaItem>, months: Boolean, zone: ZoneId, offsetMinutes: (Long) -> Int?): Timeline {
            val dated = items.map { item -> item to dateOf(item, zone, offsetMinutes(item.id)) }
                .sortedWith(compareByDescending<Pair<MediaItem, LocalDate>> { it.second }.thenComparator { a, b -> MediaItem.NEWEST_FIRST.compare(a.first, b.first) })
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

        /** The day an item was taken on, in its own zone when known. */
        fun dateOf(item: MediaItem, zone: ZoneId, offsetMinutes: Int?): LocalDate {
            val instant = Instant.ofEpochMilli(item.taken)
            return if (offsetMinutes != null) instant.atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toLocalDate() else instant.atZone(zone).toLocalDate()
        }
    }
}
