package sh.hnet.gallery.data.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.system.ErrnoException
import android.system.OsConstants
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sh.hnet.gallery.data.originalUri
import sh.hnet.gallery.data.uri
import sh.hnet.gallery.facts.Fact
import sh.hnet.gallery.model.MediaItem
import sh.hnet.gallery.model.VaultEntry
import sh.hnet.gallery.model.VaultFormat
import sh.hnet.gallery.model.VaultIndex
import sh.hnet.gallery.model.VaultSpace

/**
 * The vault (SPEC §18, LOGIKA §9, §19.3–§19.4). A key in Android Keystore that needs the user every time (fingerprint,
 * or the screen lock's PIN or pattern) seals only the vault's master key, kept in `vault/key`; one prompt unseals it, and
 * it stays in memory until the vault locks. Every file has its own key, sealed by the master key ([VaultFormat]); the
 * index and the thumbnails are vault files too. Everything lives in `noBackupFilesDir/vault/`, out of every backup.
 */
class Vault(context: Context, private val scope: CoroutineScope) {
    sealed interface State {
        /** Never set up, or forgotten: the setup page. */
        data object NotSetUp : State

        /** Set up and locked: the prompt opens it. */
        data object Locked : State

        /** Its key is gone (the screen lock was switched off, credentials reset): [files] can't be read any more. */
        data class Lost(val files: Int) : State

        /** Open: its items, moved in last first. */
        data class Open(val entries: List<VaultEntry>) : State
    }

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val dir = File(appContext.noBackupFilesDir, "vault")
    private val keyFile = File(dir, "key")
    private val indexFile = File(dir, "index")
    private val random = SecureRandom()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<State>(State.NotSetUp)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The master key's bytes while open; zeroed when it locks. */
    private var master: ByteArray? = null
    private var index = VaultIndex()
    private val thumbnails = LruCache<String, ImageBitmap>(200)
    private var locking: Job? = null

    init {
        refresh()
    }

    private fun masterKey(): SecretKey = SecretKeySpec(master ?: throw IllegalStateException("Locked"), "AES")

    /** The state from what is on disk and in the Keystore (an open vault stays open). */
    fun refresh() {
        _state.value = when {
            !keyFile.exists() -> State.NotSetUp
            master != null -> State.Open(index.ordered)
            !keystoreHasKey() -> State.Lost(contentCount())
            else -> State.Locked
        }
    }

    private fun contentCount(): Int = dir.listFiles { file -> file.name.endsWith(DATA) }?.size ?: 0

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun keystoreHasKey(): Boolean = try {
        keystore().containsAlias(ALIAS)
    } catch (_: Exception) {
        false
    }

    // Setting up

    /**
     * A new Keystore key and the cipher to seal a new master key with, for the prompt ([created] after it); null when the
     * key can't be made (no secure screen lock).
     */
    fun prepareCreate(): Cipher? = try {
        keystore().deleteEntry(ALIAS)
        val key = try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    } catch (_: Exception) {
        null
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Every use asks (timeout 0): a fingerprint, or the screen lock as the alternative (SPEC §18.2)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // A new fingerprint must not lose the vault (CLAUDE.md, product rules)
            .setInvalidatedByBiometricEnrollment(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    /** The prompt passed with [cipher] from [prepareCreate]: a new master key sealed and kept, an empty index; open. */
    suspend fun created(cipher: Cipher) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val key = ByteArray(32).also(random::nextBytes)
            val sealed = cipher.doFinal(key)
            writeAtomically(keyFile, cipher.iv + sealed)
            master = key
            index = VaultIndex()
            saveIndex()
            _state.value = State.Open(index.ordered)
        }
    }

    // Unlocking and locking

    /** The cipher to unseal the master key with, for the prompt ([unlocked] after it); null when the key is lost. */
    fun prepareUnlock(): Cipher? {
        return try {
            val stored = keyFile.readBytes()
            val key = keystore().getKey(ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored, 0, IV)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            _state.value = State.Lost(contentCount())
            null
        } catch (_: Exception) {
            null
        }
    }

