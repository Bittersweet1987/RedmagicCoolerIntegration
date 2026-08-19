package com.romestylez.redmagiccooler

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * BLE control library for the REDMAGIC VC Cooler 6 Pro (advertised as "RM Magcooler 6pro" /
 * "REDMAGIC Cooler 6 pro+"), reverse engineered from the Goper companion app
 * (cn.nubia.externdevice) via static bytecode analysis and Bluetooth HCI snoop logging.
 *
 * See PROTOCOL.md in this module for the full protocol writeup, including open questions.
 *
 * Modeled after the connection-management style of romestylez/BlackSharkLib.swift and
 * pocketSRT's existing BlackShark cooler support, so it should slot into the same
 * "device manager" pattern used there.
 *
 * DE: BLE-Steuerungsbibliothek fuer den REDMAGIC VC Cooler 6 Pro (Werbename "RM Magcooler 6pro" /
 * "REDMAGIC Cooler 6 pro+"), reverse engineered aus der Goper-Companion-App
 * (cn.nubia.externdevice) per statischer Bytecode-Analyse und Bluetooth-HCI-Snoop-Mitschnitten.
 * Die vollstaendige Protokollbeschreibung inklusive offener Fragen steht in PROTOCOL.de.md.
 * Der Aufbau orientiert sich bewusst an BlackSharkLib.swift bzw. der bestehenden
 * BlackShark-Unterstuetzung in pocketSRT, damit sich die Klasse dort ohne Bruch einfuegt.
 *
 * Usage / DE: Verwendung:
 * ```
 * val cooler = RedMagicCooler6Pro(context)
 * cooler.onStateChanged = { state -> ... }
 * cooler.onConnectionStateChanged = { connected -> ... }
 * cooler.startScanAndConnect()
 * ...
 * cooler.setCoolingEnabled(true)
 * cooler.setFanStep(5)
 * cooler.setLedEnabled(true)
 * cooler.disconnect()
 * ```
 */
class RedMagicCooler6Pro(private val context: Context) {

