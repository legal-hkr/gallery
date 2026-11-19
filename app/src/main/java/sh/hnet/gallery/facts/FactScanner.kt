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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import sh.hnet.gallery.model.MediaItem

/**
 * Reads the facts of every item that has none or whose file changed (LOGIKA §2.3), newest first,
 * four files at a time, while the process lives (owner's decision D4: nothing is read while the app
 * is closed). A new list of items restarts the reading with what is still missing.
 */
class FactScanner(private val dao: FactDao, private val reader: FactReader, private val scope: CoroutineScope, private val canLocate: () -> Boolean) {
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
            // A newer list cancels the reading under way; what it wrote stays
            requests.filterNotNull().collectLatestSafely { run(it) }
        }
    }

    /** The items MediaStore lists now; called by the index after every change. */
    fun update(items: List<MediaItem>) {
        requests.value = items
    }

    private suspend fun run(items: List<MediaItem>) {
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
            return
        }
        var done = 0
        _progress.value = Progress(0, pending.size)
        try {
            for (chunk in pending.chunked(CHUNK)) {
                val read = coroutineScope { chunk.map { item -> async(files) { reader.read(item, located) } }.awaitAll() }
                dao.upsert(read)
                known = HashMap(known).apply { read.forEach { put(it.id, it) } }
                _facts.value = known
                done += chunk.size
                _progress.value = Progress(done, pending.size)
            }
        } finally {
            _progress.value = null
        }
    }

    private fun stale(fact: Fact?, item: MediaItem, located: Boolean): Boolean =
        fact == null || fact.generation != item.generation || fact.parser < FactReader.VERSION || (located && !fact.located && !item.video)

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

        /** Four files read at a time (LOGIKA §2.3). */
        val files = Dispatchers.IO.limitedParallelism(4)
    }
}
