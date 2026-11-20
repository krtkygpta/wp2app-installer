package dev.cmf.wp2installer

import java.util.zip.CRC32

/**
 * The wp2apps install channel: INFO/BEGIN/DATA/END under channel key K, byte-for-byte
 * the same requests the PC CLI sends (.sdk/wp2-apps/src/wp2apps/install.py), plus the same
 * package validation the PC parser performs.
 */
object Install {

    const val SLOTS = 8
    const val STUB_SLOT = 0xFE
    const val INFO = 0x90A0
    const val BEGIN = 0x90A1
    const val DATA = 0x90A2
    const val END = 0x90A3
    const val DELETE = 0x90A4
    const val MAX_DATA = 128

    /// The PC tool's HEADER = Struct('<4sHBB24sIIIIII8s'): 64 bytes.
    const val HEADER_SIZE = 64
    private const val HEADER_CRC_SPAN = 52
    private const val RESERVED_AT = 56
    const val KIND_APP = 0
    const val KIND_LOADER = 1

    const val STATUS_OK = 0
    const val STATUS_BUSY = 5

    /** Parse error with a user-readable reason. */
    class Bad(val reason: String) : IllegalArgumentException(reason)

    data class Header(
        val name: String,
        val version: Int,
        val kind: Int,
        val abi: Int,
        val abiMinor: Int,
        val size: Int,
        val entry: Int,
    )

    fun statusName(code: Int) = when (code) {
        0 -> "OK"
        1 -> "bad request"
        2 -> "watch storage error"
        3 -> "sequence error"
        4 -> "CRC mismatch"
        5 -> "busy (that slot is the app currently open on the watch)"
        6 -> "invalid package"
        7 -> "no running app"
        8 -> "message buffer full"
        9 -> "app paused"
        else -> "refused ($code)"
    }

    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    fun u32le(v: Long) = byteArrayOf(
        v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
    )

    fun le32(data: ByteArray, at: Int): Long =
        (data[at].toLong() and 0xFF) or
            ((data[at + 1].toLong() and 0xFF) shl 8) or
            ((data[at + 2].toLong() and 0xFF) shl 16) or
            ((data[at + 3].toLong() and 0xFF) shl 24)

    fun versionText(build: Int): String {
        val major = build shr 20
        val minor = (build shr 10) and 0x3FF
        val patch = build and 0x3FF
        return "$major.$minor.$patch"
    }

    /**
     * Validates the WP2A header the same way the PC parser does: magic, header CRC, zeroed
     * reserved bytes, ABI, kind, total length, body CRC, name bounds and entry point.
     * [totalSize] is the whole package when known; INFO replies pass -1 (header only).
     */
    fun parseHeader(raw: ByteArray, totalSize: Long = -1): Header {
        if (raw.size < HEADER_SIZE) throw Bad("file is too small to be a .wp2app")
        if (String(raw, 0, 4, Charsets.US_ASCII) != "WP2A") {
            throw Bad("bad magic — this is not a .wp2app file")
        }
        if (crc32(raw.copyOfRange(0, HEADER_CRC_SPAN)) != le32(raw, HEADER_CRC_SPAN)) {
            throw Bad("header CRC mismatch — the file is corrupt")
        }
        for (i in RESERVED_AT until HEADER_SIZE) {
            if (raw[i] != 0.toByte()) throw Bad("reserved header bytes are not zero — the file is corrupt")
        }
        val abi = le32(raw, 4) and 0xFFFFL
        if (abi != 1L) throw Bad("package ABI $abi is not supported")
        val kind = raw[6].toInt() and 0xFF
        if (kind != KIND_APP && kind != KIND_LOADER) throw Bad("unknown package kind $kind")
        val abiMinor = raw[7].toInt() and 0xFF
        val nameField = raw.copyOfRange(8, 32)
        val size = le32(raw, 36).toInt()
        val entry = le32(raw, 40).toInt()
        val relocCount = le32(raw, 44).toInt()
        if (totalSize >= 0) {
            if (size < 0 || totalSize != HEADER_SIZE + size + 4L * relocCount) {
                throw Bad("package length does not match its header (truncated or padded file?)")
            }
            if (crc32(raw.copyOfRange(HEADER_SIZE, raw.size)) != le32(raw, 48)) {
                throw Bad("image CRC mismatch — the file is corrupt")
            }
        }
        val end = nameField.indexOf(0).let { if (it < 0) nameField.size else it }
        if (end < 1 || end > 23) throw Bad("package name must be 1..23 bytes")
        val nameBytes = nameField.copyOfRange(0, end)
        val name = String(nameBytes, Charsets.UTF_8)
        if (size < 0 || entry < 0 || entry >= size) throw Bad("entry point is outside the image")
        return Header(
            name = name,
            version = le32(raw, 32).toInt(),
            kind = kind,
            abi = abi.toInt(),
            abiMinor = abiMinor,
            size = size,
            entry = entry,
        )
    }

    fun parsePackage(data: ByteArray): Header = parseHeader(data, data.size.toLong())

    /** install.py's slot choice: upgrade the same name in place, else the first free slot. */
    fun chooseSlot(pkgName: String, used: Map<Int, String>): Int? {
        used.filterKeys { it < SLOTS }.entries
            .filter { it.value == pkgName }
            .minOfOrNull { it.key }
            ?.let { return it }
        return (0 until SLOTS).firstOrNull { it !in used }
    }

    fun chunkCount(size: Int) = (size + MAX_DATA - 1) / MAX_DATA

    /** StubVersion reply for STUB_SLOT: status + u32 release + highest accepted abi_minor. */
    fun stubAbiMinor(reply: ByteArray): Int? =
        if (reply.size == 6 && reply[0].toInt() == 0) reply[5].toInt() and 0xFF else null
}