    companion object {
        private const val TAG = "RedMagicCooler6Pro"

        val SERVICE_UUID: UUID = UUID.fromString("d52082ad-e805-9f97-9d4e-1c682d9c9ce6")

        // Cooling on/off / DE: Kuehlung an/aus. 0x02=on/an, 0x03=off/aus
        private val CHAR_COOLING_SWITCH = shortUuid(0x1011)
        // Fan level / DE: Kuehlstufe. Raw byte / DE: Rohbyte, ~0x28..0x50
        private val CHAR_FAN_LEVEL = shortUuid(0x1012)
        // LED, 4 bytes / DE: 4 Byte. 01000000=on/an, 06000000=off/aus
        private val CHAR_LED = shortUuid(0x1013)
        // Temperature notify stream / DE: Temperatur-Notify-Stream, "04 TT" = TT degC
        private val CHAR_TEMPERATURE = shortUuid(0x1015)
        // Diablo mode / DE: Diablo-Modus. 0x01=on/an, 0x00=off/aus
        private val CHAR_DIABLO_MODE = shortUuid(0x1017)
        // Automatic temperature control / DE: Automatische Temperaturregelung. 0x01=on/an, 0x00=off/aus
        private val CHAR_AUTO_TEMP = shortUuid(0x1018)

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val COOLING_ON: Byte = 0x02
        private const val COOLING_OFF: Byte = 0x03

        private val LED_ON_FRAME = byteArrayOf(0x01, 0x00, 0x00, 0x00)
        private val LED_OFF_FRAME = byteArrayOf(0x06, 0x00, 0x00, 0x00)

        /**
         * The nine fan levels Goper offers, in the exact raw byte values it sends (captured via
         * HCI snoop). Deliberately a lookup table rather than a formula - the scale is NOT linear
         * (levels 1-5 step by 6, levels 5-9 step by 4).
         *
         * DE: Die neun Kuehlstufen von Goper, in exakt den Rohwerten, die Goper sendet (per
         * HCI-Snoop ermittelt). Bewusst eine Wertetabelle statt einer Formel - die Skala ist NICHT
         * linear (Stufen 1-5 steigen in 6er-Schritten, Stufen 5-9 in 4er-Schritten).
         */
        val FAN_LEVELS = byteArrayOf(0x28, 0x2E, 0x34, 0x3A, 0x40, 0x44, 0x48, 0x4C, 0x50)

        const val FAN_LEVEL_MIN = 0x28 // 40 = level 1 / DE: Stufe 1
        const val FAN_LEVEL_MAX = 0x50 // 80 = level 9 / DE: Stufe 9

        /**
         * Name substrings this device has been observed advertising under.
         *
         * DE: Namensbestandteile, unter denen das Geraet beobachtet wurde.
         */
        val NAME_FILTERS = listOf("MAGCOOLER", "REDMAGIC", "COOLER")

        private fun shortUuid(short: Int): UUID =
            UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))
    }

    var onStateChanged: ((CoolerState) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    private var state = CoolerState()
        set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    fun isConnected(): Boolean = state.connected

    // ------------------------------------------------------------------
    // Connection / DE: Verbindung
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScanAndConnect(timeoutMs: Long = 15_000L) {
        val adapter = bluetoothAdapter ?: run { log("No Bluetooth adapter"); return }
        val scanner = adapter.bluetoothLeScanner ?: run { log("No BLE scanner"); return }
        log("Scanning for cooler...")
        scanner.startScan(scanCallback)
        mainHandler.postDelayed({ scanner.stopScan(scanCallback) }, timeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun connect(macAddress: String) {
        val adapter = bluetoothAdapter ?: run { log("No Bluetooth adapter"); return }
        val device = adapter.getRemoteDevice(macAddress)
        connectDevice(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        log("Connecting to ${device.address}...")
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        opQueue.clear()
        opInFlight = false
        state = CoolerState(connected = false)
        onConnectionStateChanged?.invoke(false)
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            val upper = name.uppercase(Locale.ROOT)
            if (NAME_FILTERS.any { upper.contains(it) }) {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                connectDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: $errorCode")
        }
    }

    // ------------------------------------------------------------------
    // GATT callback / DE: GATT-Callback
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Connected, discovering services...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status=$status)")
                state = CoolerState(connected = false)
                onConnectionStateChanged?.invoke(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                log("Cooler service not found on this device")
                return
            }
            state = state.copy(connected = true)
            onConnectionStateChanged?.invoke(true)

            // Subscribe to the temperature notify stream; ignore failures on characteristics
            // without a CCCD (0x1013/0x1014 are known to not expose one).
            // DE: Temperatur-Notify-Stream abonnieren; Fehler bei Characteristics ohne CCCD
            // ignorieren (0x1013/0x1014 bieten bekanntermassen keinen).
            service.getCharacteristic(CHAR_TEMPERATURE)?.let { enqueueEnableNotify(g, it) }

            refreshState()
            processQueue()
        }

        // NOTE: intentionally using the deprecated (gatt, characteristic, status/value-less)
        // callback overloads rather than the API 33+ byte[]-returning ones, so this keeps
        // working down to pocketSRT's minSdk without duplicating logic for both signatures.
        // DE: Bewusst die veralteten Callback-Signaturen statt der byte[]-Varianten ab API 33,
        // damit der Code bis hinunter zu pocketSRTs minSdk funktioniert, ohne die Logik fuer
        // beide Signaturen doppelt vorzuhalten.
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            applyReadValue(characteristic.uuid, characteristic.value ?: ByteArray(0))
            opInFlight = false
            processQueue()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            opInFlight = false
            processQueue()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            opInFlight = false
            processQueue()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            applyNotifyValue(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }
    }

    private fun applyReadValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CHAR_COOLING_SWITCH -> if (value.isNotEmpty()) {
                state = state.copy(coolingEnabled = value[0] == COOLING_ON)
            }
            CHAR_FAN_LEVEL -> if (value.isNotEmpty()) {
                state = state.copy(fanLevel = value[0].toInt() and 0xFF)
            }
            CHAR_DIABLO_MODE -> if (value.isNotEmpty()) {
                state = state.copy(diabloMode = value[0].toInt() != 0)
            }
            CHAR_AUTO_TEMP -> if (value.isNotEmpty()) {
                state = state.copy(autoTemperatureControl = value[0].toInt() != 0)
            }
            CHAR_LED -> if (value.isNotEmpty()) {
                // 0x01 = on, 0x06 = off; anything else is left as-is rather than guessed at.
                // DE: 0x01 = an, 0x06 = aus; alles andere bleibt unveraendert statt geraten.
                when (value[0].toInt()) {
                    0x01 -> state = state.copy(ledOn = true)
                    0x06 -> state = state.copy(ledOn = false)
                }
            }
            CHAR_TEMPERATURE -> applyNotifyValue(uuid, value)
        }
    }

    private fun applyNotifyValue(uuid: UUID, value: ByteArray) {
        if (uuid == CHAR_TEMPERATURE && value.size >= 2 && value[0] == 0x04.toByte()) {
            val celsius = value[1].toInt() and 0xFF
            state = state.copy(temperatureCelsius = celsius)
        }
    }

    /**
     * Re-reads every known-readable characteristic and updates [state] / [onStateChanged].
     *
     * DE: Liest alle bekannten lesbaren Characteristics neu und aktualisiert [state] /
     * [onStateChanged].
     */
    @SuppressLint("MissingPermission")
    fun refreshState() {
        val g = gatt ?: return
        val service = g.getService(SERVICE_UUID) ?: return
        for (uuid in listOf(CHAR_COOLING_SWITCH, CHAR_FAN_LEVEL, CHAR_LED, CHAR_DIABLO_MODE, CHAR_AUTO_TEMP, CHAR_TEMPERATURE)) {
            service.getCharacteristic(uuid)?.let { enqueueRead(g, it) }
        }
    }

    // ------------------------------------------------------------------
    // Public control API / DE: Oeffentliche Steuer-API
    // ------------------------------------------------------------------

    fun setCoolingEnabled(enabled: Boolean) {
        writeByte(CHAR_COOLING_SWITCH, if (enabled) COOLING_ON else COOLING_OFF)
        state = state.copy(coolingEnabled = enabled)
    }

    /**
     * Sets the fan speed by Goper's own step number, 1 (quietest) to 9 (fastest).
     * This is what UI should normally use - it writes exactly the values Goper writes.
     *
     * DE: Setzt die Kuehlstufe anhand der Goper-Stufennummer, 1 (leiseste) bis 9 (schnellste).
     * Das ist der normale Weg fuer die UI - es werden exakt die Werte geschrieben, die auch
     * Goper schreibt.
     */
    fun setFanStep(step: Int) {
        val index = step.coerceIn(1, FAN_LEVELS.size) - 1
        setFanLevel(FAN_LEVELS[index].toInt() and 0xFF)
    }

    /**
     * Sets the fan level by raw byte value. Prefer [setFanStep] unless you specifically want to
     * probe values Goper never sends. Goper only ever writes [FAN_LEVELS]
     * ([FAN_LEVEL_MIN]..[FAN_LEVEL_MAX]); behaviour outside that range is untested.
     *
     * DE: Setzt die Kuehlstufe als Rohbytewert. Bevorzugt [setFanStep] verwenden, ausser es
     * sollen bewusst Werte getestet werden, die Goper nie sendet. Goper schreibt ausschliesslich
     * [FAN_LEVELS] ([FAN_LEVEL_MIN]..[FAN_LEVEL_MAX]); das Verhalten ausserhalb dieses Bereichs
     * ist ungetestet.
     */
    fun setFanLevel(level: Int) {
        val clamped = level.coerceIn(0, 255)
        writeByte(CHAR_FAN_LEVEL, clamped.toByte())
        state = state.copy(fanLevel = clamped)
    }

    fun setDiabloMode(enabled: Boolean) {
        writeByte(CHAR_DIABLO_MODE, if (enabled) 0x01 else 0x00)
        state = state.copy(diabloMode = enabled)
    }

    fun setAutoTemperatureControl(enabled: Boolean) {
        writeByte(CHAR_AUTO_TEMP, if (enabled) 0x01 else 0x00)
        state = state.copy(autoTemperatureControl = enabled)
    }

    /**
     * Turns the LED ring on or off. Unlike an earlier assumption this is a plain set, not a toggle,
     * and the current state is readable back from the same characteristic.
     *
     * Do not call this twice in quick succession with opposite values - the device visibly flashes
     * and settles on the last value written.
     *
     * DE: Schaltet den LED-Ring ein oder aus. Entgegen einer frueheren Annahme ist das ein
     * einfaches Setzen, kein Toggle; der aktuelle Zustand ist ueber dieselbe Characteristic
     * auslesbar. Nicht zweimal kurz hintereinander mit entgegengesetzten Werten aufrufen - die
     * LED blinkt dann sichtbar auf und bleibt beim zuletzt geschriebenen Wert stehen.
     */
    @SuppressLint("MissingPermission")
    fun setLedEnabled(enabled: Boolean) {
        val g = gatt ?: return
        val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_LED) ?: return
        enqueueWrite(g, characteristic, if (enabled) LED_ON_FRAME else LED_OFF_FRAME)
        state = state.copy(ledOn = enabled)
    }

    // ------------------------------------------------------------------
    // GATT op queue (Android only allows one in-flight GATT operation at a time)
    // DE: GATT-Warteschlange (Android erlaubt immer nur eine laufende GATT-Operation)
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun writeByte(uuid: UUID, value: Byte) {
        val g = gatt ?: return
        val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(uuid) ?: return
        enqueueWrite(g, characteristic, byteArrayOf(value))
    }

    @SuppressLint("MissingPermission")
    private fun enqueueWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        opQueue.add {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(characteristic)
        }
        processQueue()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        opQueue.add { g.readCharacteristic(characteristic) }
        processQueue()
    }

    @SuppressLint("MissingPermission")
    private fun enqueueEnableNotify(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        opQueue.add {
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            } else {
                opInFlight = false
                processQueue()
            }
        }
        processQueue()
    }

    private fun processQueue() {
        if (opInFlight) return
        val next = opQueue.poll() ?: return
        opInFlight = true
        next()
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }
}
