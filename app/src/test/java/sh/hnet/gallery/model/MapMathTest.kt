package sh.hnet.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MapMathTest {
    @Test
    fun mercatorRoundTrip() {
        assertEquals(0.5, Mercator.x(0.0), 1e-12)
        assertEquals(0.5, Mercator.y(0.0), 1e-12)
        assertEquals(0.0, Mercator.y(Mercator.MAX_LAT), 1e-9)
        for (lat in listOf(-60.0, 0.0, 50.0617, 70.0)) assertEquals(lat, Mercator.lat(Mercator.y(lat)), 1e-9)
        for (lon in listOf(-179.9, 0.0, 19.9373)) assertEquals(lon, Mercator.lon(Mercator.x(lon)), 1e-9)
        // North is up: Kraków above Lisbon
        assert(Mercator.y(50.06) < Mercator.y(38.71))
    }

    @Test
    fun clustersByCell() {
        val points = listOf(MapPoint(1, 0.10, 0.10), MapPoint(2, 0.12, 0.11), MapPoint(3, 0.60, 0.60), MapPoint(4, 0.11, 0.12))
        val clusters = MapClusters.of(points, 0.25)
        assertEquals(2, clusters.size)
        assertEquals(listOf(1L, 2L, 4L), clusters[0].ids)
        // At its newest item's point
        assertEquals(0.10, clusters[0].x, 0.0)
        assertEquals(listOf(3L), clusters[1].ids)
        // Smaller cells split them
        assertEquals(4, MapClusters.of(points, 0.005).size)
    }

    @Test
    fun steps() {
        // A world 1024 cells wide is step 20; the cell of a step is 1/2^(step/2)
        assertEquals(20, MapClusters.step(64.0 * 1024, 64.0))
        assertEquals(1.0 / 1024, MapClusters.cellOf(20), 1e-12)
    }
}
