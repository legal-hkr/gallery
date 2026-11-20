package sh.hnet.gallery.model

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A vault file (LOGIKA §9): its own random key, wrapped by the vault's master key in the header, and the content in
 * chunks of [CHUNK] bytes, each sealed with AES-GCM on its own – so a video seeks by decrypting only the chunks it
 * needs. A chunk's nonce is the file's 8 random bytes and the chunk's number; the last chunk is sealed with a flag in
 * its associated data, so a file cut at a chunk's end doesn't read as whole.
 *
 * Layout: magic `HGV1`, version 1, the wrapped key (12-byte nonce, 32-byte key sealed with its 16-byte tag), the nonce
 * prefix (8 bytes); then the chunks, each its ciphertext and 16-byte tag. An empty content is one empty last chunk.
 */
object VaultFormat {
    const val CHUNK = 64 * 1024
    private const val TAG = 16
    private const val SEALED = CHUNK + TAG
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val VERSION: Byte = 1
    private const val KEY = 32
    private const val NONCE = 12
    private const val PREFIX = 8
    private const val WRAPPED = NONCE + KEY + TAG
    const val HEADER = 4 + 1 + WRAPPED + PREFIX

    /** The file can't be read: not a vault file, another key, or changed or cut. */
    class Broken(message: String, cause: Throwable? = null) : IOException(message, cause)

    /** A new random key (the master key, a file's key). */
    fun newKey(random: SecureRandom = SecureRandom()): SecretKey = SecretKeySpec(ByteArray(KEY).also(random::nextBytes), "AES")

    /** [key]'s bytes sealed with [master]: a 12-byte nonce, then the sealed key. */
    fun wrap(master: SecretKey, key: SecretKey, random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, master, GCMParameterSpec(TAG * 8, nonce)) }
        return nonce + cipher.doFinal(key.encoded)
    }

    fun unwrap(master: SecretKey, wrapped: ByteArray): SecretKey {
        if (wrapped.size != WRAPPED) throw Broken("Wrapped key of ${wrapped.size} bytes")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(TAG * 8, wrapped, 0, NONCE)) }
        return try {
            SecretKeySpec(cipher.doFinal(wrapped, NONCE, wrapped.size - NONCE), "AES")
        } catch (e: GeneralSecurityException) {
            throw Broken("Wrong key", e)
        }
    }

    /**
     * [input] encrypted to [output] under a new file key wrapped by [master]; [onProgress] gets the bytes read so far.
     * Returns the content's size.
     */
    fun encrypt(master: SecretKey, input: InputStream, output: OutputStream, random: SecureRandom = SecureRandom(), onProgress: (Long) -> Unit = {}): Long {
        val key = newKey(random)
        val prefix = ByteArray(PREFIX).also(random::nextBytes)
        output.write(MAGIC)
        output.write(VERSION.toInt())
        output.write(wrap(master, key, random))
        output.write(prefix)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // One chunk ahead: a chunk is the last only when nothing follows it
        var current = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var currentLength = readFully(input, current)
        var index = 0
        var total = 0L
        while (true) {
            val nextLength = if (currentLength == CHUNK) readFully(input, next) else 0
            val last = nextLength == 0
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index)))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            output.write(cipher.doFinal(current, 0, currentLength))
            total += currentLength
            onProgress(total)
            if (last) break
            val swap = current
            current = next
            next = swap
            currentLength = nextLength
            index++
        }
        return total
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun nonce(prefix: ByteArray, index: Int): ByteArray = ByteBuffer.allocate(NONCE).put(prefix).putInt(index).array()

    /**
     * Reads a vault file at any place: only the chunks asked for are decrypted (the last one kept). [length] is the
     * file's size; [readAt] fills a buffer from a place in the file.
     */
    class Reader(master: SecretKey, private val length: Long, private val readAt: (position: Long, buffer: ByteBuffer) -> Unit) {
        private val key: SecretKey
        private val prefix: ByteArray
        private val chunks: Long

        /** The content's size. */
        val size: Long

        init {
            if (length < HEADER + TAG) throw Broken("File of $length bytes")
            val header = ByteBuffer.allocate(HEADER)
            readAt(0, header)
            val bytes = header.array()
            if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4] != VERSION) throw Broken("Not a vault file")
            key = unwrap(master, bytes.copyOfRange(5, 5 + WRAPPED))
            prefix = bytes.copyOfRange(5 + WRAPPED, HEADER)
            val body = length - HEADER
            chunks = (body + SEALED - 1) / SEALED
            val lastSealed = body - (chunks - 1) * SEALED
            if (lastSealed < TAG) throw Broken("Cut in a tag")
            size = (chunks - 1) * CHUNK + (lastSealed - TAG)
        }

        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var cachedIndex = -1L
        private var cached = ByteArray(0)

        /** Chunk [index], decrypted and checked. */
        @Synchronized
        fun chunk(index: Long): ByteArray {
            if (index == cachedIndex) return cached
            if (index !in 0 until chunks) throw EOFException("Chunk $index of $chunks")
            val last = index == chunks - 1
            val start = HEADER + index * SEALED
            val sealed = ByteBuffer.allocate((if (last) length - start else SEALED.toLong()).toInt())
            readAt(start, sealed)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index.toInt())))
            cipher.updateAAD(byteArrayOf(if (last) 1 else 0))
            val plain = try {
                cipher.doFinal(sealed.array())
            } catch (e: GeneralSecurityException) {
                throw Broken("Chunk $index changed, or the file was cut", e)
            }
            cachedIndex = index
            cached = plain
            return plain
        }

        /** Up to [count] bytes of the content from [position] into [into] at [offset]; -1 at the end. */
        fun read(position: Long, into: ByteArray, offset: Int, count: Int): Int {
            if (position >= size) return -1
            val index = position / CHUNK
            val plain = chunk(index)
            val within = (position - index * CHUNK).toInt()
            val n = minOf(count, plain.size - within)
            System.arraycopy(plain, within, into, offset, n)
            return n
        }

        /** The content from [from] as a stream. */
        fun stream(from: Long = 0): InputStream = object : InputStream() {
            private var position = from
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = this@Reader.read(position, b, off, len)
                if (n > 0) position += n
                return n
            }

            override fun skip(n: Long): Long {
                val skipped = minOf(n, size - position).coerceAtLeast(0)
                position += skipped
                return skipped
            }

            override fun available(): Int = minOf(size - position, Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(0)
        }

        /** The whole content in memory (a photo for decoding). */
        fun readAll(): ByteArray {
            if (size > Int.MAX_VALUE) throw Broken("Too large to read whole")
            val out = ByteArray(size.toInt())
            var at = 0
            while (at < out.size) at += read(at.toLong(), out, at, out.size - at)
            return out
        }

        companion object {
            /** A reader over an open file's channel. */
            fun of(master: SecretKey, channel: FileChannel): Reader = Reader(master, channel.size()) { position, buffer ->
                var at = position
                while (buffer.hasRemaining()) {
                    val n = channel.read(buffer, at)
                    if (n < 0) throw Broken("Cut at $at")
                    at += n
                }
            }

            /** A reader over bytes in memory (the index, a thumbnail). */
            fun of(master: SecretKey, bytes: ByteArray): Reader = Reader(master, bytes.size.toLong()) { position, buffer ->
                val n = buffer.remaining()
                if (position + n > bytes.size) throw Broken("Cut at $position")
                buffer.put(bytes, position.toInt(), n)
            }
        }
    }
}
