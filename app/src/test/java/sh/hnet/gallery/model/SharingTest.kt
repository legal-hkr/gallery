package sh.hnet.gallery.model

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingTest {
    private fun box(type: String, vararg children: ByteArray, payload: ByteArray = ByteArray(0), large: Boolean = false): ByteArray {
        val body = ByteArrayOutputStream().apply { write(payload); children.forEach { write(it) } }.toByteArray()
        val out = ByteArrayOutputStream()
        if (large) {
            out.write(int(1)); out.write(type.toByteArray(Charsets.ISO_8859_1)); out.write(int(0)); out.write(int(16 + body.size))
        } else {
            out.write(int(8 + body.size)); out.write(type.toByteArray(Charsets.ISO_8859_1))
        }
        out.write(body)
        return out.toByteArray()
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun keys(vararg names: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(int(0)); write(int(names.size))
            for (name in names) { val n = name.toByteArray(); write(int(8 + n.size)); write("mdta".toByteArray()); write(n) }
        }.toByteArray()
        return box("keys", payload = body)
    }

    private fun item(index: Int, value: String) = box("    ", payload = box("data", payload = int(1) + int(0) + value.toByteArray())).also {
        // The item's type is its key's index
        val i = int(index); for (k in 0..3) it[4 + k] = i[k]
    }

    @Test
    fun clearsUdtaLocationKeepingSizes() {
        val xyz = box("©xyz", payload = byteArrayOf(0, 18, 0x15, 0xC7.toByte()) + "+40.7580-073.9855/".toByteArray())
        val file = box("ftyp", payload = "isom".toByteArray()) + box("moov", box("mvhd", payload = ByteArray(20)), box("udta", xyz)) + box("mdat", payload = ByteArray(100) { 7 }, large = true)
        val copy = file.copyOf()
        assertEquals(1, Mp4Location.strip(ArrayBytes(copy)))
        assertEquals(file.size, copy.size)
        assertFalse(String(copy, Charsets.ISO_8859_1).contains("40.7580"))
        assertTrue(String(copy, Charsets.ISO_8859_1).contains("free"))
        // mdat untouched
        assertArrayEquals(file.copyOfRange(file.size - 100, file.size), copy.copyOfRange(copy.size - 100, copy.size))
    }

    @Test
    fun clearsAppleLocationItemsOnly() {
        val meta = box(
            "meta",
            box("hdlr", payload = ByteArray(24)),
            keys("com.apple.quicktime.make", "com.apple.quicktime.location.ISO6709"),
            box("ilst", item(1, "Apple"), item(2, "+52.2297+021.0122+100.000/")),
        )
        val file = box("moov", meta)
        val copy = file.copyOf()
        assertEquals(1, Mp4Location.strip(ArrayBytes(copy)))
        val text = String(copy, Charsets.ISO_8859_1)
        assertTrue(text.contains("Apple"))
        assertFalse(text.contains("52.2297"))
    }

    @Test
    fun fileWithoutLocationUntouched() {
        val file = box("ftyp", payload = "isom".toByteArray()) + box("mdat", payload = ByteArray(50)) + box("moov", box("udta", box("name", payload = "x".toByteArray())))
        val copy = file.copyOf()
        assertEquals(0, Mp4Location.strip(ArrayBytes(copy)))
        assertArrayEquals(file, copy)
    }

    @Test
    fun xmpGpsOut() {
        val xmp = """<rdf:Description exif:GPSLatitude="52,13.78N" exif:GPSLongitude='21,0.73E' hdrgm:Version="1.0"><exif:GPSAltitude>100/1</exif:GPSAltitude><exif:GPSVersionID><rdf:Seq><rdf:li>2</rdf:li></rdf:Seq></exif:GPSVersionID><Container:Directory/></rdf:Description>"""
        val clean = XmpGps.strip(xmp)
        assertFalse(clean.contains("GPS"))
        assertTrue(clean.contains("hdrgm:Version=\"1.0\""))
        assertTrue(clean.contains("<Container:Directory/>"))
        assertTrue(XmpGps.has(xmp))
        assertFalse(XmpGps.has(clean))
    }

    private fun media(id: Long, mime: String) = MediaItem(id, mime.startsWith("video"), mime, id, "f$id", 0, "DCIM/Camera/", "external_primary", 1, 1, 1, 0, false, false, 0, false, 1)

    @Test
    fun planOfAShare() {
        val items = listOf(media(1, "image/jpeg"), media(2, "image/heic"), media(3, "video/mp4"), media(4, "image/jpeg"))
        val located = setOf(1L, 2L, 3L)
        val removing = SharePlan.of(items, located, removeLocation = true)
        assertEquals(mapOf(1L to ShareWay.STRIPPED, 2L to ShareWay.WITH_LOCATION, 3L to ShareWay.STRIPPED, 4L to ShareWay.ORIGINAL), removing.ways)
        assertEquals(3, removing.located)
        assertEquals(1, removing.kept)
        val including = SharePlan.of(items, located, removeLocation = false)
        assertEquals(ShareWay.WITH_LOCATION, including.ways[1L])
        assertEquals(0, including.kept)
    }
}

class StoragePathsTest {
    @Test
    fun pathsAsMediaStoreNamesThem() {
        assertEquals(Triple("external_primary", "Download/", "TEST_F1.jpg"), StoragePaths.split("/storage/emulated/0/Download/TEST_F1.jpg", "external_primary"))
        assertEquals(Triple("external_primary", "DCIM/Camera/", "a.jpg"), StoragePaths.split("/storage/emulated/0/DCIM/Camera/a.jpg", "external_primary"))
        assertEquals(Triple("1234-5678", "2024/", "a.jpg"), StoragePaths.split("/storage/1234-5678/2024/a.jpg", "external_primary"))
        assertEquals(Triple("external_primary", "", "a.jpg"), StoragePaths.split("/storage/emulated/0/a.jpg", "external_primary"))
        assertEquals(null, StoragePaths.split("/data/user/0/x/a.jpg", "external_primary"))
    }
}
