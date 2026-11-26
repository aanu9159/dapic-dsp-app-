package com.dapic.dsp

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
import java.util.UUID
import java.util.concurrent.Executors

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

    fun setSingleEqBand(bandIndex: Int, value: Int): ByteArray =
        buildFrame(CMD_SET_SINGLE_EQ_BAND, byteArrayOf(bandIndex.toByte(), value.toByte()))

    fun setTone(toneType: Int, value: Int): ByteArray =
        buildFrame(CMD_SET_TONE, byteArrayOf(toneType.toByte(), value.toByte()))

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

    fun isDapicDeviceName(name: String?): Boolean {
        val upper = name?.toUpperCase() ?: return false
        return upper.contains("ANGHUI") || upper.contains("SMARTBT")
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
}

@SuppressLint("MissingPermission")
class DapicBleClient(
    context: Context,
    private val onConnected: (BluetoothDevice) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onNotifyBytes: (ByteArray) -> Unit
) {
    private val appContext = context.applicationContext
    private val bluetoothAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val txExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var dspChar: BluetoothGattCharacteristic? = null
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            if (DapicProtocol.isDapicDeviceName(dev.name)) {
                stopScan()
                connect(dev)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                this@DapicBleClient.gatt = gatt
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                dspChar = null
                mainHandler.post { onDisconnected() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(DapicProtocol.SERVICE_UUID) ?: return
            val characteristic = service.getCharacteristic(DapicProtocol.CHAR_DSP_UUID) ?: return
            dspChar = characteristic
            enableNotifications(gatt, characteristic)

            service.getCharacteristic(DapicProtocol.CHAR_UART_UUID)?.let { uartChar ->
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

    fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (isScanning) return
        isScanning = true
        scanner.startScan(scanCallback)
    }

    fun stopScan() {
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
    }
}
