package sh.hnet.gallery.facts

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.PlaceData

/**
 * Reads the facts of every item that has none or whose file changed (LOGIKA §2.3), newest first,
 * four files at a time, while the process lives (owner's decision D4: nothing is read while the app
 * is closed). A new list of items restarts the reading with what is still missing.
 */
class FactScanner(
    private val dao: FactDao,
    private val reader: FactReader,
    private val scope: CoroutineScope,
    private val canLocate: () -> Boolean,
    /** The place data while place names are on (LOGIKA §17.4), null while off or not there. */
    private val places: Flow<PlaceData?>,
) {
    /** How far a reading has got; null while nothing is being read. */
    data class Progress(val done: Int, val total: Int)

    private val _facts = MutableStateFlow<Map<Long, Fact>?>(null)

    /** Every item's facts by MediaStore id; null until the stored ones are loaded. */
    val facts: StateFlow<Map<Long, Fact>?> = _facts.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val requests = MutableStateFlow<List<MediaItem>?>(null)

    init {
        scope.launch(Dispatchers.IO) {
            _facts.value = dao.all().associateBy { it.id }
            // A newer list, or place names switched, cancels the reading under way; what it wrote stays
            combine(requests.filterNotNull(), places) { items, data -> items to data }.collectLatestSafely { (items, data) -> run(items, data) }
        }
    }

    /** The items MediaStore lists now; called by the index after every change. */
    fun update(items: List<MediaItem>) {
        requests.value = items
    }

    private suspend fun run(items: List<MediaItem>, data: PlaceData?) {
        var known = _facts.value ?: return
        val located = canLocate()
        val ids = items.mapTo(HashSet(items.size)) { it.id }
        val gone = known.keys.filter { it !in ids }
        if (gone.isNotEmpty()) {
            gone.chunked(500).forEach { dao.delete(it) }
            known = known - gone.toSet()
            _facts.value = known
        }
        // Trashed items aren't read: nothing of the app shows their facts
        val pending = items.filter { !it.trashed && stale(known[it.id], it, located) }
        if (pending.isEmpty()) {
            _progress.value = null
            place(known, data)
            return
        }
        var done = 0
        _progress.value = Progress(0, pending.size)
        try {
            for (chunk in pending.chunked(CHUNK)) {
                val read = coroutineScope { chunk.map { item -> async(files) { reader.read(item, located) } }.awaitAll() }.map { placed(it, data) }
                dao.upsert(read)
                known = HashMap(known).apply { read.forEach { put(it.id, it) } }
                _facts.value = known
                done += chunk.size
                _progress.value = Progress(done, pending.size)
            }
        } finally {
            _progress.value = null
        }
        place(known, data)
    }

    /**
     * Rows placed with other place data, or placed while names are now off, placed again – from their stored
     * coordinates, no file opened (LOGIKA §17.4).
     */
    private suspend fun place(facts: Map<Long, Fact>, data: PlaceData?) {
        val version = data?.version ?: 0
        val stale = facts.values.filter { it.places != version && (it.latitude != null || it.places != 0) }
        if (stale.isEmpty()) return
        var known = facts
        for (chunk in stale.chunked(PLACE_CHUNK)) {
            val placed = chunk.map { placed(it, data) }
            dao.upsert(placed)
            known = HashMap(known).apply { placed.forEach { put(it.id, it) } }
            _facts.value = known
        }
    }

    private fun placed(fact: Fact, data: PlaceData?): Fact {
        val lat = fact.latitude
        val lon = fact.longitude
        if (data == null || lat == null || lon == null) return fact.copy(settlement = null, region = null, country = null, places = 0)
        val place = data.place(lat, lon)
        return fact.copy(
            settlement = place.settlement.takeIf { it >= 0 },
            region = place.region.takeIf { it >= 0 },
            country = place.country.takeIf { it >= 0 },
            places = data.version,
        )
    }

    private fun stale(fact: Fact?, item: MediaItem, located: Boolean): Boolean =
        fact == null || fact.generation != item.generation || fact.parser < FactReader.versionFor(item) || (located && !fact.located)

    /** As [collectLatest], but a failure of one reading (the database, the storage) leaves the next change of the list to try again. */
    private suspend fun <T> Flow<T>.collectLatestSafely(action: suspend (T) -> Unit) = collectLatest { value ->
        try {
            action(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("FactScanner", "Reading facts failed: $e")
        }
    }

    private companion object {
        /** Files read between two writes to the database and two updates of the facts. */
        const val CHUNK = 64

        /** Rows placed between two writes: placing reads no file, so many at once. */
        const val PLACE_CHUNK = 1000

        /** Four files read at a time (LOGIKA §2.3). */
        val files = Dispatchers.IO.limitedParallelism(4)
    }
}
