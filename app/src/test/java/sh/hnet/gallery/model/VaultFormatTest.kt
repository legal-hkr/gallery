package sh.hnet.gallery.model

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VaultFormatTest {
    private val master = VaultFormat.newKey(SecureRandom())

    private fun sealed(content: ByteArray): ByteArray = ByteArrayOutputStream().also { VaultFormat.encrypt(master, content.inputStream(), it) }.toByteArray()

    private fun content(size: Int) = Random(size).nextBytes(size)

    @Test
    fun everySizeComesBackWhole() {
        val c = VaultFormat.CHUNK
        for (size in listOf(0, 1, 100, c - 1, c, c + 1, 3 * c, 3 * c + 17)) {
            val plain = content(size)
            val reader = VaultFormat.Reader.of(master, sealed(plain))
            assertEquals(size.toLong(), reader.size)
            assertArrayEquals(plain, reader.readAll())
            assertArrayEquals(plain, reader.stream().readBytes())
        }
    }

    @Test
    fun readsAnyPlace() {
        val plain = content(5 * VaultFormat.CHUNK + 1234)
        val reader = VaultFormat.Reader.of(master, sealed(plain))
        for (position in listOf(0L, 1L, VaultFormat.CHUNK - 3L, 2L * VaultFormat.CHUNK + 5, plain.size - 10L)) {
            val tail = reader.stream(position).readBytes()
            assertArrayEquals(plain.copyOfRange(position.toInt(), plain.size), tail)
        }
        assertEquals(-1, reader.read(plain.size.toLong(), ByteArray(4), 0, 4))
    }

    @Test
    fun aCutFileIsFound() {
        val plain = content(3 * VaultFormat.CHUNK)
        val file = sealed(plain)
        // Cut at the end of a whole chunk: the new last one was sealed as not last
        val oneChunkLess = file.copyOf(file.size - (VaultFormat.CHUNK + 16))
        assertThrows(VaultFormat.Broken::class.java) { VaultFormat.Reader.of(master, oneChunkLess).readAll() }
        // Cut inside a chunk
        val cut = file.copyOf(file.size - 100)
        assertThrows(VaultFormat.Broken::class.java) { VaultFormat.Reader.of(master, cut).readAll() }
    }

    @Test
    fun aChangedByteIsFound() {
        val file = sealed(content(2 * VaultFormat.CHUNK))
        file[VaultFormat.HEADER + VaultFormat.CHUNK + 40] = (file[VaultFormat.HEADER + VaultFormat.CHUNK + 40] + 1).toByte()
        val reader = VaultFormat.Reader.of(master, file)
        assertEquals(VaultFormat.CHUNK, reader.chunk(0).size)
        assertThrows(VaultFormat.Broken::class.java) { reader.chunk(1) }
    }

    @Test
    fun anotherKeyReadsNothing() {
        val file = sealed(content(10))
        assertThrows(VaultFormat.Broken::class.java) { VaultFormat.Reader.of(VaultFormat.newKey(), file) }
        assertThrows(VaultFormat.Broken::class.java) { VaultFormat.Reader.of(master, ByteArray(200)) }
    }

    @Test
    fun aWrappedKeyComesBack() {
        val key = VaultFormat.newKey()
        assertArrayEquals(key.encoded, VaultFormat.unwrap(master, VaultFormat.wrap(master, key)).encoded)
    }
}
