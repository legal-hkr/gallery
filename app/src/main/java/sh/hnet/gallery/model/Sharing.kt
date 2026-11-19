package sh.hnet.gallery.model

/** Random access to a file's bytes: a [java.io.RandomAccessFile] on the phone, an array in the tests. */
interface Bytes {
    val size: Long

    fun read(at: Long, count: Int): ByteArray

    fun write(at: Long, bytes: ByteArray)
}

class ArrayBytes(val array: ByteArray) : Bytes {
    override val size: Long get() = array.size.toLong()

    override fun read(at: Long, count: Int): ByteArray = array.copyOfRange(at.toInt(), (at + count).toInt().coerceAtMost(array.size))

    override fun write(at: Long, bytes: ByteArray) {
        bytes.copyInto(array, at.toInt())
    }
}

/**
 * A video's location taken out without re-encoding or rewriting the file (LOGIKA §6, §16.1): the boxes that hold it
 * – `©xyz` and `loci` under `udta`, and the `ilst` items whose `keys` name is `com.apple.quicktime.location…` – become
 * `free` boxes of the same size with their content zeroed. Nothing moves, so the sample tables stay right, and players
 * skip `free` by the format's definition.
 */
object Mp4Location {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "udta", "edts")
    private val LOCATION = setOf("©xyz", "loci")
    private const val APPLE_LOCATION = "com.apple.quicktime.location"

    private class Box(val start: Long, val headerSize: Int, val size: Long, val type: String) {
        val payload: Long get() = start + headerSize
        val end: Long get() = start + size
    }

    /** How many boxes were cleared. */
    fun strip(file: Bytes): Int {
        var cleared = 0
        fun walk(from: Long, to: Long) {
            for (box in boxes(file, from, to)) {
                when {
                    box.type in LOCATION -> cleared += clear(file, box)
                    box.type == "meta" -> cleared += meta(file, box)
                    box.type in CONTAINERS -> walk(box.payload, box.end)
                }
            }
        }
        walk(0, file.size)
        return cleared
    }

    private fun boxes(file: Bytes, from: Long, to: Long): List<Box> {
        val list = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            val header = file.read(at, 8)
            var size = int(header, 0).toLong() and 0xFFFFFFFFL
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                if (at + 16 > to) break
                size = long(file.read(at + 8, 8))
                headerSize = 16
            } else if (size == 0L) {
                size = to - at
            }
            if (size < headerSize || at + size > to) break
            list.add(Box(at, headerSize, size, type))
            at += size
        }
        return list
    }

    /** `meta` is a full box in MP4 (version and flags first), a plain one in QuickTime: told apart by where `hdlr` is. */
    private fun meta(file: Bytes, meta: Box): Int {
        val quickTime = meta.payload + 8 <= meta.end && String(file.read(meta.payload + 4, 4), Charsets.ISO_8859_1) == "hdlr"
        val start = if (quickTime) meta.payload else meta.payload + 4
        val children = boxes(file, start, meta.end)
        var cleared = 0
        children.firstOrNull { it.type in LOCATION }?.let { cleared += clear(file, it) }
        val keys = children.firstOrNull { it.type == "keys" } ?: return cleared
        val ilst = children.firstOrNull { it.type == "ilst" } ?: return cleared
        val located = locationKeys(file, keys)
        if (located.isEmpty()) return cleared
        for (item in boxes(file, ilst.payload, ilst.end)) {
            val index = int(item.type.toByteArray(Charsets.ISO_8859_1), 0)
            if (index in located) cleared += clear(file, item)
        }
        return cleared
    }

    /** The 1-based indexes of the location keys: `keys` is a full box, a count, then (size, namespace, name) entries. */
    private fun locationKeys(file: Bytes, keys: Box): Set<Int> {
        val found = HashSet<Int>()
        var at = keys.payload + 8
        var index = 1
        while (at + 8 <= keys.end) {
            val size = int(file.read(at, 4), 0)
            if (size < 8 || at + size > keys.end) break
            val name = String(file.read(at + 8, size - 8), Charsets.UTF_8)
            if (name.startsWith(APPLE_LOCATION)) found += index
            at += size
            index++
        }
        return found
    }

    private fun clear(file: Bytes, box: Box): Int {
        file.write(box.start + 4, "free".toByteArray(Charsets.ISO_8859_1))
        var at = box.payload
        while (at < box.end) {
            val count = minOf(64 * 1024L, box.end - at).toInt()
            file.write(at, ByteArray(count))
            at += count
        }
        return 1
    }

    private fun int(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun long(bytes: ByteArray): Long = (int(bytes, 0).toLong() shl 32) or (int(bytes, 4).toLong() and 0xFFFFFFFFL)
}

/**
 * GPS out of a photo's XMP (LOGIKA §16.1): the `exif:GPS…` properties, as attributes or elements; the rest of the
 * packet – Ultra HDR's and the motion photo's description – stays as it is.
 */
object XmpGps {
    private val ATTRIBUTE = Regex("""\s+exif:GPS\w+\s*=\s*("[^"]*"|'[^']*')""")
    private val ELEMENT = Regex("""<exif:(GPS\w+)\b[^>]*?(/>|>.*?</exif:\1\s*>)""", RegexOption.DOT_MATCHES_ALL)

    fun strip(xmp: String): String = ELEMENT.replace(ATTRIBUTE.replace(xmp, ""), "")

    fun has(xmp: String): Boolean = ATTRIBUTE.containsMatchIn(xmp) || ELEMENT.containsMatchIn(xmp)
}

/** How each item of a share goes (LOGIKA §16.1, §16.9). */
enum class ShareWay {
    /** No location: the MediaStore address itself. */
    ORIGINAL,

    /** A copy without its location. */
    STRIPPED,

    /** A copy of the original, location included (the system would cut it for the receiving app). */
    WITH_LOCATION,
}

/**
 * A share's plan: how each item goes, and what the share sheet's line says – how many had a location, how many of
 * them keep it as their format can't lose it ([kept], D18).
 */
data class SharePlan(val ways: Map<Long, ShareWay>, val located: Int, val kept: Int) {
    companion object {
        /** Formats whose location the app takes out: EXIF (and XMP) of JPEG, PNG, WebP; the boxes of MP4, MOV, 3GP. */
        private val STRIPPABLE = setOf("image/jpeg", "image/png", "image/webp", "video/mp4", "video/quicktime", "video/3gpp")

        fun strippable(mime: String): Boolean = mime.lowercase() in STRIPPABLE

        fun of(items: List<MediaItem>, located: Set<Long>, removeLocation: Boolean): SharePlan {
            var kept = 0
            val ways = LinkedHashMap<Long, ShareWay>()
            for (item in items) {
                ways[item.id] = when {
                    item.id !in located -> ShareWay.ORIGINAL
                    !removeLocation -> ShareWay.WITH_LOCATION
                    strippable(item.mime) -> ShareWay.STRIPPED
                    else -> ShareWay.WITH_LOCATION.also { kept++ }
                }
            }
            return SharePlan(ways, items.count { it.id in located }, kept)
        }
    }
}
