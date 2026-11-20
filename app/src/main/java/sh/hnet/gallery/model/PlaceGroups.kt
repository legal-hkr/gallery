package sh.hnet.gallery.model

/**
 * A town's items, newest first (SPEC §13.1); its region and country are those of its newest item. Items placed in a
 * country but near no town in the data (mountains, the sea, a village outside the extract) are a group of their
 * region, else of their country (owner's phone test of S6b: they counted to "Polska", yet no carousel showed them):
 * [settlement] is then a [PlaceKey] below 0.
 */
data class TownGroup(val settlement: Int, val region: Int, val country: Int, val items: List<MediaItem>) {
    val isTown: Boolean get() = settlement >= 0
}

/** A place's group by one number: a town's index, else its region's or its country's, below 0. */
object PlaceKey {
    private const val COUNTRY_BASE = 100_000

    fun of(place: Place): Int = when {
        place.settlement >= 0 -> place.settlement
        place.region >= 0 -> -2 - place.region
        else -> -COUNTRY_BASE - place.country
    }

    /** The region a key stands for, or -1. */
    fun region(key: Int): Int = if (key < 0 && key > -COUNTRY_BASE) -2 - key else -1

    /** The country a key stands for (a key of the country alone), or -1. */
    fun country(key: Int): Int = if (key <= -COUNTRY_BASE) -COUNTRY_BASE - key else -1
}

/** A region's towns on a country's page (SPEC §13.1), the most items first; [region] -1 for towns outside every region. */
data class RegionGroup(val region: Int, val towns: List<TownGroup>, val count: Int)

/**
 * A country (SPEC §13.1): its towns, the most items first, and the same towns by region for its page. [count] holds
 * the items placed in the country but far from any town too.
 */
data class CountryGroup(val country: Int, val count: Int, val towns: List<TownGroup>, val regions: List<RegionGroup>)

/** Places (SPEC §13.1, LOGIKA §17.5): the countries, the most items first, and the items without a location. */
class PlaceGroups(val countries: List<CountryGroup>, val noLocation: List<MediaItem>) {
    val townCount: Int get() = countries.sumOf { c -> c.towns.count { it.isTown } }

    fun country(index: Int): CountryGroup? = countries.firstOrNull { it.country == index }

    fun town(settlement: Int): TownGroup? = countries.firstNotNullOfOrNull { c -> c.towns.firstOrNull { it.settlement == settlement } }

    companion object {
        /**
         * @param items Newest first, as the library holds them (trashed ones are left out here)
         * @param placeOf An item's place; null when it has no location (or isn't read yet): it goes to "No location"
         */
        fun of(items: List<MediaItem>, placeOf: (Long) -> Place?): PlaceGroups {
            val noLocation = ArrayList<MediaItem>()
            val towns = LinkedHashMap<Int, MutableList<MediaItem>>()
            val townPlace = HashMap<Int, Place>()
            for (item in items) {
                if (item.trashed) continue
                val place = placeOf(item.id)
                if (place == null) {
                    noLocation.add(item)
                    continue
                }
                if (place.country >= 0) {
                    val key = PlaceKey.of(place)
                    towns.getOrPut(key) { ArrayList() }.add(item)
                    townPlace.putIfAbsent(key, place)
                }
            }
            val byCountry = towns.entries
                .map { (settlement, list) -> townPlace.getValue(settlement).let { TownGroup(settlement, it.region, it.country, list) } }
                .groupBy { it.country }
            // A country counts what its towns hold: a town near a border may hold items whose own point lies across it,
            // and counted by their own country they showed under a country with no town (review before v0.1.0)
            val countries = byCountry.map { (country, groups) ->
                val count = groups.sumOf { it.items.size }
                val list = groups.sortedWith(townOrder)
                val regions = list.groupBy { it.region }
                    .map { (region, regionTowns) -> RegionGroup(region, regionTowns, regionTowns.sumOf { it.items.size }) }
                    // Towns outside every region last
                    .sortedWith(compareBy<RegionGroup> { it.region < 0 }.thenByDescending { it.count }.thenBy { it.region })
                CountryGroup(country, count, list, regions)
            }.sortedWith(compareByDescending<CountryGroup> { it.count }.thenBy { it.country })
            return PlaceGroups(countries, noLocation)
        }

        private val townOrder = compareByDescending<TownGroup> { it.items.size }.thenBy { it.settlement }
    }
}
