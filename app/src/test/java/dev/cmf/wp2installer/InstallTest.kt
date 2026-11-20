package dev.cmf.wp2installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class InstallTest {

    private fun put32(b: ByteArray, at: Int, v: Long) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
        b[at + 2] = (v shr 16).toByte()
        b[at + 3] = (v shr 24).toByte()
    }

    private fun header(
        name: String = "ha-remote",
        version: Int = 2049,
        kind: Int = 0,
        abiMinor: Int = 3,
        imageSize: Int = 48081,
        entry: Int = 27672,
        relocs: Int = 104,
    ): ByteArray {
        val h = ByteArray(64)
        "WP2A".toByteArray(StandardCharsets.US_ASCII).copyInto(h, 0)
        put32(h, 4, 1) // abi u16 lands at 4..5
        h[6] = kind.toByte()
        h[7] = abiMinor.toByte()
        name.toByteArray(StandardCharsets.UTF_8).copyInto(h, 8)
        put32(h, 32, version.toLong())
        put32(h, 36, imageSize.toLong())
        put32(h, 40, entry.toLong())
        put32(h, 44, relocs.toLong())
        val crc = CRC32().apply { update(h, 0, 52) }.value
        put32(h, 52, crc)
        return h
    }

    private fun packageBytes(
        name: String = "ha-remote",
        version: Int = 2049,
        kind: Int = 0,
        imageSize: Int = 100,
        entry: Int = 40,
        relocs: Int = 2,
    ): ByteArray {
        val h = header(name, version, kind, 3, imageSize, entry, relocs)
        val body = ByteArray(imageSize + 4 * relocs)
        put32(h, 48, CRC32().apply { update(body) }.value)
        // the header CRC covers the body CRC field, so it is computed after it — same order as pack()
        put32(h, 52, CRC32().apply { update(h, 0, 52) }.value)
        return h + body
    }

    @Test
    fun parsesTheSameHeaderAsThePcTool() {
        val parsed = Install.parseHeader(header())
        assertEquals("ha-remote", parsed.name)
        assertEquals(2049, parsed.version)
        assertEquals(0, parsed.kind)
        assertEquals(1, parsed.abi)
        assertEquals(3, parsed.abiMinor)
        assertEquals(48081, parsed.size)
        assertEquals(27672, parsed.entry)
        assertEquals("0.2.1", Install.versionText(2049))
        assertEquals("0.2.2", Install.versionText(2050))
        assertEquals(64, Install.HEADER_SIZE)
    }

    @Test
    fun rejectsCorruptFilesWithAReason() {
        val badCrc = header().also { it[40] = (it[40] + 1).toByte() }
        assertTrue(runCatching { Install.parseHeader(badCrc) }.exceptionOrNull().toString().contains("CRC"))
        val badMagic = header().also { it[0] = 'X'.code.toByte() }
        assertTrue(runCatching { Install.parseHeader(badMagic) }.exceptionOrNull().toString().contains("magic"))
        val dirtyReserved = header().also { it[56] = 1 }
        assertTrue(runCatching { Install.parseHeader(dirtyReserved) }.exceptionOrNull().toString().contains("reserved"))
        assertTrue(runCatching { Install.parseHeader(byteArrayOf(1, 2, 3)) }.exceptionOrNull() is Install.Bad)
    }

    @Test
    fun fullPackageValidationMatchesThePcParser() {
        val good = packageBytes()
        assertEquals("ha-remote", Install.parsePackage(good).name)
        // length checks run before the body CRC, exactly like package.parse
        val truncated = good.copyOf(good.size - 1)
        assertTrue(runCatching { Install.parsePackage(truncated) }.exceptionOrNull().toString().contains("length"))
        val padded = good + ByteArray(4)
        assertTrue(runCatching { Install.parsePackage(padded) }.exceptionOrNull().toString().contains("length"))
        val badBody = good.copyOf().also { it[70] = (it[70] + 1).toByte() }
        assertTrue(runCatching { Install.parsePackage(badBody) }.exceptionOrNull().toString().contains("image CRC"))
    }

    @Test
    fun loaderPackagesAreVisibleInTheHeaderSoTheUiCanRefuseThem() {
        assertEquals(Install.KIND_LOADER, Install.parseHeader(header(kind = 1)).kind)
        assertEquals(Install.KIND_APP, Install.parseHeader(header(kind = 0)).kind)
    }

    @Test
    fun slotChoiceMatchesInstallDotPy() {
        val used = mapOf(0 to "other", 1 to "ha-remote", 3 to "third", 255 to "loader")
        assertEquals(1, Install.chooseSlot("ha-remote", used))
        assertEquals(2, Install.chooseSlot("music", used))
        val full = (0 until 8).associateWith { "slot$it" } + (255 to "loader")
        assertNull(Install.chooseSlot("new", full))
    }

    @Test
    fun chunkingOffsetsFollowThePcTool() {
        assertEquals(0, Install.chunkCount(0))
        assertEquals(1, Install.chunkCount(1))
        assertEquals(1, Install.chunkCount(128))
        assertEquals(2, Install.chunkCount(129))
        assertEquals(380, Install.chunkCount(48561))
        val v = 0xDEADBEEFL
        assertEquals(v, Install.le32(Install.u32le(v), 0))
    }

    @Test
    fun statusAndStubRepliesAreReadable() {
        assertTrue(Install.statusName(Install.STATUS_BUSY).contains("busy"))
        assertTrue(Install.statusName(99).contains("99"))
        assertEquals(3, Install.stubAbiMinor(byteArrayOf(0, 1, 0, 0, 0, 3)))
        assertNull(Install.stubAbiMinor(byteArrayOf(0, 1)))
    }
}
