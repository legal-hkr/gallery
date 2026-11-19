package sh.hnet.gallery.model

/**
 * The camera's and the screenshots' folders (owner's decision D3 of 18 Nov 2026), on every volume: In
 * Photos by the sources' rules (SPEC-albums §9.1), the screenshots gathered by their collection.
 */
object TimelineFolders {
    /** `DCIM/Camera/`, and the DCF folders of digital cameras: three digits and five characters (`100ANDRO`, `100MEDIA`, `100CANON`). */
    private val CAMERA = Regex("DCIM/(Camera|[1-9][0-9]{2}[A-Za-z0-9_]{5})/", RegexOption.IGNORE_CASE)

    /** A `Screenshots` folder straight in `DCIM/` or `Pictures/` (Pixel, Samsung, Xiaomi). */
    private val SCREENSHOTS = Regex("(DCIM|Pictures)/Screenshots/", RegexOption.IGNORE_CASE)

    fun isCamera(path: String): Boolean = CAMERA.matches(path)

    fun isScreenshots(path: String): Boolean = SCREENSHOTS.matches(path)
}
