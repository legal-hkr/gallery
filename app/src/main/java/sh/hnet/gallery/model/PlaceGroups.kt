package sh.hnet.gallery.model

/** A town's items, newest first (SPEC §13.1); its region and country are those of its newest item. */
data class TownGroup(val settlement: Int, val region: Int, val country: Int, val items: List<MediaItem>)

/** A region's towns on a country's page (SPEC §13.1), the most items first; [region] -1 for towns outside every region. */
data class RegionGroup(val region: Int, val towns: List<TownGroup>, val count: Int)

/**
 * A country (SPEC §13.1): its towns, the most items first, and the same towns by region for its page. [count] holds
 * the items placed in the country but far from any town too.
 */
data class CountryGroup(val country: Int, val count: Int, val towns: List<TownGroup>, val regions: List<RegionGroup>)

/** Places (SPEC §13.1, LOGIKA §17.5): the countries, the most items first, and the items without a location. */
class PlaceGroups(val countries: List<CountryGroup>, val noLocation: List<MediaItem>) {
    val townCount: Int get() = countries.sumOf { it.towns.size }

    fun country(index: Int): CountryGroup? = countries.firstOrNull { it.country == index }

    fun town(settlement: Int): TownGroup? = countries.firstNotNullOfOrNull { c -> c.towns.firstOrNull { it.settlement == settlement } }

    companion object {
        /**
         * @param items Newest first, as the library holds them (trashed ones are left out here)
         * @param placeOf An item's place; null when it has no location (or isn't read yet): it goes to "No location"
         */
        fun of(items: List<MediaItem>, placeOf: (Long) -> Place?): PlaceGroups {
            val noLocation = ArrayList<MediaItem>()
            val countryItems = LinkedHashMap<Int, Int>()
            val towns = LinkedHashMap<Int, MutableList<MediaItem>>()
            val townPlace = HashMap<Int, Place>()
            for (item in items) {
                if (item.trashed) continue
                val place = placeOf(item.id)
                if (place == null) {
                    noLocation.add(item)
                    continue
                }
                if (place.country >= 0) countryItems[place.country] = (countryItems[place.country] ?: 0) + 1
                if (place.settlement >= 0 && place.country >= 0) {
                    towns.getOrPut(place.settlement) { ArrayList() }.add(item)
                    townPlace.putIfAbsent(place.settlement, place)
                }
            }
            val byCountry = towns.entries
                .map { (settlement, list) -> townPlace.getValue(settlement).let { TownGroup(settlement, it.region, it.country, list) } }
                .groupBy { it.country }
            val countries = countryItems.map { (country, count) ->
                val list = byCountry[country].orEmpty().sortedWith(townOrder)
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
