package sh.hnet.gallery.model

import kotlin.random.Random

/** How one slide gives way to the next (SPEC §16). */
enum class SlideTransition {
    CROSSFADE,
    KEN_BURNS,
    ;

    companion object {
        fun of(name: String?): SlideTransition = entries.firstOrNull { it.name == name } ?: CROSSFADE
    }
}

/** The slideshow's queue and timing (SPEC §16, LOGIKA §19.2). */
object Slideshow {
    /** A photo's time on screen, in seconds (SPEC §20.6); 5 by default. */
    val SECONDS = listOf(3, 5, 10, 20)
    const val DEFAULT_SECONDS = 5

    /** A video plays at most this long, from its start (SPEC §16). */
    const val VIDEO_MAX_MS = 15_000L

    /** The crossfade between two slides. */
    const val FADE_MS = 800

    fun seconds(value: Int?): Int = value?.takeIf { it in SECONDS } ?: DEFAULT_SECONDS

    /** A slide's time: a photo's [seconds], a video's length up to [VIDEO_MAX_MS] (unknown length: the most). */
    fun slideMs(video: Boolean, durationMs: Long, seconds: Int): Long = when {
        !video -> seconds * 1000L
        durationMs <= 0 -> VIDEO_MAX_MS
        else -> minOf(durationMs, VIDEO_MAX_MS)
    }

    /**
     * The slide after [current] in [ids] that is still there ([alive]), from the start again after the last; [current]
     * itself when it is the only one left, null when none is. A [current] gone from [ids] counts from its place in
     * [ids] all the same.
     */
    fun next(ids: List<Long>, current: Long, alive: (Long) -> Boolean): Long? {
        if (ids.isEmpty()) return null
        val at = ids.indexOf(current)
        for (step in 1..ids.size) {
            val id = ids[((if (at < 0) -1 else at) + step).mod(ids.size)]
            if (alive(id)) return id
        }
        return null
    }

    /** Where the show starts: [start] when there, else the first slide there is. */
    fun first(ids: List<Long>, start: Long, alive: (Long) -> Boolean): Long? = if (start in ids && alive(start)) start else ids.firstOrNull(alive)
}

/**
 * Ken Burns (SPEC §16): a slow pan and zoom between two poses over a slide's time. A pose's [scale] is 1.0–1.15 of the
 * photo fitted to the screen; [x] and [y] are its shift as a share (−1…1) of what that scale adds, so the place the photo
 * fitted always stays covered (it may grow into the screen's free space beyond, uncut).
 */
data class KenBurnsPose(val scale: Float, val x: Float, val y: Float) {
    /** The shift in pixels of a photo shown [width] × [height] (fitted): within half of what the scale adds. */
    fun shiftX(width: Float): Float = x * (scale - 1f) * width / 2f

    fun shiftY(height: Float): Float = y * (scale - 1f) * height / 2f
}

data class KenBurns(val from: KenBurnsPose, val to: KenBurnsPose) {
    /** The pose at [t] (0…1 of the slide's time), moving evenly. */
    fun at(t: Float): KenBurnsPose {
        val p = t.coerceIn(0f, 1f)
        return KenBurnsPose(from.scale + (to.scale - from.scale) * p, from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
    }

    companion object {
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 1.15f

        /** No motion: the photo fitted, as with animations off in the system. */
        val STILL = KenBurns(KenBurnsPose(1f, 0f, 0f), KenBurnsPose(1f, 0f, 0f))

        /**
         * A random path: in or out between near 1.0 and near 1.15 (always a visible zoom), and from one random shift to
         * another; each shift kept within what its own scale allows.
         */
        fun random(random: Random = Random.Default): KenBurns {
            val near = MIN_SCALE + random.nextFloat() * 0.03f
            val far = MAX_SCALE - random.nextFloat() * 0.03f
            fun shift() = random.nextFloat() * 2f - 1f
            val a = KenBurnsPose(near, shift(), shift())
            val b = KenBurnsPose(far, shift(), shift())
            return if (random.nextBoolean()) KenBurns(a, b) else KenBurns(b, a)
        }
    }
}
