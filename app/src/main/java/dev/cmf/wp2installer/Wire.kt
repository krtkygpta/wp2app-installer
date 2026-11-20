package dev.cmf.wp2installer

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The patched firmware's encrypted link (cmf-watch-sdk docs/abi-1.4.md). A frame is F5,
 * length:u16be, 0xFFFF, 00 01, 00 01, opcode:u16be, then an AES-128-CBC body (fixed IV,
 * PKCS7, CRC32 of the plaintext appended). Same wire format as the PC wp2apps tool and the
 * HA companion; keyed with channel key K, so nothing re-pairs.
 */
object Wire {

    const val SYSTEM = 0xFFFF
    const val PING = 0x90A7
    const val EVENT = 0xB0A6

    private val IV = hex("5051525354555657606162636465665A")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun crc(data: ByteArray): ByteArray {
        val v = CRC32().apply { update(data) }.value
        return byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }

    private fun cipher(mode: Int, key: ByteArray) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(IV))
    }

    fun seal(key: ByteArray, plain: ByteArray) = cipher(Cipher.ENCRYPT_MODE, key).doFinal(plain + crc(plain))

    /** Plaintext, or null when the body belongs to another host's session. */
    fun open(key: ByteArray, body: ByteArray): ByteArray? = runCatching {
        val p = cipher(Cipher.DECRYPT_MODE, key).doFinal(body)
        val plain = p.copyOfRange(0, p.size - 4)
        plain.takeIf { crc(it).contentEquals(p.copyOfRange(p.size - 4, p.size)) }
    }.getOrNull()

    fun frame(opcode: Int, plain: ByteArray, key: ByteArray): ByteArray {
        val body = seal(key, plain)
        val out = ByteArrayOutputStream(11 + body.size)
        fun u16(v: Int) {
            out.write(v shr 8 and 255)
            out.write(v and 255)
        }
        out.write(0xF5)
        u16(body.size)
        u16(SYSTEM)
        u16(1)
        u16(1)
        u16(opcode)
        out.write(body)
        return out.toByteArray()
    }

    data class Packet(val c1: Int, val c2: Int, val body: ByteArray)

    /** Reassembles frames that span several notifications. */
    class Parser {
        private var buf = ByteArray(0)

        fun feed(incoming: ByteArray): List<Packet> {
            buf += incoming
            val result = ArrayList<Packet>()
            while (true) {
                val start = buf.indexOfFirst { it.toInt() and 255 == 0xF5 }
                if (start < 0) {
                    buf = ByteArray(0)
                    break
                }
                if (start > 0) buf = buf.copyOfRange(start, buf.size)
                if (buf.size < 11) break
                fun u16(i: Int) = (buf[i].toInt() and 255) shl 8 or (buf[i + 1].toInt() and 255)
                val size = u16(1)
                if (size !in 1..4096) {
                    buf = buf.copyOfRange(1, buf.size)
                    continue
                }
                if (buf.size < 11 + size) break
                result += Packet(u16(3), u16(9), buf.copyOfRange(11, 11 + size))
                buf = buf.copyOfRange(11 + size, buf.size)
            }
            return result
        }
    }
}
