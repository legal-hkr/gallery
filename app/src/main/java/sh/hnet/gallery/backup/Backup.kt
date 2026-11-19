package sh.hnet.gallery.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gallery's backup file (SPEC §20.2), shaped as the family's: the settings only. Never media, the
 * vault, its key or the indexes: those are the system's or are rebuilt. Pure Kotlin, unit-tested.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val appVersion: String,
    val appVersionCode: Long,
    /** ISO 8601, UTC. */
    val createdAt: String,
    val settings: BackupSettings = BackupSettings(),
    /** The albums made in the app (SPEC-albums §20.2); absent in backups from before them. */
    val albums: List<BackupAlbum>? = null,
) {
    companion object {
        const val FORMAT = "sh.hnet.gallery.backup"
        const val FORMAT_VERSION = 1
    }
}

/**
 * Every setting may be absent (a backup from before it existed): a restore then leaves the current
 * value as it is (family rule). Later stages add their settings here (SPEC §20.2's list).
 *
 * @param gridStep The grid's step by name (`LARGE`, `MEDIUM`, `SMALL`, `MONTHS`)
 * @param timelineOn Folders switched on in Photos, as `volume:path` – only read, from backups made before the sources (S1–S4a)
 * @param timelineOff Default folders (camera, screenshots) switched off, the same way – only read
 * @param newAlbumsInPhotos The old switch for albums made in the app – only read, and ignored
 * @param newFoldersInPhotos Unknown new folders go In Photos (SPEC-albums §20.3)
 * @param sources The states the user chose for sources, by `volume:path` (an SD card's volume is its own id, so it comes back only on that card)
 * @param mediaManagementOffers How many of the two offers of Media management were shown (SPEC §10.6)
 * @param autoplayMotion Motion photos play once when shown (Settings › Viewer)
 */
@Serializable
data class BackupSettings(
    val gridStep: String? = null,
    val timelineOn: List<String>? = null,
    val timelineOff: List<String>? = null,
    val newAlbumsInPhotos: Boolean? = null,
    val newFoldersInPhotos: Boolean? = null,
    val sources: Map<String, String>? = null,
    /** Sources' albums pinned (pinning time, for the order) and their sorts, by `volume:path`. */
    val sourcePins: Map<String, Long>? = null,
    val sourceSorts: Map<String, String>? = null,
    /** The Albums tab's order (`RECENT`, `NAME`, `COUNT`). */
    val albumSort: String? = null,
    /** The collections shown oldest first, by name. */
    val collectionsOldestFirst: List<String>? = null,
    val mediaManagementOffers: Int? = null,
    val autoplayMotion: Boolean? = null,
)

/**
 * A user album in the backup (SPEC-albums §20.2): its items by fingerprint (name, date taken, size), so it
 * finds them on another phone; never the media themselves.
 */
@Serializable
data class BackupAlbum(
    val name: String,
    val createdAt: Long,
    val pinnedAt: Long? = null,
    val sort: String? = null,
    val items: List<String> = emptyList(),
)

/** What a picked file turned out to be, before anything changes. */
sealed interface BackupCheck {
    data class Valid(val backup: BackupFile) : BackupCheck

    /** Not JSON, or JSON of something else. */
    data class NotOurs(val details: String) : BackupCheck

    /** Made by a later version with a format this one doesn't know. */
    data class Newer(val formatVersion: Int) : BackupCheck
}

object Backup {
    /** Lenient on what it reads (unknown fields from later versions within the format), plain on what it writes. */
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    /** Checks [text] in order: JSON at all, our format, a version we read, then the content. */
    fun check(text: String): BackupCheck {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return BackupCheck.NotOurs(e.message ?: "Not JSON")
        } catch (e: IllegalArgumentException) {
            return BackupCheck.NotOurs(e.message ?: "Not a JSON object")
        }
        if (root.string("format") != BackupFile.FORMAT) return BackupCheck.NotOurs("format: ${root.string("format") ?: "absent"}")
        // Not a number (an object, a list) is as good as absent; jsonPrimitive would throw on it
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull ?: return BackupCheck.NotOurs("formatVersion absent")
        if (version > BackupFile.FORMAT_VERSION) return BackupCheck.Newer(version)
        return try {
            BackupCheck.Valid(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: SerializationException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        } catch (e: IllegalArgumentException) {
            BackupCheck.NotOurs(e.message ?: "Malformed backup")
        }
    }

    private fun JsonObject.string(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}
