package com.dapic.dsp

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
import java.util.UUID
import java.util.concurrent.Executors

data class DiscoveredBtDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isBonded: Boolean
)

object DapicProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000aa81-0000-1000-8000-00805f9b34fb")
    val CHAR_DSP_UUID: UUID = UUID.fromString("0000aa81-0000-1000-8000-00805f9b34fb")
    val CHAR_UART_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val HEADER_0: Byte = 0x5A
    private const val HEADER_1: Byte = 0x5A
    private const val HEADER_2: Byte = 0xAA.toByte()
    private const val HEADER_3: Byte = 0x81.toByte()
    private const val TRAILER_0: Byte = 0x48
    private const val TRAILER_1: Byte = 0x48

    const val CMD_SET_VOL: Int = 0x0A2B
    const val CMD_SET_MUTE: Int = 0x0A27
    const val CMD_GET_VOL_INFO: Int = 0x0B03
    const val CMD_GET_MAX_LIMITS: Int = 0x0B09
    const val CMD_GET_FREQ_LIST: Int = 0x0B0B
    const val CMD_GET_EQ_MODE: Int = 0x0B0F
    const val CMD_GET_EQ_STATE: Int = 0x0B11
    const val CMD_SET_SINGLE_EQ_BAND: Int = 0x0B13
    const val CMD_GET_BALANCE_FADER: Int = 0x0B15
    const val CMD_SET_BALANCE_FADER: Int = 0x0B17
    const val CMD_SET_TONE: Int = 0x0B1B
    const val CMD_SET_SOUNDFIELD: Int = 0x0B1F
    const val CMD_SET_CHANNEL_GAIN: Int = 0x0B21
    const val CMD_SET_CHANNEL_XOVER: Int = 0x0B23

    val EQ_20_LABELS = arrayOf(
        "32Hz", "45Hz", "63Hz", "90Hz", "125Hz",
        "180Hz", "250Hz", "350Hz", "500Hz", "700Hz",
        "1kHz", "1.4kHz", "2kHz", "2.8kHz", "4kHz",
        "5.6kHz", "8kHz", "10kHz", "12.5kHz", "16kHz"
    )

    fun buildFrame(cmd: Int, payload: ByteArray = ByteArray(0), useAdditiveSum: Boolean = false): ByteArray {
        val len = if (payload.size < 0) 0 else if (payload.size > 255) 255 else payload.size
        val frame = ByteArray(4 + 2 + 1 + len + 1 + 2)
        frame[0] = HEADER_0
        frame[1] = HEADER_1
        frame[2] = HEADER_2
        frame[3] = HEADER_3

        frame[4] = ((cmd ushr 8) and 0xFF).toByte()
        frame[5] = (cmd and 0xFF).toByte()
        frame[6] = len.toByte()

        if (len > 0) {
            System.arraycopy(payload, 0, frame, 7, len)
        }

        var acc = 0
        for (i in 4 until (7 + len)) {
            acc = if (useAdditiveSum) {
                (acc + (frame[i].toInt() and 0xFF)) and 0xFF
            } else {
                acc xor (frame[i].toInt() and 0xFF)
            }
        }
        frame[7 + len] = acc.toByte()
        frame[8 + len] = TRAILER_0
        frame[9 + len] = TRAILER_1
        return frame
    }

    fun setSingleEqBand(bandIndex1Based: Int, value: Int): ByteArray =
        buildFrame(CMD_SET_SINGLE_EQ_BAND, byteArrayOf(bandIndex1Based.toByte(), value.toByte()))

    fun setTone(toneType: Int, value: Int): ByteArray =
        buildFrame(CMD_SET_TONE, byteArrayOf(toneType.toByte(), value.toByte()))

    fun setBalanceFader(balance: Int, fader: Int): ByteArray =
        buildFrame(CMD_SET_BALANCE_FADER, byteArrayOf(balance.toByte(), fader.toByte()))

    fun setConcertHall(enabled: Boolean, mode: Int): ByteArray =
        buildFrame(CMD_SET_SOUNDFIELD, byteArrayOf(if (enabled) 1 else 0, mode.toByte()))

    fun setChannelGain(channelIndex1Based: Int, gain0to15: Int, muted: Boolean): ByteArray =
        buildFrame(
            CMD_SET_CHANNEL_GAIN,
            byteArrayOf(channelIndex1Based.toByte(), if (muted) 0 else gain0to15.toByte(), if (muted) 1 else 0)
        )

    fun setChannelCrossover(channelIndex1Based: Int, freqHz: Int): ByteArray =
        buildFrame(
            CMD_SET_CHANNEL_XOVER,
            byteArrayOf(
                channelIndex1Based.toByte(),
                ((freqHz ushr 8) and 0xFF).toByte(),
                (freqHz and 0xFF).toByte()
            )
        )

    fun setVolume(value: Int, mode: Int = 0): ByteArray =
        buildFrame(CMD_SET_VOL, byteArrayOf(mode.toByte(), value.toByte()))

    fun setMute(muted: Boolean): ByteArray =
        buildFrame(CMD_SET_MUTE, byteArrayOf(if (muted) 1 else 0))

    fun chunk20Bytes(frame: ByteArray): List<ByteArray> {
        val chunks = ArrayList<ByteArray>((frame.size + 19) / 20)
        var offset = 0
        while (offset < frame.size) {
            val end = if (offset + 20 < frame.size) offset + 20 else frame.size
            chunks.add(frame.copyOfRange(offset, end))
            offset += 20
        }
        return chunks
    }

    fun parseHexString(input: String): ByteArray? {
        val cleaned = input.replace(Regex("[^0-9A-Fa-f]"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        val out = ByteArray(cleaned.length / 2)
        var i = 0
        while (i < cleaned.length) {
            val byteVal = cleaned.substring(i, i + 2).toIntOrNull(16) ?: return null
            out[i / 2] = byteVal.toByte()
            i += 2
        }
        return out
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
}

@Suppress("MissingPermission")
class DapicBleClient(
    context: Context,
    private val onConnected: (BluetoothDevice) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onNotifyBytes: (ByteArray) -> Unit,
    private val onDeviceDiscovered: (DiscoveredBtDevice) -> Unit,
    private val onStatusMessage: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val txExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var dspChar: BluetoothGattCharacteristic? = null
    private var isScanning = false

    val isHwConnected: Boolean
        get() = gatt != null && dspChar != null

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            stopScan()
            mainHandler.post { onStatusMessage("Scan finished") }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val devName = dev.name ?: result.scanRecord?.deviceName ?: "Unknown BT Device"
            val item = DiscoveredBtDevice(
                device = dev,
                name = devName,
                address = dev.address ?: "",
                rssi = result.rssi,
                isBonded = dev.bondState == BluetoothDevice.BOND_BONDED
            )
            mainHandler.post { onDeviceDiscovered(item) }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@DapicBleClient.gatt = gatt
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                dspChar = null
                this@DapicBleClient.gatt?.close()
                this@DapicBleClient.gatt = null
                mainHandler.post { onDisconnected() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(DapicProtocol.SERVICE_UUID)
            val characteristic = service?.getCharacteristic(DapicProtocol.CHAR_DSP_UUID)
                ?: gatt.services.flatMap { it.characteristics }.firstOrNull { char ->
                    (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }

            if (characteristic == null) {
                mainHandler.post { onStatusMessage("Connected (Audio-only / No DSP GATT char)") }
                return
            }

            dspChar = characteristic
            enableNotifications(gatt, characteristic)

            service?.getCharacteristic(DapicProtocol.CHAR_UART_UUID)?.let { uartChar ->
                mainHandler.postDelayed({ enableNotifications(gatt, uartChar) }, 120L)
            }

            gatt.device?.let { dev -> mainHandler.post { onConnected(dev) } }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            mainHandler.post { onNotifyBytes(data) }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        characteristic.getDescriptor(DapicProtocol.CCCD_UUID)?.let { cccd ->
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }
    }

    fun getBondedDevices(): List<DiscoveredBtDevice> {
        val bonded = bluetoothAdapter?.bondedDevices ?: return emptyList()
        return bonded.map { dev ->
            DiscoveredBtDevice(
                device = dev,
                name = dev.name ?: "Paired Device",
                address = dev.address ?: "",
                rssi = 0,
                isBonded = true
            )
        }
    }

    fun startDiscoveryForPicker(timeoutMs: Long = 12000L) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            onStatusMessage("BLE scanner unavailable")
            return
        }
        if (isScanning) stopScan()
        isScanning = true
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, timeoutMs)
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        gatt?.close()
        gatt = device.connectGatt(appContext, false, gattCallback)
    }

    fun sendFrame(frame: ByteArray) {
        txExecutor.execute {
            val currentGatt = gatt ?: return@execute
            val targetChar = dspChar ?: return@execute
            for (chunk in DapicProtocol.chunk20Bytes(frame)) {
                targetChar.value = chunk
                currentGatt.writeCharacteristic(targetChar)
                try {
                    Thread.sleep(20L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@execute
                }
            }
        }
    }

    fun syncDspState() {
        val queries = listOf(
            DapicProtocol.CMD_GET_MAX_LIMITS,
            DapicProtocol.CMD_GET_FREQ_LIST,
            DapicProtocol.CMD_GET_EQ_MODE,
            DapicProtocol.CMD_GET_EQ_STATE,
            DapicProtocol.CMD_GET_BALANCE_FADER,
            DapicProtocol.CMD_GET_VOL_INFO
        )
        txExecutor.execute {
            for (cmd in queries) {
                sendFrame(DapicProtocol.buildFrame(cmd))
                Thread.sleep(60L)
            }
        }
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        dspChar = null
        mainHandler.post { onDisconnected() }
    }
}
