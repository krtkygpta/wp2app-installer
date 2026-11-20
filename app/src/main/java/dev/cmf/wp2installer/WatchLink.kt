package dev.cmf.wp2installer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * One GATT client on the watch, next to Nothing X on the same link (the same multi-GATT
 * pattern as the HA companion): connect to the already bonded device, read channel key K
 * over the shell service, then speak the encrypted install channel. No new bond and no bind,
 * so Nothing X keeps working and no popup appears on the watch.
 */
@SuppressLint("MissingPermission")
class WatchLink(context: Context, private val device: BluetoothDevice) {

    @Volatile
    var connected = false
        private set

    private val serviceContext = context.applicationContext
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var key: ByteArray? = null

    private var mtu = 23
    private val parser = Wire.Parser()
    private val replies = Channel<Wire.Packet>(Channel.BUFFERED)
    private val shell = Channel<ByteArray>(Channel.BUFFERED)
    private var op: CompletableDeferred<Int>? = null
    private var connectedOnce: CompletableDeferred<Boolean>? = null
    private val requestLock = Mutex()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectedOnce?.complete(true)
                return
            }
            connectedOnce?.complete(false)
            op?.completeExceptionally(IllegalStateException("Bluetooth disconnected"))
            connected = false
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { op?.complete(status) }

        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == 0) mtu = value
            op?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            op?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            op?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                CMD_N -> synchronized(parser) { parser.feed(value) }.forEach { packet ->
                    // Unsolicited app events are not used here; everything else is a reply.
                    if (!(packet.c1 == Wire.SYSTEM && packet.c2 == Wire.EVENT)) replies.trySend(packet)
                }
                SHELL_N -> shell.trySend(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onCharacteristicChanged(g, c, c.value ?: return)
        }
    }

    private suspend fun operation(start: (BluetoothGatt) -> Boolean): Int {
        val g = gatt ?: error("Bluetooth link unavailable")
        val done = CompletableDeferred<Int>()
        op = done
        repeat(50) {
            if (start(g)) return withTimeout(6000) { done.await() }
            delay(20)
        }
        error("Bluetooth operation could not start")
    }

    private fun characteristic(id: UUID) =
        gatt?.services?.firstNotNullOfOrNull { service ->
            service.characteristics.firstOrNull { it.uuid == id }
        }

    private suspend fun write(id: UUID, bytes: ByteArray) {
        val c = characteristic(id) ?: error("Watch GATT service missing")
        val type = if (bytes.size <= mtu - 3) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val status = operation { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) error("Watch GATT write failed")
    }

    private suspend fun subscribe(id: UUID) {
        val c = characteristic(id) ?: error("Watch notification characteristic missing")
        val descriptor = c.getDescriptor(CCCD) ?: error("Watch notification descriptor missing")
        gatt?.setCharacteristicNotification(c, true)
        val status = operation { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) error("Could not subscribe to watch")
    }

    private suspend fun at(command: String): String {
        while (shell.tryReceive().isSuccess) Unit
        write(SHELL_W, "$command\r\n".toByteArray())
        val out = StringBuilder()
        withTimeout(7000) {
            while (!out.contains("OK") && !out.contains("ERROR")) out.append(String(shell.receive()))
        }
        return out.toString()
    }

    /** Connects and reads K. Throws with a short reason when any step fails. */
    suspend fun open() {
        connectedOnce = CompletableDeferred()
        gatt = device.connectGatt(serviceContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (withTimeoutOrNull(15_000) { connectedOnce?.await() } != true) error("Could not connect to the bonded watch")
        runCatching { operation { it.requestMtu(247) } }
        if (mtu < 160) error("The watch kept a $mtu-byte MTU; uploading 128-byte chunks needs at least 160 — retry")
        if (operation { it.discoverServices() } != BluetoothGatt.GATT_SUCCESS) error("Watch service discovery failed")
        for (id in listOf(CMD_W, CMD_N, SHELL_W, SHELL_N)) {
            if (characteristic(id) == null) error("Watch channel service not found — the firmware needs the patched stub")
        }
        subscribe(CMD_N)
        subscribe(SHELL_N)
        val reply = at("AT GETSECRET")
        val match = Regex("GETSECRET:([0-9A-Fa-f]{32})").find(reply)
            ?: error("No channel key; the firmware needs the stub with K")
        key = ByteArray(16) { match.groupValues[1].substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        connected = true
        if (ping() == null) error("The firmware channel did not answer")
    }

    /** One install-channel exchange: returns the decrypted reply (including its status byte), null on timeout. */
    suspend fun call(opcode: Int, payload: ByteArray): ByteArray? = requestLock.withLock {
        val k = key ?: return null
        write(CMD_W, Wire.frame(opcode, payload, k))
        var result: ByteArray? = null
        withTimeoutOrNull(5000) {
            while (result == null) {
                val packet = replies.receive()
                if (packet.c1 == Wire.SYSTEM && packet.c2 == (opcode or 0xA000)) {
                    result = Wire.open(k, packet.body)
                }
            }
        }
        result
    }

    /** PING answer: running slot (0xFF = none) and flags. */
    suspend fun ping(): Pair<Int, Int>? {
        val reply = call(Wire.PING, byteArrayOf(0)) ?: return null
        if (reply.size != 3 || reply[0].toInt() != 0) return null
        return (reply[1].toInt() and 255) to (reply[2].toInt() and 255)
    }

    fun close() {
        connected = false
        key?.fill(0)
        key = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        replies.close()
        shell.close()
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val CMD_W = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        private val CMD_N = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        private val SHELL_W = UUID.fromString("77d4ff01-2fe2-2334-0d35-9ccd078f529c")
        private val SHELL_N = UUID.fromString("77d4ff02-2fe2-2334-0d35-9ccd078f529c")
    }
}
