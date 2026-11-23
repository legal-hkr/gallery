package sh.hnet.gallery.model

import kotlin.random.Random
import kotlinx.serialization.Serializable

/** How often a Frame changes its photo (SPEC §21.1): every hour, every 6 hours (the default) or daily. */
enum class FrameInterval(val millis: Long) {
    HOURLY(3_600_000L),
    SIX_HOURS(6 * 3_600_000L),
    DAILY(24 * 3_600_000L);

    companion object {
        fun of(name: String?): FrameInterval = entries.firstOrNull { it.name == name } ?: SIX_HOURS
    }
}

/**
 * One Frame widget (S11, SPEC §21.1, LOGIKA §22.5): its album (a key of the Albums tab's – `s:` a folder, `u:` a user
 * album – or `c:FAVORITES`, `c:VIDEOS`), its interval, the photos still to come in this round, the one shown, and when
 * it changes next (epoch ms).
 */
@Serializable
data class FrameState(
    val album: String,
    val interval: String = FrameInterval.SIX_HOURS.name,
    val queue: List<Long> = emptyList(),
    val current: Long? = null,
    val nextAt: Long = 0,
)

/** A Frame's order: random, no photo again until the album is through, then a new round (SPEC §21.1). */
object FrameQueue {
    /**
     * [state] at [now] with the album's photos [ids]: the one shown kept until its time is up (and while it is still in
     * the album), else the next of the round – those gone from the album left out, a new round shuffled when it's
     * through (not starting with the photo just shown, when there's another).
     */
    fun step(state: FrameState, ids: List<Long>, now: Long, random: Random): FrameState {
        val valid = ids.toHashSet()
        if (valid.isEmpty()) return state.copy(queue = emptyList(), current = null)
        val left = state.queue.filter { it in valid }
        val current = state.current
        if (current != null && current in valid && now < state.nextAt) return state.copy(queue = left)
        var round = left
        if (round.isEmpty()) {
            round = ids.distinct().shuffled(random)
            if (round.size > 1 && round.first() == current) round = round.drop(1) + round.first()
        }
        return state.copy(queue = round.drop(1), current = round.first(), nextAt = now + FrameInterval.of(state.interval).millis)
    }
}
