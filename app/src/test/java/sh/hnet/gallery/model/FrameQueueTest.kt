package sh.hnet.gallery.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQueueTest {
    private val hour = FrameInterval.HOURLY.millis
    private val random = Random(5)

    @Test
    fun everyPhotoOnceARound() {
        val ids = (1L..10L).toList()
        var state = FrameState("u:1", FrameInterval.HOURLY.name)
        val shown = ArrayList<Long>()
        var now = 0L
        repeat(10) {
            state = FrameQueue.step(state, ids, now, random)
            shown += state.current!!
            now += hour
        }
        assertEquals(ids.toSet(), shown.toSet())
        // The next round starts anew, not with the photo just shown
        val last = state.current
        state = FrameQueue.step(state, ids, now, random)
        assertNotEquals(last, state.current)
        assertEquals(9, state.queue.size)
    }

    @Test
    fun keptUntilItsTimeAndWhileItIsThere() {
        val first = FrameQueue.step(FrameState("u:1", FrameInterval.SIX_HOURS.name), listOf(1L, 2L, 3L), 0, random)
        assertEquals(6 * hour, first.nextAt)
        // Within its time: the same
        assertEquals(first.current, FrameQueue.step(first, listOf(1L, 2L, 3L), 5 * hour, random).current)
        // Gone from the album (trashed, moved to the vault): another at once
        val without = listOf(1L, 2L, 3L) - first.current!!
        val next = FrameQueue.step(first, without, 1 * hour, random)
        assertTrue(next.current in without)
        assertTrue(next.queue.all { it in without })
    }

    @Test
    fun anEmptyAlbumShowsNothing() {
        val state = FrameQueue.step(FrameState("s:x", current = 7, nextAt = 99), emptyList(), 0, random)
        assertNull(state.current)
        assertEquals(FrameInterval.SIX_HOURS, FrameInterval.of(null))
        assertEquals(FrameInterval.DAILY, FrameInterval.of("DAILY"))
    }
}
