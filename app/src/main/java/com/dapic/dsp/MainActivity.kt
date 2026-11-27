package com.dapic.dsp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private companion object {
        const val REQ_BLE_PERMS = 101
        const val REQ_ENABLE_BT = 102
    }

    private lateinit var bleClient: DapicBleClient
    private lateinit var tvStatus: TextView
    private lateinit var tvVol: TextView
    private lateinit var tvBass: TextView
    private lateinit var tvTreble: TextView
    private lateinit var tvSub: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvFader: TextView
    private lateinit var tvBand1: TextView
    private lateinit var tvBand2: TextView
    private lateinit var tvBand3: TextView
    private lateinit var tvBand4: TextView
    private lateinit var tvBand5: TextView
    private lateinit var tvLog: TextView

    private lateinit var sbVol: SeekBar
    private lateinit var sbBass: SeekBar
    private lateinit var sbTreble: SeekBar
    private lateinit var sbSub: SeekBar
    private lateinit var sbBalance: SeekBar
    private lateinit var sbFader: SeekBar
    private lateinit var sbBand1: SeekBar
    private lateinit var sbBand2: SeekBar
    private lateinit var sbBand3: SeekBar
    private lateinit var sbBand4: SeekBar
    private lateinit var sbBand5: SeekBar
    private lateinit var etHexCommand: EditText

    private var muted = false
    private var balanceVal = 7
    private var faderVal = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        bleClient = DapicBleClient(
            context = this,
            onConnected = { dev ->
                tvStatus.text = "Status: Connected (${dev.name ?: dev.address})"
                appendLog("Connected to ${dev.name ?: dev.address}")
                bleClient.syncDspState()
            },
            onDisconnected = {
                tvStatus.text = "Status: Disconnected"
                appendLog("Disconnected")
            },
            onNotifyBytes = { bytes ->
                appendLog("RX: ${DapicProtocol.toHex(bytes)}")
            },
            onStatusMessage = { msg ->
                tvStatus.text = "Status: $msg"
                appendLog("INFO: $msg")
            }
        )

        setupButtons()
        setupSliders()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvVol = findViewById(R.id.tvVol)
        tvBass = findViewById(R.id.tvBass)
        tvTreble = findViewById(R.id.tvTreble)
        tvSub = findViewById(R.id.tvSub)
        tvBalance = findViewById(R.id.tvBalance)
        tvFader = findViewById(R.id.tvFader)
        tvBand1 = findViewById(R.id.tvBand1)
        tvBand2 = findViewById(R.id.tvBand2)
        tvBand3 = findViewById(R.id.tvBand3)
        tvBand4 = findViewById(R.id.tvBand4)
        tvBand5 = findViewById(R.id.tvBand5)
        tvLog = findViewById(R.id.tvLog)

        sbVol = findViewById(R.id.sbVol)
        sbBass = findViewById(R.id.sbBass)
        sbTreble = findViewById(R.id.sbTreble)
        sbSub = findViewById(R.id.sbSub)
        sbBalance = findViewById(R.id.sbBalance)
        sbFader = findViewById(R.id.sbFader)
        sbBand1 = findViewById(R.id.sbBand1)
        sbBand2 = findViewById(R.id.sbBand2)
        sbBand3 = findViewById(R.id.sbBand3)
        sbBand4 = findViewById(R.id.sbBand4)
        sbBand5 = findViewById(R.id.sbBand5)
        etHexCommand = findViewById(R.id.etHexCommand)
    }

    private fun setupButtons() {
        val btnMute = findViewById<Button>(R.id.btnMute)
        val btnAutoReconnect = findViewById<Button>(R.id.btnAutoReconnect)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            ensureBleReadyAndScan()
        }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            bleClient.disconnect()
        }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            bleClient.syncDspState()
            appendLog("TX: Sync DSP state")
        }

        btnMute.setOnClickListener {
            muted = !muted
            btnMute.text = if (muted) "Mute: ON" else "Mute: OFF"
            bleClient.sendFrame(DapicProtocol.setMute(muted))
            appendLog("TX: Mute = $muted")
        }

        btnAutoReconnect.setOnClickListener {
            bleClient.autoReconnect = !bleClient.autoReconnect
            btnAutoReconnect.text = if (bleClient.autoReconnect) "Auto-Reconn: ON" else "Auto-Reconn: OFF"
            appendLog("Auto-Reconnect = ${bleClient.autoReconnect}")
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            applyPreset(bass = 7, treble = 7, sub = 7, balance = 7, fader = 7, bands = intArrayOf(7, 7, 7, 7, 7), label = "Flat Reset")
        }

        findViewById<Button>(R.id.btnPresetFlat).setOnClickListener {
            applyPreset(bass = 7, treble = 7, sub = 7, balance = 7, fader = 7, bands = intArrayOf(7, 7, 7, 7, 7), label = "Flat")
        }

        findViewById<Button>(R.id.btnPresetBass).setOnClickListener {
            applyPreset(bass = 12, treble = 8, sub = 12, balance = 7, fader = 7, bands = intArrayOf(12, 10, 7, 8, 9), label = "Bass+")
        }

        findViewById<Button>(R.id.btnPresetVocal).setOnClickListener {
            applyPreset(bass = 6, treble = 10, sub = 6, balance = 7, fader = 7, bands = intArrayOf(6, 8, 11, 11, 9), label = "Vocal")
        }

        findViewById<Button>(R.id.btnSendHex).setOnClickListener {
            val raw = etHexCommand.text.toString()
            val bytes = DapicProtocol.parseHexString(raw)
            if (bytes == null) {
                appendLog("ERR: Invalid hex string")
            } else {
                bleClient.sendFrame(bytes)
                appendLog("TX RAW: ${DapicProtocol.toHex(bytes)}")
            }
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            tvLog.text = "Log: Cleared"
        }
    }

    private fun setupSliders() {
        sbVol.setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvVol.text = "Master Volume: $p"
            bleClient.sendFrame(DapicProtocol.setVolume(p))
            appendLog("TX: Vol = $p")
        })

        sbBass.setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvBass.text = "Bass: $p"
            bleClient.sendFrame(DapicProtocol.setTone(1, p))
            appendLog("TX: Bass = $p")
        })

        sbTreble.setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvTreble.text = "Treble: $p"
            bleClient.sendFrame(DapicProtocol.setTone(2, p))
            appendLog("TX: Treble = $p")
        })

        sbSub.setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvSub.text = "Sub / Tone 3: $p"
            bleClient.sendFrame(DapicProtocol.setTone(3, p))
            appendLog("TX: Tone3 = $p")
        })

        sbBalance.setOnSeekBarChangeListener(simpleSeekListener { p ->
            balanceVal = p
            tvBalance.text = "Balance (L/R): $p"
            bleClient.sendFrame(DapicProtocol.setBalanceFader(balanceVal, faderVal))
            appendLog("TX: Bal=$balanceVal Fad=$faderVal")
        })

        sbFader.setOnSeekBarChangeListener(simpleSeekListener { p ->
            faderVal = p
            tvFader.text = "Fader (F/R): $p"
            bleClient.sendFrame(DapicProtocol.setBalanceFader(balanceVal, faderVal))
            appendLog("TX: Bal=$balanceVal Fad=$faderVal")
        })

        val bandSliders = listOf(
            1 to Pair(sbBand1, tvBand1),
            2 to Pair(sbBand2, tvBand2),
            3 to Pair(sbBand3, tvBand3),
            4 to Pair(sbBand4, tvBand4),
            5 to Pair(sbBand5, tvBand5)
        )

        for ((idx, pair) in bandSliders) {
            val (bar, label) = pair
            bar.setOnSeekBarChangeListener(simpleSeekListener { p ->
                label.text = "EQ Band $idx: $p"
                bleClient.sendFrame(DapicProtocol.setSingleEqBand(idx, p))
                appendLog("TX: Band $idx = $p")
            })
        }
    }

    private fun applyPreset(
        bass: Int,
        treble: Int,
        sub: Int,
        balance: Int,
        fader: Int,
        bands: IntArray,
        label: String
    ) {
        sbBass.progress = bass
        tvBass.text = "Bass: $bass"
        bleClient.sendFrame(DapicProtocol.setTone(1, bass))

        sbTreble.progress = treble
        tvTreble.text = "Treble: $treble"
        bleClient.sendFrame(DapicProtocol.setTone(2, treble))

        sbSub.progress = sub
        tvSub.text = "Sub / Tone 3: $sub"
        bleClient.sendFrame(DapicProtocol.setTone(3, sub))

        balanceVal = balance
        faderVal = fader
        sbBalance.progress = balance
        sbFader.progress = fader
        tvBalance.text = "Balance (L/R): $balance"
        tvFader.text = "Fader (F/R): $fader"
        bleClient.sendFrame(DapicProtocol.setBalanceFader(balance, fader))

        val sliders = listOf(sbBand1, sbBand2, sbBand3, sbBand4, sbBand5)
        val labels = listOf(tvBand1, tvBand2, tvBand3, tvBand4, tvBand5)
        for (i in 0 until 5) {
            val v = bands.getOrElse(i) { 7 }
            sliders[i].progress = v
            labels[i].text = "EQ Band ${i + 1}: $v"
            bleClient.sendFrame(DapicProtocol.setSingleEqBand(i + 1, v))
        }

        appendLog("Preset applied: $label")
    }

    private fun appendLog(line: String) {
        val existing = tvLog.text.toString()
        val lines = existing.split("\n").takeLast(14)
        tvLog.text = (lines + line).joinToString("\n")
    }

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasAllBlePermissions(): Boolean {
        return requiredBlePermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureBleReadyAndScan() {
        if (!hasAllBlePermissions()) {
            tvStatus.text = "Status: Requesting Bluetooth permissions..."
            ActivityCompat.requestPermissions(this, requiredBlePermissions(), REQ_BLE_PERMS)
            return
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null) {
            tvStatus.text = "Status: Bluetooth hardware not found"
            appendLog("ERR: BluetoothAdapter is null")
            return
        }

        if (!adapter.isEnabled) {
            tvStatus.text = "Status: Please enable Bluetooth..."
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            try {
                startActivityForResult(enableIntent, REQ_ENABLE_BT)
            } catch (_: SecurityException) {
                appendLog("ERR: Cannot prompt Bluetooth enable without BLUETOOTH_CONNECT")
            }
            return
        }

        tvStatus.text = "Status: Scanning for ANGHUI / SMARTBT..."
        bleClient.startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERMS) {
            val allGranted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                ensureBleReadyAndScan()
            } else {
                tvStatus.text = "Status: Bluetooth permission denied"
                appendLog("ERR: Grant Nearby devices / Bluetooth permission in App Info")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                ensureBleReadyAndScan()
            } else {
                tvStatus.text = "Status: Bluetooth is off"
                appendLog("INFO: Bluetooth enable declined")
            }
        }
    }

    private fun simpleSeekListener(onChange: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    override fun onDestroy() {
        bleClient.disconnect()
        super.onDestroy()
    }
}
