package sh.hnet.gallery.model

/**
 * What another app asks to pick (SPEC §17.4, LOGIKA §16.6): the types it takes – any image, `video/mp4`, several
 * through `EXTRA_MIME_TYPES` – and whether one item or several. Items of other types are shown dimmed.
 */
data class PickRequest(val types: List<String>, val multiple: Boolean) {
    /** What the title names (SPEC §17.4: photo(s), video(s), item(s)). */
    enum class Kind { PHOTOS, VIDEOS, ITEMS }

    val kind: Kind
        get() = when {
            types.isNotEmpty() && types.all { it.startsWith("image/") } -> Kind.PHOTOS
            types.isNotEmpty() && types.all { it.startsWith("video/") } -> Kind.VIDEOS
            else -> Kind.ITEMS
        }

    fun accepts(mime: String): Boolean = types.isEmpty() || types.any { matches(it, mime.lowercase()) }

    companion object {
        /** For the app itself (adding to an album): every photo and video, several. */
        val ALL = PickRequest(emptyList(), multiple = true)

        /**
         * From an intent's type and `EXTRA_MIME_TYPES` (the list wins when given); the old directory types of `PICK`
         * (`vnd.android.cursor.dir/image`) read as any image; anything else that is no photo or video type as all of them.
         */
        fun of(type: String?, mimes: Array<String>?, multiple: Boolean): PickRequest {
            val given = mimes?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(type)
            val types = given.map { normal(it.lowercase()) }
            return PickRequest(if (types.any { it == "*/*" } || types.isEmpty()) emptyList() else types.filter { it.startsWith("image/") || it.startsWith("video/") }.ifEmpty { emptyList() }, multiple)
        }

        private fun normal(type: String): String = when (type) {
            "vnd.android.cursor.dir/image" -> "image/*"
            "vnd.android.cursor.dir/video" -> "video/*"
            else -> type
        }

        private fun matches(pattern: String, mime: String): Boolean = when {
            pattern == "*/*" -> true
            pattern.endsWith("/*") -> mime.startsWith(pattern.dropLast(1))
            else -> pattern == mime
        }
    }
}
