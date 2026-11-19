package sh.hnet.gallery.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val file = BackupFile(
        appVersion = "0.1.0",
        appVersionCode = 1,
        createdAt = "2026-11-19T10:00:00Z",
        settings = BackupSettings(
            gridStep = "SMALL",
            timelineOn = listOf("external_primary:Pictures/WhatsApp/"),
            timelineOff = listOf("external_primary:DCIM/Screenshots/"),
            newAlbumsInPhotos = false,
            mediaManagementOffers = 1,
        ),
    )

    @Test
    fun roundTrips() {
        assertEquals(BackupCheck.Valid(file), Backup.check(Backup.encode(file)))
    }

    @Test
    fun holdsNoMedia() {
        // Settings only (SPEC §20.2): no item ids, file names, places or the vault
        val text = Backup.encode(file)
        for (field in listOf("\"id\"", "displayName", "latitude", "vault", ".jpg")) assertTrue(field, field !in text)
    }

    @Test
    fun rejectsOtherFilesAndNewerFormats() {
        assertTrue(Backup.check("not json") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format":"sh.hnet.gallery.backup","formatVersion":{}}""") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format":"sh.hnet.contacts.backup","formatVersion":1}""") is BackupCheck.NotOurs)
        assertEquals(BackupCheck.Newer(2), Backup.check("""{"format":"sh.hnet.gallery.backup","formatVersion":2}"""))
    }

    @Test
    fun missingSettingsStayAbsent() {
        // A file from before a setting existed leaves it null, so a restore keeps the current value
        val check = Backup.check("""{"format":"sh.hnet.gallery.backup","formatVersion":1,"appVersion":"0.1.0","appVersionCode":1,"createdAt":"x","settings":{"gridStep":"LARGE"},"extra":1}""")
        assertEquals(BackupSettings(gridStep = "LARGE"), (check as BackupCheck.Valid).backup.settings)
    }
}
