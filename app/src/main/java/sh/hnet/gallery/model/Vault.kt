package sh.hnet.gallery.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One item in the vault (LOGIKA §9, §19.4), as its encrypted index holds it: what the grid, the viewer and Info need
 * without opening the file, and where it came from, so it can go back there.
 *
 * @param file The name of its content (`<file>.data`) and thumbnail (`<file>.thumb`) in the vault's folder
 * @param taken When it was taken (ms), as the gallery knew it
 * @param movedAt When it went into the vault (ms): the grid's order, newest first
 * @param fromVolume The volume it came from; null when imported (S8c)
 * @param fromPath Its folder there (`RELATIVE_PATH`, ending in `/`); null when imported
 * @param albums The user albums it was in before moving in (SPEC-albums §18.3), chosen again when moved out
 */
@Serializable
data class VaultEntry(
    val file: String,
    val name: String,
    val mime: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val taken: Long = 0,
    val offsetMinutes: Int? = null,
    val movedAt: Long,
    val fromVolume: String? = null,
    val fromPath: String? = null,
    val albums: List<Long> = emptyList(),
    val gainmap: Boolean = false,
) {
    val video: Boolean get() = mime.startsWith("video/")
}

/** The vault's index: every item, written whole and atomically, encrypted (LOGIKA §9). */
@Serializable
data class VaultIndex(val version: Int = 1, val entries: List<VaultEntry> = emptyList()) {
    /** The grid's order (SPEC §18.3): moved in last, first. */
    val ordered: List<VaultEntry> get() = entries.sortedWith(compareByDescending<VaultEntry> { it.movedAt }.thenByDescending { it.taken }.thenBy { it.file })

    fun encode(): ByteArray = JSON.encodeToString(serializer(), this).toByteArray()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun decode(bytes: ByteArray): VaultIndex = JSON.decodeFromString(serializer(), bytes.decodeToString())
    }
}

/** When the vault locks after the app goes to the background (SPEC §18.4, §20.7); the screen turning off always locks it (D31). */
enum class VaultLockDelay(val ms: Long) {
    AT_ONCE(0),
    ONE_MINUTE(60_000),
    FIVE_MINUTES(300_000),
    ;

    companion object {
        fun of(name: String?): VaultLockDelay = entries.firstOrNull { it.name == name } ?: AT_ONCE
    }
}

object VaultSpace {
    /** Room kept free beyond the copies (LOGIKA §19.4): the phone mustn't fill up while moving in. */
    const val MARGIN = 200L * 1024 * 1024

    /** Whether moving [sizes] in fits in [free] bytes: each original stays until its encrypted copy is written. */
    fun fits(sizes: List<Long>, free: Long): Boolean {
        // The header and every chunk's tag: a little over the content
        val needed = sizes.sumOf { it + VaultFormat.HEADER + (it / VaultFormat.CHUNK + 1) * 16 }
        return needed + MARGIN <= free
    }
}
