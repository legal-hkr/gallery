package sh.hnet.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
    private fun state(path: String, newInPhotos: Boolean = false) = SourceRules.classify(path, newInPhotos)

    @Test
    fun rulesInOrder() {
        assertEquals(SourceState.IN_PHOTOS, state("DCIM/Camera/"))
        assertEquals(SourceState.IN_PHOTOS, state("DCIM/100CANON/"))
        assertEquals(SourceState.IN_PHOTOS, state("Pictures/Screenshots/"))
        // Sound folders: album covers
        assertEquals(SourceState.HIDDEN, state("Music/Kult/Spokojnie/"))
        assertEquals(SourceState.HIDDEN, state("audiobooks/"))
        // Stickers and apps' working files, but not the messengers' media
        assertEquals(SourceState.HIDDEN, state("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/"))
        assertEquals(SourceState.HIDDEN, state("Android/media/com.spotify.music/covers/"))
        assertEquals(SourceState.ALBUM_ONLY, state("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals(SourceState.ALBUM_ONLY, state("Android/media/org.telegram.messenger/Telegram/Telegram Video/"))
        // Screen recordings stay out of the timeline even with new folders in Photos
        assertEquals(SourceState.ALBUM_ONLY, state("Movies/Screen recordings/", newInPhotos = true))
        assertEquals(SourceState.ALBUM_ONLY, state("DCIM/ScreenRecorder/", newInPhotos = true))
        // Everything else: by the switch
        assertEquals(SourceState.ALBUM_ONLY, state("Download/"))
        assertEquals(SourceState.IN_PHOTOS, state("Pictures/WhatsApp/", newInPhotos = true))
    }

    @Test
    fun theOldChoiceBecomesTheUsers() {
        assertEquals(
            mapOf("external_primary:Download/" to SourceState.IN_PHOTOS, "external_primary:DCIM/Camera/" to SourceState.ALBUM_ONLY),
            SourceRules.fromTimelineChoice(setOf("external_primary:Download/"), setOf("external_primary:DCIM/Camera/")),
        )
    }

    @Test
    fun coversByName() {
        assertTrue(CoverNames.isCover("cover.jpg"))
        assertTrue(CoverNames.isCover("Folder.JPG"))
        assertTrue(CoverNames.isCover("AlbumArt_{1234}_Large.jpg"))
        assertTrue(CoverNames.isCover("albumartsmall.jpg"))
        assertFalse(CoverNames.isCover("cover photo.jpg"))
        assertFalse(CoverNames.isCover("IMG_20261119.jpg"))
    }

    private fun item(id: Long, path: String, name: String = "IMG_$id.jpg", trashed: Boolean = false) =
        MediaItem(id, false, "image/jpeg", id, name, 0, path, "external_primary", 1, 1, 1, 0, false, trashed, 0, false, 1)

    @Test
    fun whatIsShownWhere() {
        val items = listOf(
            item(1, "DCIM/Camera/"),
            item(2, "Download/"),
            item(3, "Music/Kult/"),
            item(4, "Download/", name = "cover.jpg"),
            item(5, "DCIM/Camera/", trashed = true),
        )
        val library = Library.of(items) { SourceRules.classify(it.path, false) }
        assertEquals(listOf(1L, 2L, 5L), library.visible.map { it.id })
        assertEquals(listOf(1L), library.timeline.map { it.id })
    }

    @Test
    fun mosaics() {
        assertEquals(0, Mosaic.cells(0).size)
        assertEquals(Mosaic.Cell(0f, 0f, 0.5f, 1f), Mosaic.cells(3)[0])
        assertEquals(4, Mosaic.cells(7).size)
    }
}

class FingerprintsTest {
    private fun item(id: Long, name: String, taken: Long = 1000, size: Long = 5, volume: String = "external_primary") =
        MediaItem(id, false, "image/jpeg", taken, name, 0, "DCIM/Camera/", volume, size, 1, 1, 0, false, false, 0, false, 1)

    private fun orphan(name: String, size: Long = 5, volume: String = "", albums: Set<Long> = setOf(1L)) = Fingerprints.Orphan(Fingerprints.of(item(0, name, size = size)), volume, albums)

    @Test
    fun relinksByNameDateAndSize() {
        val items = listOf(item(10, "A.jpg"), item(11, "B.jpg"), item(12, "B.jpg", size = 6))
        val orphans = mapOf(1L to orphan("A.jpg"), 2L to orphan("B.jpg", size = 6), 3L to orphan("C.jpg"))
        assertEquals(mapOf(1L to 10L, 2L to 12L), Fingerprints.relink(orphans, items))
    }

    @Test
    fun identicalFilesEachFindTheirOwn() {
        // Two copies of one photo in an album, both given new ids by a rescan
        val items = listOf(item(10, "A.jpg"), item(11, "A.jpg"))
        val moves = Fingerprints.relink(mapOf(1L to orphan("A.jpg"), 2L to orphan("A.jpg")), items)
        assertEquals(setOf(10L, 11L), moves.values.toSet())
        // Never one that is in its album already – but one in another album, yes
        assertEquals(mapOf(1L to 11L), Fingerprints.relink(mapOf(1L to orphan("A.jpg")), items, members = mapOf(1L to setOf(10L))))
        assertEquals(mapOf(1L to 10L), Fingerprints.relink(mapOf(1L to orphan("A.jpg")), items, members = mapOf(2L to setOf(10L))))
    }

    @Test
    fun aCardTakenOutWaitsForItsReturn() {
        // The card's photo and its copy on the phone; the card out, nothing of it in the index
        val phone = listOf(item(10, "A.jpg"))
        assertEquals(emptyMap<Long, Long>(), Fingerprints.relink(mapOf(1L to orphan("A.jpg", volume = "1234-abcd")), phone))
        // Back in, with a new id: the card's own copy, not the phone's
        val both = phone + item(20, "A.jpg", volume = "1234-abcd")
        assertEquals(mapOf(1L to 20L), Fingerprints.relink(mapOf(1L to orphan("A.jpg", volume = "1234-abcd")), both))
        // In, but its own file not indexed yet (a card being scanned): it waits, the phone's copy isn't taken
        val scanning = phone + item(21, "B.jpg", volume = "1234-abcd")
        assertEquals(emptyMap<Long, Long>(), Fingerprints.relink(mapOf(1L to orphan("A.jpg", volume = "1234-abcd")), scanning))
        // An older row without a volume, matched after the card's: the card's orphan keeps the card's copy
        val older = mapOf(2L to orphan("A.jpg"), 1L to orphan("A.jpg", volume = "1234-abcd", albums = setOf(2L)))
        assertEquals(mapOf(1L to 20L, 2L to 10L), Fingerprints.relink(older, both))
    }

    @Test
    fun aBackupsItemsFoundAndMissing() {
        val items = listOf(item(10, "A.jpg"), item(11, "B.jpg"))
        val (found, missing) = Fingerprints.match(listOf(Fingerprints.of(item(0, "A.jpg")), Fingerprints.of(item(0, "Z.jpg"))), items)
        assertEquals(listOf(10L), found)
        assertEquals(1, missing)
    }

    @Test
    fun albumNames() {
        assertEquals(AlbumNames.Problem.Empty, AlbumNames.problem("  ", emptyList()))
        assertEquals(AlbumNames.Problem.Exists, AlbumNames.problem(" aparat ", listOf("Aparat")))
        assertEquals(null, AlbumNames.problem("Wakacje: Chorwacja/2025", listOf("Aparat")))
    }
}
