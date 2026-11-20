package sh.hnet.gallery.model

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * The map's projection (SPEC §13.3): Web Mercator onto a unit square – x 0..1 from 180° W eastwards, y 0..1 from the
 * north (85.05° N) southwards – as every web map, so places keep their shapes; the poles beyond 85° are left out.
 */
object Mercator {
    const val MAX_LAT = 85.05112878

    fun x(lon: Double): Double = (lon + 180.0) / 360.0

    fun y(lat: Double): Double {
        val clamped = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(clamped) + 1.0 / kotlin.math.cos(clamped)) / PI) / 2.0
    }

    fun lon(x: Double): Double = x * 360.0 - 180.0

    fun lat(y: Double): Double = Math.toDegrees(atan(kotlin.math.sinh(PI * (1.0 - 2.0 * y))))
}

/** A point on the map: an item at its place on the unit square. */
data class MapPoint(val id: Long, val x: Double, val y: Double)

/**
 * A cluster (SPEC §13.3): the items of one grid cell, newest first as given; drawn at its newest item's point, so it
 * sits where that photo was taken.
 */
data class MapCluster(val ids: List<Long>, val x: Double, val y: Double) {
    val newest: Long get() = ids.first()
    val size: Int get() = ids.size
}

object MapClusters {
    /**
     * Items in cells of [cell] (unit-square side); the clusters come in the order of their newest items. [points]
     * are newest first, so each cluster's list is too.
     */
    fun of(points: List<MapPoint>, cell: Double): List<MapCluster> {
        val cells = LinkedHashMap<Long, MutableList<MapPoint>>()
        for (p in points) {
            val key = floor(p.x / cell).toLong() * 4_294_967_296L + floor(p.y / cell).toLong()
            cells.getOrPut(key) { ArrayList() }.add(p)
        }
        return cells.values.map { list -> MapCluster(list.map { it.id }, list[0].x, list[0].y) }
    }

    /**
     * The zoom's step for clustering: clusters are made again only when it changes, not on every frame of a pinch.
     * Half steps of the power of two of the world's width in cells.
     */
    fun step(worldPx: Double, cellPx: Double): Int = floor(2.0 * kotlin.math.log2(worldPx / cellPx)).toInt()

    /** The cell side (unit square) for a [step]. */
    fun cellOf(step: Int): Double = 1.0 / Math.pow(2.0, step / 2.0)
}
