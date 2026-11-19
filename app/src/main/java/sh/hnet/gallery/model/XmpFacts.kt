package sh.hnet.gallery.model

/**
 * What a photo's XMP says about motion and HDR (LOGIKA §2.3, SPEC §22.5), read without an XML
 * parser: XMP is written by cameras in a few fixed shapes, and a property may be an attribute
 * (`GCamera:MotionPhoto="1"`) or an element (`<GCamera:MotionPhoto>1</GCamera:MotionPhoto>`), with
 * any namespace prefix. Checked against real files before S2 (LOGIKA §5).
 *
 * @param motion A motion photo: Google's `MotionPhoto` (also as written by Samsung) or the older `MicroVideo`
 * @param motionLength The embedded video's length in bytes, counted from the file's end; null when the XMP doesn't say
 * @param gainmap An Ultra HDR photo: `hdrgm:Version`, or a container item with the `GainMap` semantic
 */
data class XmpFacts(val motion: Boolean, val motionLength: Long?, val gainmap: Boolean) {
    companion object {
        val NONE = XmpFacts(motion = false, motionLength = null, gainmap = false)

        fun parse(xmp: String?): XmpFacts {
            if (xmp.isNullOrEmpty()) return NONE
            val items = containerItems(xmp)
            val motionItem = items.firstOrNull { it["Semantic"] == "MotionPhoto" }
            val microVideo = property(xmp, "MicroVideo") == "1"
            val motion = property(xmp, "MotionPhoto") == "1" || microVideo
            val length = motionItem?.get("Length")?.toLongOrNull()?.takeIf { it > 0 }
                ?: if (microVideo) property(xmp, "MicroVideoOffset")?.toLongOrNull()?.takeIf { it > 0 } else null
            val gainmap = property(xmp, "Version", prefix = "hdrgm") != null || items.any { it["Semantic"] == "GainMap" }
            return XmpFacts(motion, if (motion) length else null, gainmap)
        }

        /** A property's value by its local name, as an attribute or as a simple element; [prefix] limits it to one namespace prefix. */
        internal fun property(xmp: String, name: String, prefix: String? = null): String? {
            val p = prefix?.let { Regex.escape(it) } ?: "[A-Za-z][\\w.-]*"
            Regex("""\b$p:$name\s*=\s*(["'])(.*?)\1""").find(xmp)?.let { return it.groupValues[2].trim() }
            Regex("""<($p):$name\s*>\s*([^<]*?)\s*</\1:$name\s*>""").find(xmp)?.let { return it.groupValues[2].trim() }
            return null
        }

        /** The `Container:Directory` items, each as its properties (`Semantic`, `Mime`, `Length`, `Padding`) without prefixes. */
        internal fun containerItems(xmp: String): List<Map<String, String>> {
            val items = ArrayList<Map<String, String>>()
            // An item is a <…:Item …/> or <…:Item …>…</…:Item> element inside the directory; its fields are attributes or child elements
            val item = Regex("""<([A-Za-z][\w.-]*):Item\b([^>]*?)(/>|>(.*?)</\1:Item\s*>)""", RegexOption.DOT_MATCHES_ALL)
            val attribute = Regex("""\b[A-Za-z][\w.-]*:(\w+)\s*=\s*(["'])(.*?)\2""")
            val element = Regex("""<([A-Za-z][\w.-]*):(\w+)\s*>\s*([^<]*?)\s*</\1:\2\s*>""")
            for (match in item.findAll(xmp)) {
                val fields = HashMap<String, String>()
                attribute.findAll(match.groupValues[2]).forEach { fields[it.groupValues[1]] = it.groupValues[3].trim() }
                element.findAll(match.groupValues[4]).forEach { fields[it.groupValues[2]] = it.groupValues[3] }
                if (fields.isNotEmpty()) items.add(fields)
            }
            return items
        }

        /** An EXIF offset (`+01:00`, `-05:30`, `Z`) in minutes; null when missing or malformed. */
        fun offsetMinutes(value: String?): Int? {
            val text = value?.trim() ?: return null
            if (text == "Z") return 0
            val match = Regex("""([+-])(\d{2}):?(\d{2})""").matchEntire(text) ?: return null
            val minutes = match.groupValues[2].toInt() * 60 + match.groupValues[3].toInt()
            if (minutes > 18 * 60) return null
            return if (match.groupValues[1] == "-") -minutes else minutes
        }
    }
}
