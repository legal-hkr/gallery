package sh.hnet.gallery.data.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import sh.hnet.gallery.GalleryApplication

/**
 * The open vault's content for the app itself (S8b2, LOGIKA §19.4): `content://<package>.vault/<file>` reads an item
 * decrypted on the fly through a seekable proxy descriptor – only the chunks asked for are decrypted, in memory; no
 * file is behind it – so the viewer, its zoom tiles, the video player and Info read a vault item as any file. Not
 * exported and granting nothing: no other app can read it. Locked, nothing opens.
 */
class VaultProvider : ContentProvider() {
    private val vault: Vault get() = (context!!.applicationContext as GalleryApplication).vault

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val file = uri.lastPathSegment ?: throw FileNotFoundException("No file")
        return vault.openDescriptor(file) ?: throw FileNotFoundException("Locked or gone")
    }

    override fun getType(uri: Uri): String? = uri.lastPathSegment?.let(vault::entry)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val entry = uri.lastPathSegment?.let(vault::entry) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> entry.name
                        OpenableColumns.SIZE -> entry.size
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
