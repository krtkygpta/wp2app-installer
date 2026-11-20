package dev.cmf.wp2installer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

/**
 * Picks a bonded watch and a .wp2app file, then uploads it over the existing bond using the
 * same multi-GATT pattern as the HA companion: a second connectGatt client, channel key K,
 * no new pairing and no popup. Only app packages are accepted; loader/firmware packages are
 * refused.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("installer", MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var watchSummary: TextView
    private lateinit var pkgInfo: TextView
    private lateinit var slotSpinner: Spinner
    private var devices: List<BluetoothDevice> = emptyList()
    private var selectedAddress: String? = null
    private var pkgBytes: ByteArray? = null
    private var pkgHeader: Install.Header? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        askPermissions()
        refreshWatches()
    }

    override fun onResume() {
        super.onResume()
        refreshWatches()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshWatches()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: run { status.text = "No file chosen"; return }
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read the file")
            val header = Install.parsePackage(bytes)
            pkgBytes = bytes
            pkgHeader = header
            slotSpinner.setSelection(0)
            val kb = bytes.size / 1024
            pkgInfo.text = "${header.name}  ·  v${Install.versionText(header.version)}  ·  ${kb} KB  ·  ${Install.chunkCount(bytes.size)} chunks"
            status.text = "Package ready — choose a slot (Auto is best) and install"
        } catch (e: Exception) {
            pkgBytes = null
            pkgHeader = null
            pkgInfo.text = "No package chosen"
            status.text = "Cannot use this file: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- view helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text
        setOnClickListener { runCatching(action).onFailure { status.text = "Error: " + (it.message ?: it.javaClass.simpleName) } }
    }

    private fun card(titleText: String): LinearLayout {
        val card = MaterialCardView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        card.addView(box)
        box.addView(label(titleText, 16f, bold = true))
        return box
    }

    private fun ViewGroup.addCard(box: LinearLayout) {
        addView(box.parent as ViewGroup)
    }

    private fun space(root: ViewGroup, height: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)))
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("WP2 App Installer", 24f, bold = true))
        status = label("Pick a watch and a .wp2app file")
        root.addView(status)
        space(root, 8)

        val watchBox = card("1 · Your watch")
        watchSummary = label("No watch selected")
        watchBox.addView(watchSummary)
        space(watchBox, 8)
        watchBox.addView(button("Choose watch") { chooseWatch() })
        root.addCard(watchBox)
        space(root, 12)

        val pkgBox = card("2 · Package file")
        pkgInfo = label("No package chosen")
        pkgBox.addView(pkgInfo)
        space(pkgBox, 8)
        pkgBox.addView(button("Choose .wp2app file") { pickFile() })
        space(pkgBox, 8)
        pkgBox.addView(label("Destination slot", 14f, bold = true))
        slotSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto — same name, else free slot") + (0 until Install.SLOTS).map { "Slot $it" }
            )
        }
        pkgBox.addView(slotSpinner)
        root.addCard(pkgBox)
        space(root, 16)

        root.addView(button("Install to watch") { install() })
        space(root, 12)
        root.addView(
            label(
                "Installs over the bond Nothing X already made: no second pairing, no popup. " +
                    "App packages only — loader/firmware packages are refused.",
                12f,
            )
        )
    }

    // ---------- permissions and devices ----------

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    @Suppress("MissingPermission")
    private fun deviceName(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull().orEmpty()

    @Suppress("MissingPermission")
    private fun refreshWatches() {
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) {
                devices = emptyList()
                status.text = "Bluetooth is unavailable on this phone"
                return
            }
            if (!adapter.isEnabled) {
                devices = emptyList()
                status.text = "Turn on Bluetooth, then reopen the app"
                return
            }
            devices = adapter.bondedDevices.orEmpty()
                .sortedWith(
                    compareByDescending<BluetoothDevice> { deviceName(it).contains("watch", true) }
                        .thenBy { deviceName(it) }
                )
            selectedAddress = prefs.getString("watch", null)
            updateWatchSummary()
        } catch (e: SecurityException) {
            status.text = "Allow the Bluetooth permission, then reopen the app"
        } catch (e: Exception) {
            status.text = "Bluetooth error: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun updateWatchSummary() {
        val selected = devices.firstOrNull { it.address == selectedAddress }
        watchSummary.text = when {
            selected != null -> "Selected watch: " + (deviceName(selected).ifBlank { "Unknown" }) + " · " + selected.address
            selectedAddress != null -> "Saved watch is not bonded any more — choose it again"
            else -> "No watch selected. Pair it once in Nothing X first."
        }
    }

    @Suppress("MissingPermission")
    private fun chooseWatch() {
        if (devices.isEmpty()) {
            status.text = "No bonded devices. Pair the watch once through Nothing X first."
            return
        }
        val watchLike = devices.filter { deviceName(it).contains("watch", true) }
        val ordered = watchLike + devices.filterNot { deviceName(it).contains("watch", true) }
        val entries = ordered.mapIndexed { index, device ->
            val name = deviceName(device).ifBlank { "Unknown" }
            val tag = if (index < watchLike.size) "⌚ " else "   "
            tag + name + "  ·  " + device.address
        }.toTypedArray()
        val preselect = ordered.indexOfFirst { it.address == selectedAddress }
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose your watch")
            .setSingleChoiceItems(entries, preselect) { dialog, which ->
                selectedAddress = ordered[which].address
                prefs.edit().putString("watch", selectedAddress).apply()
                updateWatchSummary()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- package file ----------

    private fun pickFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            PICK_FILE,
        )
    }

    // ---------- install ----------

    private class WatchRefused(code: Int) : IllegalStateException(Install.statusName(code))

    @SuppressLint("MissingPermission")
    private fun install() {
        val address = selectedAddress
        if (address == null) {
            status.text = "Choose your watch first"
            return
        }
        val data = pkgBytes
        val header = pkgHeader
        if (data == null || header == null) {
            status.text = "Choose a .wp2app file first"
            return
        }
        if (header.kind != Install.KIND_APP) {
            status.text = "Refused: this is a firmware/loader package, not an app"
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            status.text = "Bluetooth is unavailable"
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            status.text = "Bad saved watch address"
            return
        }
        val slotChoice = slotSpinner.selectedItemPosition // 0 = auto
        status.text = "Connecting to the watch…"

        scope.launch {
            val link = WatchLink(this@MainActivity, device)
            try {
                link.open()
                status.text = "Connected · checking firmware…"

                // Stub version/ABI pre-flight (what wp2apps list reads); an old stub that
                // does not answer is left for the watch to judge at END.
                link.call(Install.INFO, byteArrayOf(Install.STUB_SLOT.toByte()))?.let { reply ->
                    if (reply.isNotEmpty() && reply[0].toInt() != 0 && reply[0].toInt() != 1) {
                        throw IllegalStateException("watch refused firmware check: " + Install.statusName(reply[0].toInt() and 255))
                    }
                    Install.stubAbiMinor(reply)?.let { stubAbi ->
                        if (header.abiMinor > stubAbi) {
                            throw IllegalStateException("watch stub is too old: package needs ABI minor ${header.abiMinor}, stub offers $stubAbi — update the App Loader first")
                        }
                    }
                }

                status.text = "Reading installed slots…"
                val used = HashMap<Int, String>()
                for (s in 0 until Install.SLOTS) {
                    val reply = link.call(Install.INFO, byteArrayOf(s.toByte())) ?: continue
                    if (reply.size > 1 && reply[0].toInt() == 0) {
                        used[s] = try {
                            Install.parseHeader(reply.copyOfRange(1, minOf(reply.size, 1 + Install.HEADER_SIZE))).name
                        } catch (bad: Exception) {
                            "unknown"
                        }
                    }
                }
                val slot = if (slotChoice == 0) {
                    Install.chooseSlot(header.name, used) ?: error("No free slot — remove an app on the watch first")
                } else {
                    slotChoice - 1
                }
                val replaced = used[slot]
                if (replaced != null && replaced != header.name) {
                    status.text = "Replacing '$replaced' in slot $slot · starting upload…"
                } else {
                    status.text = "Uploading to slot $slot · 0%"
                }

                val begin = link.call(
                    Install.BEGIN,
                    byteArrayOf(slot.toByte()) + Install.u32le(data.size.toLong()) + Install.u32le(Install.crc32(data)),
                ) ?: error("watch did not answer BEGIN")
                if (begin[0].toInt() != 0) throw WatchRefused(begin[0].toInt() and 255)

                var offset = 0
                var lastPct = 0
                while (offset < data.size) {
                    val n = minOf(Install.MAX_DATA, data.size - offset)
                    val part = data.copyOfRange(offset, offset + n)
                    val reply = link.call(Install.DATA, Install.u32le(offset.toLong()) + part)
                        ?: error("upload stalled after $offset bytes — keep the watch awake and retry")
                    if (reply[0].toInt() != 0) throw WatchRefused(reply[0].toInt() and 255)
                    if (reply.size < 5) error("short DATA reply from the watch")
                    val acked = Install.le32(reply, 1)
                    if (acked != (offset + n).toLong()) {
                        error("watch expects offset $acked, sent ${offset + n}")
                    }
                    offset += n
                    val pct = offset * 100 / data.size
                    if (pct >= lastPct + 10 || offset == data.size) {
                        lastPct = pct
                        status.text = "Uploading to slot $slot · $pct%"
                    }
                }

                val end = link.call(Install.END, byteArrayOf(0))
                    ?: error("watch did not answer END — the file may not have been committed")
                if (end[0].toInt() != 0) throw WatchRefused(end[0].toInt() and 255)

                status.text = "Verifying…"
                val verify = link.call(Install.INFO, byteArrayOf(slot.toByte()))
                val installed = verify?.takeIf { it.size > 1 && it[0].toInt() == 0 }
                    ?.let { Install.parseHeader(it.copyOfRange(1, minOf(it.size, 1 + Install.HEADER_SIZE))).name }
                status.text = if (installed == header.name) {
                    "Installed ${header.name} v${Install.versionText(header.version)} → slot $slot ✓"
                } else {
                    "Installed, but the watch reports slot $slot as ${installed ?: "empty"}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Install failed: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                link.close()
            }
        }
    }

    companion object {
        private const val PICK_FILE = 11
    }
}
