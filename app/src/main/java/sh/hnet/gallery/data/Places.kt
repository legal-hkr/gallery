package sh.hnet.gallery.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import sh.hnet.gallery.model.PlaceData

/**
 * The place data inside the app (LOGIKA §17.3, D22): `assets/places.bin.gz` in the repository, which the build
 * puts in the APK unpacked as `assets/places.bin` (zip-compressed there); copied once per app version into
 * `noBackupFilesDir/places/` and mapped from there, so the system pages in only what is read.
 */
object Places {
    private const val ASSET = "places.bin"

    /** The data, or null when the app has none (a build without the asset) or it can't be read. Blocking: call off the main thread. */
    fun load(context: Context): PlaceData? {
        val folder = File(context.noBackupFilesDir, "places").apply { mkdirs() }
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val file = File(folder, "places-$stamp.bin")
        return try {
            if (!file.exists()) {
                // An older app's copy goes; the new one is written whole before it counts
                folder.listFiles()?.forEach { it.delete() }
                val part = File(folder, "places.part")
                context.assets.open(ASSET).use { input -> part.outputStream().use { input.copyTo(it, 256 * 1024) } }
                if (!part.renameTo(file)) return null
            }
            RandomAccessFile(file, "r").use { raf -> PlaceData(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())) }
        } catch (e: Exception) {
            Log.w("Places", "No place data: $e")
            null
        }
    }
}