    /** The prompt passed with [cipher] from [prepareUnlock]: the master key in memory, the index read; open. */
    suspend fun unlocked(cipher: Cipher): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val stored = keyFile.readBytes()
                master = cipher.doFinal(stored, IV, stored.size - IV)
                index = if (indexFile.exists()) VaultIndex.decode(VaultFormat.Reader.of(masterKey(), indexFile.readBytes()).readAll()) else VaultIndex()
                _state.value = State.Open(index.ordered)
                true
            } catch (_: Exception) {
                lockNow()
                false
            }
        }
    }

    /** Locked (SPEC §18.4): the master key zeroed, the decrypted thumbnails dropped. */
    fun lock() {
        locking?.cancel()
        lockNow()
    }

    private fun lockNow() {
        master?.fill(0)
        master = null
        index = VaultIndex()
        thumbnails.evictAll()
        refresh()
    }

    /** The app went to the background: locks after [delayMs] unless it comes back first ([cancelLock]). */
    fun lockAfter(delayMs: Long) {
        locking?.cancel()
        if (master == null) return
        if (delayMs <= 0) return lock()
        locking = scope.launch {
            delay(delayMs)
            lockNow()
        }
    }

    /**
     * The vault's own system picker is over the app (import's files, export's folder, SPEC §18.6–§18.7): leaving for it
     * isn't leaving the app, so it doesn't lock then; the screen turning off still does.
     */
    @Volatile
    var picking = false

    fun cancelLock() {
        locking?.cancel()
        locking = null
    }

    // Content

    /** Whether moving [items] in fits on the phone's storage (LOGIKA §19.4). */
    fun fits(items: List<MediaItem>): Boolean = VaultSpace.fits(items.map { it.size }, StatFs(appContext.noBackupFilesDir.path).availableBytes)

    /**
     * [items] encrypted into the vault (the originals untouched – the caller deletes them, or [remove]s these entries
     * when that is refused); [onProgress] gets how many are done. The location stays in the file when the app may read
     * it ([located]). Returns the new entries; a failure removes what this call wrote and throws.
     */
    suspend fun moveIn(items: List<MediaItem>, facts: Map<Long, Fact>, albumsOf: (Long) -> List<Long>, located: Boolean, onProgress: (Int) -> Unit): List<VaultEntry> {
        val incoming = items.map { item ->
            Incoming(
                label = item.name,
                open = { resolver.openInputStream(if (located) item.originalUri() else item.uri()) },
                // The thumbnail from the original (a video's frame too); none is fine
                thumbnail = { thumbnailOf(item) },
                entry = { file, size ->
                    val fact = facts[item.id]
                    VaultEntry(
                        file = file, name = item.name, mime = item.mime, size = size,
                        width = item.width, height = item.height, durationMs = item.duration,
                        taken = item.taken, offsetMinutes = fact?.offsetMinutes, movedAt = System.currentTimeMillis(),
                        fromVolume = item.volume, fromPath = item.path, albums = albumsOf(item.id), gainmap = fact?.gainmap == true,
                    )
                },
            )
        }
        val (added, failures) = add(incoming, allOrNothing = true, onProgress = onProgress)
        if (failures.isNotEmpty()) throw IOException(failures.first())
        return added
    }

    /**
     * Files from anywhere the system's picker offers – a USB drive, a card, a folder (SPEC §18.6) – encrypted into the
     * vault without ever entering MediaStore; the name, the date from the file (EXIF, a video's own) or its provider,
     * the size and a thumbnail are taken on the way. Those that fail are skipped and named in the second list.
     */
    suspend fun importFiles(uris: List<Uri>, onProgress: (Int) -> Unit): Pair<List<VaultEntry>, List<String>> {
        // The providers' names and dates read off the main thread
        val incoming = withContext(Dispatchers.IO) { uris.map { uri -> ImportedFile(appContext, uri).incoming() } }
        return add(incoming, allOrNothing = false, onProgress = onProgress)
    }

    /** One file on its way in: how to read it, its thumbnail, and its entry once written as [file] of a size. */
    class Incoming(val label: String, val open: () -> InputStream?, val thumbnail: () -> ByteArray?, val entry: (file: String, size: Long) -> VaultEntry)

    /**
     * [incoming] encrypted into the vault and the index written once. [allOrNothing]: the first failure takes back
     * what this call wrote; else the failures are skipped. The entries added, and each failure as "name: error".
     */
    private suspend fun add(incoming: List<Incoming>, allOrNothing: Boolean, onProgress: (Int) -> Unit): Pair<List<VaultEntry>, List<String>> = withContext(Dispatchers.IO) {
        val key = masterKey()
        val added = ArrayList<VaultEntry>()
        val failures = ArrayList<String>()
        try {
            for ((done, one) in incoming.withIndex()) {
                ensureActive()
                val file = UUID.randomUUID().toString()
                try {
                    val written = one.open()?.use { input -> writeEncrypted(File(dir, file + DATA)) { out -> VaultFormat.encrypt(key, input, out, random) } }
                        ?: throw IOException("Can't be opened")
                    one.thumbnail()?.let { jpeg -> writeEncrypted(File(dir, file + THUMB)) { out -> VaultFormat.encrypt(key, jpeg.inputStream(), out, random) } }
                    added.add(one.entry(file, written))
                } catch (e: Exception) {
                    deleteFiles(file)
                    if (allOrNothing) throw e
                    failures.add(one.label + ": " + e.javaClass.simpleName + (e.message?.let { " – $it" } ?: ""))
                }
                onProgress(done + 1)
            }
            if (added.isNotEmpty()) {
                mutex.withLock {
                    index = index.copy(entries = index.entries + added)
                    saveIndex()
                    publish()
                }
            }
            added to failures
        } catch (e: Exception) {
            for (entry in added) deleteFiles(entry.file)
            if (allOrNothing) throw e
            added to (failures + (e.javaClass.simpleName))
        }
    }

    /**
     * The vault's items written decrypted into a folder the user chose (SPEC §18.7), under their own names (the
     * provider numbers a name taken); the vault keeps them. Each failure as "name: error".
     */
    suspend fun export(entries: List<VaultEntry>, tree: Uri, onProgress: (Int) -> Unit): List<String> = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val failures = ArrayList<String>()
        for ((done, entry) in entries.withIndex()) {
            ensureActive()
            var created: Uri? = null
            try {
                created = DocumentsContract.createDocument(resolver, parent, entry.mime, entry.name) ?: throw IOException("Not created")
                val (reader, stream) = open(entry)
                stream.use { reader.stream().use { from -> resolver.openOutputStream(created)?.use { to -> from.copyTo(to, 256 * 1024) } ?: throw IOException("Not writable") } }
            } catch (e: Exception) {
                // Half a file is no file
                created?.let { try { DocumentsContract.deleteDocument(resolver, it) } catch (_: Exception) { } }
                failures.add(entry.name + ": " + e.javaClass.simpleName + (e.message?.let { " – $it" } ?: ""))
            }
            onProgress(done + 1)
        }
        failures
    }

    /** How many items the vault holds (Forget the key's question); 0 while locked. */
    val count: Int get() = if (master == null) 0 else index.entries.size

    /** Out of the vault for good (SPEC §18.3), or a move in taken back. */
    suspend fun remove(entries: List<VaultEntry>) = withContext(Dispatchers.IO) {
        val files = entries.mapTo(HashSet()) { it.file }
        mutex.withLock {
            if (master != null) {
                index = index.copy(entries = index.entries.filter { it.file !in files })
                saveIndex()
            }
            files.forEach(::deleteFiles)
            files.forEach(thumbnails::remove)
            publish()
        }
    }

    /** The vault forgotten (Settings, S8c) or its unreadable content deleted (SPEC §18.8): the Keystore key and every file go. */
    suspend fun forget() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                keystore().deleteEntry(ALIAS)
            } catch (_: Exception) {
                // Gone already
            }
            master?.fill(0)
            master = null
            index = VaultIndex()
            thumbnails.evictAll()
            dir.listFiles()?.forEach { it.delete() }
            refresh()
        }
    }

    /** An item's thumbnail, decrypted into memory only (SPEC §18.3); null while locked or without one. */
    suspend fun thumbnail(entry: VaultEntry): ImageBitmap? = withContext(Dispatchers.IO) {
        thumbnails.get(entry.file)?.let { return@withContext it }
        val key = master?.let { SecretKeySpec(it, "AES") } ?: return@withContext null
        val file = File(dir, entry.file + THUMB)
        if (!file.exists()) return@withContext null
        try {
            val bytes = VaultFormat.Reader.of(key, file.readBytes()).readAll()
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: return@withContext null
            // Locked meanwhile: not kept
            if (master != null) thumbnails.put(entry.file, image)
            image
        } catch (_: Exception) {
            null
        }
    }

    /** An item of the open vault by its file; null while locked. */
    fun entry(file: String): VaultEntry? = if (master == null) null else index.entries.firstOrNull { it.file == file }

    /**
     * An item's content as a seekable read-only descriptor (the [VaultProvider]): reads come to [reader]'s chunks,
     * decrypted in memory; nothing is written anywhere. Null while locked or gone.
     */
    fun openDescriptor(file: String): ParcelFileDescriptor? {
        val entry = entry(file) ?: return null
        val (reader, stream) = try {
            open(entry)
        } catch (_: Exception) {
            return null
        }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = reader.size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                // Locked meanwhile: not a byte more, though the file's own key is still in this reader
                if (master == null) throw ErrnoException("read", OsConstants.EACCES)
                var done = 0
                try {
                    while (done < size) {
                        val n = reader.read(offset + done, data, done, size - done)
                        if (n <= 0) break
                        done += n
                    }
                } catch (_: Exception) {
                    throw ErrnoException("read", OsConstants.EIO)
                }
                return done
            }

            override fun onRelease() = stream.close()
        }
        return try {
            appContext.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, io)
        } catch (_: Exception) {
            stream.close()
            null
        }
    }

    /** The proxy descriptors' reads, on a thread of their own. */
    private val io: Handler by lazy { Handler(HandlerThread("vault").apply { start() }.looper) }

    /** A vault item as the viewer's item: from outside MediaStore, read through the [VaultProvider]. */
    fun item(entry: VaultEntry): MediaItem = MediaItem(
        id = idOf(entry.file), video = entry.video, mime = entry.mime, taken = entry.taken, name = entry.name,
        bucketId = 0, path = "", volume = "", size = entry.size, width = entry.width, height = entry.height,
        duration = entry.durationMs, favorite = false, trashed = false, expires = 0, ours = true, generation = 0,
        added = entry.movedAt, source = "content://${appContext.packageName}.vault/${entry.file}",
    )

    /** A reader of an item's content (the viewer, moving out); the caller closes the stream. */
    fun open(entry: VaultEntry): Pair<VaultFormat.Reader, FileInputStream> {
        val stream = FileInputStream(File(dir, entry.file + DATA))
        return VaultFormat.Reader.of(masterKey(), stream.channel) to stream
    }

    private fun thumbnailOf(item: MediaItem): ByteArray? = try {
        val bitmap = resolver.loadThumbnail(item.uri(), Size(THUMB_PX, THUMB_PX), null)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray().also { bitmap.recycle() }
    } catch (_: Exception) {
        null
    }

    private fun <T> writeEncrypted(target: File, write: (OutputStream) -> T): T {
        dir.mkdirs()
        val part = File(dir, target.name + ".part")
        val result = part.outputStream().buffered().use(write)
        if (!part.renameTo(target)) throw IOException("Can't keep ${target.name}")
        return result
    }

    private fun saveIndex() {
        val bytes = index.encode()
        writeEncrypted(indexFile) { out -> VaultFormat.encrypt(masterKey(), bytes.inputStream(), out, random) }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val part = File(dir, target.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(target)) throw IOException("Can't keep ${target.name}")
    }

    private fun deleteFiles(file: String) {
        File(dir, file + DATA).delete()
        File(dir, file + THUMB).delete()
    }

    private fun publish() {
        _state.value = if (master != null) State.Open(index.ordered) else State.Locked
    }

    companion object {
        /** A vault item's id as an item (negative, as every item from outside MediaStore), stable for its file. */
        fun idOf(file: String): Long = -1 - (UUID.fromString(file).mostSignificantBits and Long.MAX_VALUE)

        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = 12
        private const val DATA = ".data"
        private const val THUMB = ".thumb"
        private const val THUMB_PX = 512
    }
}
