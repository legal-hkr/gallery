package sh.hnet.gallery.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** Where a photo was taken (LOGIKA §17.4): indexes into [PlaceData], each -1 when unknown. */
data class Place(val settlement: Int, val region: Int, val country: Int) {
    val known: Boolean get() = settlement >= 0 || region >= 0 || country >= 0

    companion object {
        val NONE = Place(-1, -1, -1)
    }
}

/** Which of a place's three names (LOGIKA §17.4): the app's language first, then the local name, then English. */
enum class PlaceLanguage { POLISH, ENGLISH }

/**
 * The place data built by `tools/places/build.py` (its docstring has the layout; LOGIKA §17.3), read in place from a
 * buffer – mapped from the unpacked file on the phone, so nothing but the names asked for is decoded.
 */
class PlaceData(buffer: ByteBuffer) {
    private val data: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    val version: Int
    private val stringCount: Int
    val countryCount: Int
    val regionCount: Int
    val settlementCount: Int
    private val cellCount: Int
    private val stringsAt: Int
    private val countriesAt: Int
    private val regionsAt: Int
    private val cellsAt: Int
    private val settlementsAt: Int
    private val bordersAt: Int
    private val grid: Int
    private val stringBytesAt: Int
    private val names = HashMap<Int, String>()
    private val shapes = object : LinkedHashMap<Long, Border>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Border>?): Boolean = size > 64
    }

    init {
        require(data.capacity() >= HEADER && data.getInt(0) == MAGIC) { "Not place data" }
        require(data.getShort(4).toInt() == FORMAT) { "Unknown format" }
        version = data.getInt(8)
        stringCount = data.getInt(12)
        countryCount = data.getInt(16)
        regionCount = data.getInt(20)
        settlementCount = data.getInt(24)
        cellCount = data.getInt(28)
        stringsAt = data.getInt(32)
        countriesAt = data.getInt(36)
        regionsAt = data.getInt(40)
        cellsAt = data.getInt(44)
        settlementsAt = data.getInt(48)
        bordersAt = data.getInt(52)
        grid = data.getInt(56)
        stringBytesAt = stringsAt + (stringCount + 1) * 4
    }

    @Synchronized
    fun string(index: Int): String = names.getOrPut(index) {
        val from = data.getInt(stringsAt + index * 4)
        val to = data.getInt(stringsAt + (index + 1) * 4)
        val bytes = ByteArray(to - from)
        data.slice(stringBytesAt + from, to - from).get(bytes)
        String(bytes, Charsets.UTF_8)
    }

    /** Three name indexes at [at], [width] bytes each: Polish, English, local. */
    private fun nameIndexes(at: Int, width: Int): IntArray = IntArray(3) { i -> if (width == 4) data.getInt(at + i * 4) else u24(at + i * 3) }

    private fun u24(at: Int): Int = (data.get(at).toInt() and 0xFF) or ((data.get(at + 1).toInt() and 0xFF) shl 8) or ((data.get(at + 2).toInt() and 0xFF) shl 16)

    private fun pickName(names: IntArray, language: PlaceLanguage): String {
        val first = string(if (language == PlaceLanguage.POLISH) names[0] else names[1])
        return first.ifEmpty { string(names[2]).ifEmpty { string(names[1]) } }
    }

    /** All three names of a place (search matches any of them, SPEC §12.3). */
    private fun allNames(names: IntArray): List<String> = names.distinct().map(::string).filter { it.isNotEmpty() }

    fun settlementName(index: Int, language: PlaceLanguage): String = pickName(nameIndexes(settlementAt(index) + 8, 3), language)

    fun regionName(index: Int, language: PlaceLanguage): String = pickName(nameIndexes(regionAt(index) + 4, 4), language)

    fun countryName(index: Int, language: PlaceLanguage): String = pickName(nameIndexes(countryAt(index), 4), language)

    fun settlementNames(index: Int): List<String> = allNames(nameIndexes(settlementAt(index) + 8, 3))

    fun regionNames(index: Int): List<String> = allNames(nameIndexes(regionAt(index) + 4, 4))

    fun countryNames(index: Int): List<String> = allNames(nameIndexes(countryAt(index), 4))

    fun regionCountry(index: Int): Int = data.getShort(regionAt(index)).toInt() and 0xFFFF

    /** The cell holding a settlement: the last cell whose first settlement is at or before it. */
    private fun cellOf(index: Int): Int {
        var low = 0
        var high = cellCount - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (data.getInt(cellsAt + mid * 8 + 4) <= index) low = mid else high = mid - 1
        }
        return data.getInt(cellsAt + low * 8)
    }

    fun settlementLat(index: Int): Double = settlementLat(index, cellOf(index))

    fun settlementLon(index: Int): Double = settlementLon(index, cellOf(index))

    /** A settlement's point from its cell's corner and its place in the cell (two bytes an axis). */
    private fun settlementLat(index: Int, cell: Int): Double = (cell / (360 * grid)).toDouble() / grid - 90 + (data.getShort(settlementAt(index)).toInt() and 0xFFFF) / 65535.0 / grid

    private fun settlementLon(index: Int, cell: Int): Double = (cell % (360 * grid)).toDouble() / grid - 180 + (data.getShort(settlementAt(index) + 2).toInt() and 0xFFFF) / 65535.0 / grid

    fun settlementPopulation(index: Int): Long = data.getInt(settlementAt(index) + 4).toLong() and 0xFFFFFFFFL

    private fun settlementRegion(index: Int): Int = (data.getShort(settlementAt(index) + 17).toInt() and 0xFFFF).let { if (it == NONE16) -1 else it }

    private fun settlementCountry(index: Int): Int = (data.getShort(settlementAt(index) + 19).toInt() and 0xFFFF).let { if (it == NONE16) -1 else it }

    private fun settlementAt(index: Int) = settlementsAt + index * SETTLEMENT

    private fun regionAt(index: Int) = regionsAt + index * AREA

    private fun countryAt(index: Int) = countriesAt + index * AREA

    /**
     * The place of a point (SPEC §13.2, LOGIKA §17.4): the nearest settlement within [RADIUS_KM], a more populous one
     * up to [PREFER_KM] further away winning; the region and country of the point itself, those of the settlement
     * when the point lies outside every border (at sea near a coast).
     */
    fun place(lat: Double, lon: Double): Place {
        val settlement = nearest(lat, lon)
        val hint = if (settlement >= 0) settlementRegion(settlement) else -1
        var region = regionOf(lat, lon, hint)
        if (region < 0 && settlement >= 0) region = hint
        val country = when {
            region >= 0 -> regionCountry(region).let { if (it == NONE16) -1 else it }
            else -> countryOf(lat, lon).let { if (it < 0 && settlement >= 0) settlementCountry(settlement) else it }
        }
        return Place(settlement, region, country)
    }

    /** The settlement for [lat], [lon], or -1 when none lies within [RADIUS_KM]. */
    fun nearest(lat: Double, lon: Double): Int {
        val candidates = ArrayList<Pair<Int, Double>>()
        val rows = ceil(RADIUS_KM / KM_PER_LAT * grid).toInt()
        val row = floor((lat + 90) * grid).toInt().coerceIn(0, 180 * grid - 1)
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val cols = ceil(RADIUS_KM / (KM_PER_LON * cosLat) * grid).toInt().coerceAtMost(180 * grid)
        val col = floor((lon + 180) * grid).toInt().coerceIn(0, 360 * grid - 1)
        val width = 360 * grid
        for (r in (row - rows)..(row + rows)) {
            if (r < 0 || r >= 180 * grid) continue
            // A run of columns, split in two where it crosses the date line
            val from = col - cols
            val to = col + cols
            val runs = when {
                to - from + 1 >= width -> listOf(0 to width - 1)
                from < 0 -> listOf(0 to to, (from + width) to (width - 1))
                to >= width -> listOf(from to (width - 1), 0 to (to - width))
                else -> listOf(from to to)
            }
            for ((a, b) in runs) {
                var c = firstCellAtLeast(r * width + a)
                while (c < cellCount) {
                    val id = data.getInt(cellsAt + c * 8)
                    if (id > r * width + b) break
                    val start = data.getInt(cellsAt + c * 8 + 4)
                    val end = if (c + 1 < cellCount) data.getInt(cellsAt + (c + 1) * 8 + 4) else settlementCount
                    for (s in start until end) {
                        val d = distanceKm(lat, lon, settlementLat(s, id), settlementLon(s, id))
                        if (d <= RADIUS_KM + PREFER_KM) candidates.add(s to d)
                    }
                    c++
                }
            }
        }
        val closest = candidates.minByOrNull { it.second } ?: return -1
        if (closest.second > RADIUS_KM) return -1
        return candidates
            .filter { it.second <= closest.second + PREFER_KM }
            .maxWithOrNull(compareBy<Pair<Int, Double>> { settlementPopulation(it.first) }.thenByDescending { it.second })!!
            .first
    }

    private fun firstCellAtLeast(id: Int): Int {
        var low = 0
        var high = cellCount
        while (low < high) {
            val mid = (low + high) ushr 1
            if (data.getInt(cellsAt + mid * 8) < id) low = mid + 1 else high = mid
        }
        return low
    }

    /** The region whose border holds the point (the finest level), trying [hint] first; -1 when none. */
    fun regionOf(lat: Double, lon: Double, hint: Int = -1): Int {
        if (hint >= 0 && inside(regionAt(hint) + 16, KIND_REGION, hint, lat, lon)) return hint
        for (r in 0 until regionCount) if (r != hint && inside(regionAt(r) + 16, KIND_REGION, r, lat, lon)) return r
        return -1
    }

    fun countryOf(lat: Double, lon: Double): Int {
        for (c in 0 until countryCount) if (inside(countryAt(c) + 12, KIND_COUNTRY, c, lat, lon)) return c
        return -1
    }

    /** The bounding box at [at] (west, south, east, north in 1e-4°) then the finest border after it. */
    private fun inside(at: Int, kind: Int, index: Int, lat: Double, lon: Double): Boolean {
        val x = (lon * BORDER_UNIT).toLong()
        val y = (lat * BORDER_UNIT).toLong()
        if (x < data.getInt(at) || y < data.getInt(at + 4) || x > data.getInt(at + 8) || y > data.getInt(at + 12)) return false
        return border(kind, index, at + 16 + (LEVELS - 1) * 4).contains(x, y)
    }

    /** A border at one level, for the map (SPEC §13.3): rings of points in 1e-4°. */
    fun countryBorder(index: Int, level: Int): Border = border(KIND_COUNTRY, index, countryAt(index) + 28 + level * 4, level)

    fun regionBorder(index: Int, level: Int): Border = border(KIND_REGION, index, regionAt(index) + 32 + level * 4, level)

    @Synchronized
    private fun border(kind: Int, index: Int, offsetAt: Int, level: Int = LEVELS - 1): Border {
        val key = (kind.toLong() shl 40) or (level.toLong() shl 32) or index.toLong()
        return shapes.getOrPut(key) { decodeBorder(bordersAt + data.getInt(offsetAt)) }
    }

    private fun decodeBorder(start: Int): Border {
        var at = start
        val count = data.getShort(at).toInt() and 0xFFFF
        at += 2
        val rings = ArrayList<IntArray>(count)
        repeat(count) {
            val points = data.getInt(at)
            var x = data.getInt(at + 4)
            var y = data.getInt(at + 8)
            at += 12
            val ring = IntArray(points * 2)
            ring[0] = x
            ring[1] = y
            for (p in 1 until points) {
                // Two zigzag varints: x, then y
                for (axis in 0..1) {
                    var value = 0L
                    var shift = 0
                    while (true) {
                        val b = data.get(at++).toInt() and 0xFF
                        value = value or ((b and 0x7F).toLong() shl shift)
                        if (b < 0x80) break
                        shift += 7
                    }
                    val delta = ((value ushr 1) xor -(value and 1)).toInt()
                    if (axis == 0) x += delta else y += delta
                }
                ring[p * 2] = x
                ring[p * 2 + 1] = y
            }
            rings.add(ring)
        }
        return Border(rings)
    }

    companion object {
        const val RADIUS_KM = 30.0
        const val PREFER_KM = 2.0
        const val LEVELS = 4
        private const val MAGIC = 0x4C504E48 // "HNPL" little-endian
        private const val FORMAT = 2

        /** Borders' units per degree (about 11 m). */
        const val BORDER_UNIT = 1e4
        private const val HEADER = 64
        private const val AREA = 48
        private const val SETTLEMENT = 21
        private const val NONE16 = 0xFFFF
        private const val KIND_COUNTRY = 0
        private const val KIND_REGION = 1
        private const val KM_PER_LAT = 110.57
        private const val KM_PER_LON = 111.32

        /** Distance on the sphere, close enough within tens of kilometres (equirectangular). */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            var dLon = abs(lon2 - lon1)
            if (dLon > 180) dLon = 360 - dLon
            val x = dLon * KM_PER_LON * cos(Math.toRadians((lat1 + lat2) / 2))
            val y = (lat2 - lat1) * KM_PER_LAT
            return sqrt(x * x + y * y)
        }
    }
}

/** A border's rings, points as x, y pairs in 1e-4°; holes are rings too (even–odd rule). */
class Border(val rings: List<IntArray>) {
    fun contains(x: Long, y: Long): Boolean {
        var inside = false
        for (ring in rings) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[i * 2].toLong()
                val yi = ring[i * 2 + 1].toLong()
                val xj = ring[j * 2].toLong()
                val yj = ring[j * 2 + 1].toLong()
                if ((yi > y) != (yj > y)) {
                    // Where the edge crosses the point's row, compared without division
                    val cross = (xj - xi) * (y - yi) - (x - xi) * (yj - yi)
                    if (if (yj > yi) cross > 0 else cross < 0) inside = !inside
                }
                j = i
            }
        }
        return inside
    }
}
