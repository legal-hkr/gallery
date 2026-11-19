package sh.hnet.gallery.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.hnet.gallery.model.IndexMerge
import sh.hnet.gallery.model.MediaItem

/**
 * Every photo and video MediaStore lists, in memory, newest first (LOGIKA §2.1): the one list the
 * timeline, the albums, Trash and the results are made from. Loaded once with the newest first, then
 * kept up to date by generation (LOGIKA §2.2).
 */
@OptIn(FlowPreview::class)
class MediaIndex(private val source: MediaSource, private val scope: CoroutineScope) {
    private val _access = MutableStateFlow(source.access())
    val access: StateFlow<Access> = _access.asStateFlow()

    private val _items = MutableStateFlow<List<MediaItem>?>(null)

    /** Every item, trashed included; null until the first read. */
    val items: StateFlow<List<MediaItem>?> = _items.asStateFlow()

    private val lock = Mutex()
    private var volumes: Map<String, VolumeState> = emptyMap()

    init {
        scope.launch(Dispatchers.IO) {
            _access.collectLatest { access ->
                if (access == Access.NONE) {
                    _items.value = emptyList()
                    return@collectLatest
                }
                safely { load(firstScreen = _items.value == null) }
                source.changes().debounce(CHANGE_DEBOUNCE_MS).collect { safely { refresh() } }
            }
        }
    }

    /**
     * The app came to the screen (SPEC §19.4): access may have been given or taken in the system, and
     * with partial access the system's selection may have changed without a word to the app.
     */
    fun recheck() {
        val now = source.access()
        if (now != _access.value) _access.value = now else if (now != Access.NONE && _items.value != null) scope.launch(Dispatchers.IO) { safely { refresh() } }
    }

    /** Everything again; the newest screenful first when nothing is shown yet. */
    private suspend fun load(firstScreen: Boolean) = lock.withLock {
        // Taken before reading: a change made while reading is newer than these and comes with the next refresh
        val states = source.volumes()
        if (firstScreen) _items.value = source.items(limit = FIRST_SCREEN).sortedWith(MediaItem.NEWEST_FIRST)
        _items.value = source.items().sortedWith(MediaItem.NEWEST_FIRST)
        volumes = states
    }

    /** What changed since the last read, by generation; everything again when a volume is new, gone back or replaced. */
    private suspend fun refresh() {
        val current = _items.value
        // With partial access, newly selected items keep their old generation, so only a full read finds them
        if (current == null || _access.value == Access.PARTIAL) return load(firstScreen = false)
        lock.withLock {
            val states = source.volumes()
            val replaced = states.any { (name, state) -> volumes[name]?.let { it.version != state.version || it.generation > state.generation } ?: true }
            if (replaced) {
                _items.value = source.items().sortedWith(MediaItem.NEWEST_FIRST)
            } else {
                val changed = states.flatMap { (name, _) -> source.items(volume = name, since = volumes.getValue(name).generation) }
                _items.value = IndexMerge.merge(current, changed, source.ids())
            }
            volumes = states
        }
    }

    /** A failed read (a volume going away while read, the provider restarting) keeps what is shown; the next change reads again. */
    private suspend fun safely(action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // MediaStore's own message (a column, a volume), never an item's name or date (family rule: logs carry no user data)
            Log.w(TAG, "MediaStore read failed: $e")
        }
    }

    private companion object {
        const val TAG = "MediaIndex"

        /** The newest items read first, to show the top of the timeline at once (LOGIKA §2.1). */
        const val FIRST_SCREEN = 300

        /** MediaStore announces a change many times; one refresh after it settles (SPEC §22.3). */
        const val CHANGE_DEBOUNCE_MS = 300L
    }
}
