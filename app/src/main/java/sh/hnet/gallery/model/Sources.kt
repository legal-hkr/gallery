package sh.hnet.gallery.model

/** Where a source's content is seen (SPEC-albums §9.1). */
enum class SourceState {
    /** On the timeline and in its album. */
    IN_PHOTOS,

    /** Only in its album (and user albums, search, collections). */
    ALBUM_ONLY,

    /** Nowhere but Settings › Photos. */
    HIDDEN;

    companion object {
        fun of(name: String?): SourceState? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The state a folder gets the first time the app sees it (SPEC-albums §9.1, LOGIKA §15.2, §15.11), the
 * first matching rule winning; afterwards the user's choice wins and the rules never change it again.
 * Paths are MediaStore's `RELATIVE_PATH` ("DCIM/Camera/"), compared without case.
 */
object SourceRules {
    /** Music and sound folders: their pictures are album covers (no audio is read – the app has no audio access). */
    private val SOUND = Regex("(Music|Audiobooks|Podcasts|Ringtones|Notifications|Alarms|Recordings)/.*", RegexOption.IGNORE_CASE)

    /** Messengers' stickers, wherever they are. */
    private val STICKERS = Regex("(.*/)?[^/]*Stickers/.*", RegexOption.IGNORE_CASE)

    /** Apps' own media folders (Android 11+), mostly working files. */
    private val APP_MEDIA = Regex("Android/media/[^/]+/.*", RegexOption.IGNORE_CASE)

    /** …except the messengers' photos and videos, which live there since Android 11. */
    private val MESSENGER_MEDIA = Regex(".*/(WhatsApp Images|WhatsApp Video|WhatsApp Animated Gifs|WhatsApp Business Images|WhatsApp Business Video|Telegram Images|Telegram Video|Signal)/.*", RegexOption.IGNORE_CASE)

    /** Screen recordings as the makers name them. */
    private val SCREEN_RECORDINGS = Regex("(Movies|DCIM)/(Screen ?recordings?|ScreenRecords?|ScreenRecorder)/", RegexOption.IGNORE_CASE)

    fun classify(path: String, newFoldersInPhotos: Boolean): SourceState = when {
        TimelineFolders.isCamera(path) -> SourceState.IN_PHOTOS
        TimelineFolders.isScreenshots(path) -> SourceState.IN_PHOTOS
        SOUND.matches(path) -> SourceState.HIDDEN
        STICKERS.matches(path) -> SourceState.HIDDEN
        APP_MEDIA.matches(path) && !MESSENGER_MEDIA.matches(path) -> SourceState.HIDDEN
        SCREEN_RECORDINGS.matches(path) -> SourceState.ALBUM_ONLY
        newFoldersInPhotos -> SourceState.IN_PHOTOS
        else -> SourceState.ALBUM_ONLY
    }

    /**
     * The states the timeline's old choice (S1–S4a: folders switched on or off in Settings › Photos, as
     * [FolderKey.key]s) amounts to, as the user's own: on is In Photos, off is Album only.
     */
    fun fromTimelineChoice(on: Set<String>, off: Set<String>): Map<String, SourceState> =
        on.associateWith { SourceState.IN_PHOTOS } + off.associateWith { SourceState.ALBUM_ONLY }
}

/** Album covers lying among other files (SPEC-albums §9.1): left out everywhere, by name, without a setting. */
object CoverNames {
    private val COVER = Regex("(cover|folder|albumart[^/]*)\\.(jpe?g|png|webp|gif|bmp)", RegexOption.IGNORE_CASE)

    fun isCover(name: String): Boolean = COVER.matches(name)
}

/**
 * What the app shows of the media (SPEC-albums §9.1): [visible] leaves out hidden sources' items and
 * album covers (Trash's included – they are nowhere but Settings); [timeline] is the visible items of
 * sources In Photos that aren't in Trash.
 */
class Library(val visible: List<MediaItem>, val timeline: List<MediaItem>) {
    companion object {
        fun of(items: List<MediaItem>, stateOf: (FolderKey) -> SourceState): Library {
            val states = HashMap<FolderKey, SourceState>()
            val visible = ArrayList<MediaItem>(items.size)
            val timeline = ArrayList<MediaItem>(items.size)
            for (item in items) {
                if (CoverNames.isCover(item.name)) continue
                val state = states.getOrPut(item.folder) { stateOf(item.folder) }
                if (state == SourceState.HIDDEN) continue
                visible.add(item)
                if (state == SourceState.IN_PHOTOS && !item.trashed) timeline.add(item)
            }
            return Library(visible, timeline)
        }
    }
}

/** An album cover's mosaic (SPEC §9.3): where each of its up to four newest items goes, as fractions of the square. */
object Mosaic {
    data class Cell(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** One alone; two side by side; three as one large on the left and two small on the right; four in 2 × 2. */
    fun cells(count: Int): List<Cell> = when {
        count <= 0 -> emptyList()
        count == 1 -> listOf(Cell(0f, 0f, 1f, 1f))
        count == 2 -> listOf(Cell(0f, 0f, 0.5f, 1f), Cell(0.5f, 0f, 1f, 1f))
        count == 3 -> listOf(Cell(0f, 0f, 0.5f, 1f), Cell(0.5f, 0f, 1f, 0.5f), Cell(0.5f, 0.5f, 1f, 1f))
        else -> listOf(Cell(0f, 0f, 0.5f, 0.5f), Cell(0.5f, 0f, 1f, 0.5f), Cell(0f, 0.5f, 0.5f, 1f), Cell(0.5f, 0.5f, 1f, 1f))
    }
}
