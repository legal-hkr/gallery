package sh.hnet.gallery.facts

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.flow.Flow

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

/**
 * A folder's state (SPEC-albums §9.1, §9.12), by [sh.hnet.gallery.model.FolderKey.key]: given by the rules the
 * first time the app sees it ([chosenByUser] false), the user's once set. [lastSeenAt]: a folder gone keeps its
 * state for 90 days in case it comes back. Pins and sorts of its album come with S4b2.
 */
@Entity(tableName = "source")
data class SourceRow(
    @PrimaryKey val key: String,
    val state: String,
    val chosenByUser: Boolean,
    val pinnedAt: Long? = null,
    val sort: String? = null,
    val lastSeenAt: Long,
)

/** A user album (SPEC-albums §9.12): a set kept by the app. Used from S4b2. */
@Entity(tableName = "album")
data class AlbumRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val lastAddedAt: Long,
    val pinnedAt: Long? = null,
    val sort: String? = null,
)

/**
 * An item in a user album (SPEC-albums §9.12): by MediaStore id, and by [fingerprint] (name, date taken,
 * size) to find it again after a rescan changed the ids or on another phone after a restore.
 */
@Entity(tableName = "album_item", primaryKeys = ["albumId", "mediaId"], indices = [Index("mediaId")])
data class AlbumItemRow(
    val albumId: Long,
    val mediaId: Long,
    val fingerprint: String,
    val addedAt: Long,
)

@Dao
interface SourceDao {
    @Query("SELECT * FROM source")
    fun watch(): Flow<List<SourceRow>>

    @Query("SELECT * FROM source")
    suspend fun all(): List<SourceRow>

    @Upsert
    suspend fun upsert(rows: List<SourceRow>)

    @Query("UPDATE source SET sort = :sort WHERE `key` = :key")
    suspend fun setSort(key: String, sort: String?)

    @Query("UPDATE source SET pinnedAt = :pinnedAt WHERE `key` = :key")
    suspend fun setPinned(key: String, pinnedAt: Long?)

    @Query("DELETE FROM source WHERE `key` IN (:keys)")
    suspend fun delete(keys: List<String>)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM album")
    fun watchAlbums(): Flow<List<AlbumRow>>

    @Query("SELECT * FROM album_item")
    fun watchItems(): Flow<List<AlbumItemRow>>

    @Query("SELECT * FROM album")
    suspend fun albums(): List<AlbumRow>

    @Query("SELECT * FROM album_item")
    suspend fun items(): List<AlbumItemRow>

    @Query("SELECT * FROM album_item WHERE albumId = :album")
    suspend fun itemsOf(album: Long): List<AlbumItemRow>

    @Insert
    suspend fun insert(album: AlbumRow): Long

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumRow>)

    @Upsert
    suspend fun upsertItems(items: List<AlbumItemRow>)

    @Query("UPDATE album SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE album SET sort = :sort WHERE id = :id")
    suspend fun setSort(id: Long, sort: String?)

    @Query("UPDATE album SET pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinnedAt: Long?)

    @Query("UPDATE album SET lastAddedAt = :at WHERE id = :id")
    suspend fun touched(id: Long, at: Long)

    @Query("DELETE FROM album WHERE id IN (:ids)")
    suspend fun deleteAlbums(ids: List<Long>)

    @Query("DELETE FROM album_item WHERE albumId IN (:ids)")
    suspend fun deleteItemsOfAlbums(ids: List<Long>)

    @Query("DELETE FROM album_item WHERE albumId = :album AND mediaId IN (:media)")
    suspend fun removeItems(album: Long, media: List<Long>)

    @Query("DELETE FROM album_item WHERE mediaId IN (:media)")
    suspend fun forget(media: List<Long>)

    @Query("UPDATE album_item SET mediaId = :to, fingerprint = :fingerprint WHERE mediaId = :from")
    suspend fun relink(from: Long, to: Long, fingerprint: String)
}

/**
 * The app's database (LOGIKA §2.3, §15.3): facts derived from the files, rebuilt if lost; since version 2
 * also the user's own data – the sources' states and the albums – which the backup carries.
 */
@Database(entities = [Fact::class, SourceRow::class, AlbumRow::class, AlbumItemRow::class], version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun factDao(): FactDao

    abstract fun sourceDao(): SourceDao

    abstract fun albumDao(): AlbumDao

    companion object {
        fun create(context: Context): GalleryDatabase =
            Room.databaseBuilder<GalleryDatabase>(context.applicationContext, "gallery.db")
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
