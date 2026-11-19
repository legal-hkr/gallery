package sh.hnet.gallery.facts

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.driver.AndroidSQLiteDriver

/**
 * What the app read from one file, which MediaStore doesn't say (LOGIKA §2.3). Derived from
 * MediaStore: a row is valid while the item's [generation] is the same, and goes with the item.
 *
 * @param id The item's MediaStore id
 * @param generation The item's `GENERATION_MODIFIED` when read; a different one means the file changed
 * @param parser [FactReader.VERSION] when read; an older one is read again
 * @param located Whether the location could be read (the app had `ACCESS_MEDIA_LOCATION`); read again once it can
 * @param offsetMinutes The zone the photo was taken in (`OffsetTimeOriginal`)
 * @param motionLength A motion photo's video length in bytes from the file's end, when the file says
 */
@Entity(tableName = "fact")
data class Fact(
    @PrimaryKey val id: Long,
    val generation: Long,
    val parser: Int,
    val located: Boolean,
    val offsetMinutes: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val motion: Boolean,
    val motionLength: Long?,
    val gainmap: Boolean,
)

@Dao
interface FactDao {
    @Query("SELECT * FROM fact")
    suspend fun all(): List<Fact>

    @Upsert
    suspend fun upsert(facts: List<Fact>)

    @Query("DELETE FROM fact WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

/** The app's database: only data derived from MediaStore or the files, rebuilt if lost (LOGIKA §2.3). */
@Database(entities = [Fact::class], version = 1)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun factDao(): FactDao

    companion object {
        fun create(context: Context): GalleryDatabase =
            Room.databaseBuilder<GalleryDatabase>(context.applicationContext, "gallery.db")
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
