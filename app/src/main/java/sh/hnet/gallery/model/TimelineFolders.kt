package sh.hnet.gallery.model

/**
 * Which folders the Photos timeline shows (LOGIKA §2.5, owner's decision D3 of 18 Nov 2026): the
 * camera and the screenshots by default, on every volume; any folder the user switched on; minus the
 * defaults the user switched off.
 */
object TimelineFolders {
    /** `DCIM/Camera/`, and the DCF folders of digital cameras: three digits and five characters (`100ANDRO`, `100MEDIA`, `100CANON`). */
    private val CAMERA = Regex("DCIM/(Camera|[1-9][0-9]{2}[A-Za-z0-9_]{5})/", RegexOption.IGNORE_CASE)

    /** A `Screenshots` folder straight in `DCIM/` or `Pictures/` (Pixel, Samsung, Xiaomi). */
    private val SCREENSHOTS = Regex("(DCIM|Pictures)/Screenshots/", RegexOption.IGNORE_CASE)

    fun isCamera(path: String): Boolean = CAMERA.matches(path)

    fun isScreenshots(path: String): Boolean = SCREENSHOTS.matches(path)

    /** Shown in the timeline unless switched off. */
    fun isDefault(path: String): Boolean = isCamera(path) || isScreenshots(path)

    /**
     * Whether the folder's items are in the timeline.
     *
     * @param on Folders switched on by the user (and albums created in the app), as [FolderKey.key]
     * @param off Default folders switched off by the user
     */
    fun shown(folder: FolderKey, on: Set<String>, off: Set<String>): Boolean {
        val key = folder.key
        return key in on || (isDefault(folder.path) && key !in off)
    }
}
