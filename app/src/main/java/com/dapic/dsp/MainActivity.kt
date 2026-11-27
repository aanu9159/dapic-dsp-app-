package com.dapic.dsp

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

    private data class ChannelSliderConfig(
        val index: Int,
        val seekBar: SeekBar,
        val label: TextView,
        val prefix: String
    )

    private lateinit var bleClient: DapicBleClient
    private lateinit var swDsp: SoftwareDspEngine

    private lateinit var tvStatus: TextView
    private lateinit var tvVol: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvFader: TextView
    private lateinit var tvChFL: TextView
    private lateinit var tvChFR: TextView
    private lateinit var tvChRL: TextView
    private lateinit var tvChRR: TextView
    private lateinit var tvFreqFL: TextView
    private lateinit var tvFreqFR: TextView
    private lateinit var tvFreqRL: TextView
    private lateinit var tvFreqRR: TextView
    private lateinit var tvLog: TextView

    private lateinit var sbVol: SeekBar
    private lateinit var sbBalance: SeekBar
    private lateinit var sbFader: SeekBar
    private lateinit var sbChFL: SeekBar
    private lateinit var sbChFR: SeekBar
    private lateinit var sbChRL: SeekBar
    private lateinit var sbChRR: SeekBar
    private lateinit var sbFreqFL: SeekBar
    private lateinit var sbFreqFR: SeekBar
    private lateinit var sbFreqRL: SeekBar
    private lateinit var sbFreqRR: SeekBar
    private lateinit var llEq20Container: LinearLayout
    private lateinit var etHexCommand: EditText

    private val eq20Sliders = ArrayList<SeekBar>(20)
    private val eq20Labels = ArrayList<TextView>(20)

    private val discoveredDevices = LinkedHashMap<String, DiscoveredBtDevice>()
    private var pickerAdapter: ArrayAdapter<String>? = null
    private val pickerDisplayList = ArrayList<String>()

    private var muted = false
    private var concertHallOn = false
    private var hallModeIndex = 2
    private val hallModeNames = arrayOf("Small", "Medium", "Hall", "Arena")

    private var balanceVal = 7
    private var faderVal = 7

    private val chGains = intArrayOf(10, 10, 10, 10)
    private val chMuted = booleanArrayOf(false, false, false, false)
    private val chFreqs = intArrayOf(80, 80, 80, 80)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swDsp = SoftwareDspEngine(this)
        bindViews()
        build20BandEqUi()

        bleClient = DapicBleClient(
            context = this,
            onConnected = { dev ->
                tvStatus.text = "Status: HW Connected (${dev.name ?: dev.address})"
                appendLog("HW Connected: ${dev.name ?: dev.address}")
                bleClient.syncDspState()
            },
            onDisconnected = {
                tvStatus.text = "Status: Disconnected (SW DSP Active)"
                appendLog("HW Disconnected -> Software DSP Active")
            },
            onNotifyBytes = { bytes ->
                appendLog("RX: ${DapicProtocol.toHex(bytes)}")
            },
            onDeviceDiscovered = { item ->
                if (!discoveredDevices.containsKey(item.address)) {
                    discoveredDevices[item.address] = item
                    val tag = if (item.isBonded) "[Paired]" else "[BLE ${item.rssi}dBm]"
                    pickerDisplayList.add("${item.name} $tag\n${item.address}")
                    pickerAdapter?.notifyDataSetChanged()
                }
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
        tvBalance = findViewById(R.id.tvBalance)
        tvFader = findViewById(R.id.tvFader)
        tvChFL = findViewById(R.id.tvChFL)
        tvChFR = findViewById(R.id.tvChFR)
        tvChRL = findViewById(R.id.tvChRL)
        tvChRR = findViewById(R.id.tvChRR)
        tvFreqFL = findViewById(R.id.tvFreqFL)
        tvFreqFR = findViewById(R.id.tvFreqFR)
        tvFreqRL = findViewById(R.id.tvFreqRL)
        tvFreqRR = findViewById(R.id.tvFreqRR)
        tvLog = findViewById(R.id.tvLog)

        sbVol = findViewById(R.id.sbVol)
        sbBalance = findViewById(R.id.sbBalance)
        sbFader = findViewById(R.id.sbFader)
        sbChFL = findViewById(R.id.sbChFL)
        sbChFR = findViewById(R.id.sbChFR)
        sbChRL = findViewById(R.id.sbChRL)
        sbChRR = findViewById(R.id.sbChRR)
        sbFreqFL = findViewById(R.id.sbFreqFL)
        sbFreqFR = findViewById(R.id.sbFreqFR)
        sbFreqRL = findViewById(R.id.sbFreqRL)
        sbFreqRR = findViewById(R.id.sbFreqRR)
        llEq20Container = findViewById(R.id.llEq20Container)
        etHexCommand = findViewById(R.id.etHexCommand)
    }

    private fun build20BandEqUi() {
        eq20Sliders.clear()
        eq20Labels.clear()
        llEq20Container.removeAllViews()

        for (i in 0 until 20) {
            val bandNum = i + 1
            val freqLabel = DapicProtocol.EQ_20_LABELS[i]

            val tv = TextView(this).apply {
                text = "Band $bandNum ($freqLabel): 0 dB (7)"
                setPadding(0, 8, 0, 2)
            }

            val sb = SeekBar(this).apply {
                max = 14
                progress = 7
                setOnSeekBarChangeListener(simpleSeekListener { p ->
                    val db = p - 7
                    val sign = if (db > 0) "+" else ""
                    tv.text = "Band $bandNum ($freqLabel): $sign$db dB ($p)"
                    bleClient.sendFrame(DapicProtocol.setSingleEqBand(bandNum, p))
                    swDsp.setEq20Band(bandNum, p)
                    appendLog("EQ B$bandNum ($freqLabel) -> $p")
                })
            }

            eq20Labels.add(tv)
            eq20Sliders.add(sb)
            llEq20Container.addView(tv)
            llEq20Container.addView(sb)
        }
    }

    private fun setupButtons() {
        val btnMute = findViewById<Button>(R.id.btnMute)
        val btnSwFallback = findViewById<Button>(R.id.btnSwFallback)
        val btnConcertHall = findViewById<Button>(R.id.btnConcertHall)
        val btnHallMode = findViewById<Button>(R.id.btnHallMode)

        findViewById<Button>(R.id.btnSelectDevice).setOnClickListener {
            openBluetoothDevicePicker()
        }

        findViewById<Button>(R.id.btnClassicBt).setOnClickListener {
            appendLog("Opening Android Bluetooth Audio settings...")
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
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
            if (muted) swDsp.setMasterVolume(0) else swDsp.setMasterVolume(sbVol.progress)
            appendLog("Mute = $muted")
        }

        btnSwFallback.setOnClickListener {
            swDsp.enabled = !swDsp.enabled
            btnSwFallback.text = if (swDsp.enabled) "SW DSP: ON" else "SW DSP: OFF"
            appendLog("Software DSP Fallback = ${swDsp.enabled}")
        }

        btnConcertHall.setOnClickListener {
            concertHallOn = !concertHallOn
            btnConcertHall.text = if (concertHallOn) "Concert Hall: ON" else "Concert Hall: OFF"
            bleClient.sendFrame(DapicProtocol.setConcertHall(concertHallOn, hallModeIndex))
            swDsp.setConcertHall(concertHallOn, hallModeIndex)
            appendLog("Concert Hall = $concertHallOn (${hallModeNames[hallModeIndex]})")
        }

        btnHallMode.setOnClickListener {
            hallModeIndex = (hallModeIndex + 1) % hallModeNames.size
            btnHallMode.text = "Mode: ${hallModeNames[hallModeIndex]}"
            if (concertHallOn) {
                bleClient.sendFrame(DapicProtocol.setConcertHall(true, hallModeIndex))
                swDsp.setConcertHall(true, hallModeIndex)
            }
            appendLog("Hall Mode = ${hallModeNames[hallModeIndex]}")
        }

        findViewById<Button>(R.id.btnLoudness).setOnClickListener {
            bleClient.sendFrame(DapicProtocol.setTone(1, 13))
            swDsp.setBassBoostStrength(750)
            appendLog("Bass Boost applied (HW + SW)")
        }

        findViewById<Button>(R.id.btnCenterLR).setOnClickListener {
            balanceVal = 7
            sbBalance.progress = 7
            tvBalance.text = "Left / Right Balance: 0 (Center)"
            bleClient.sendFrame(DapicProtocol.setBalanceFader(balanceVal, faderVal))
            appendLog("Balance L/R centered")
        }

        findViewById<Button>(R.id.btnCenterFR).setOnClickListener {
            faderVal = 7
            sbFader.progress = 7
            tvFader.text = "Front / Rear Fader: 0 (Center)"
            bleClient.sendFrame(DapicProtocol.setBalanceFader(balanceVal, faderVal))
            appendLog("Fader F/R centered")
        }

        findViewById<Button>(R.id.btnCenterAll).setOnClickListener {
            balanceVal = 7
            faderVal = 7
            sbBalance.progress = 7
            sbFader.progress = 7
            tvBalance.text = "Left / Right Balance: 0 (Center)"
            tvFader.text = "Front / Rear Fader: 0 (Center)"
            bleClient.sendFrame(DapicProtocol.setBalanceFader(7, 7))
            appendLog("Staging centered (L/R + F/R)")
        }

        val chButtons = listOf(
            findViewById<Button>(R.id.btnMuteFL) to "FL",
            findViewById<Button>(R.id.btnMuteFR) to "FR",
            findViewById<Button>(R.id.btnMuteRL) to "RL",
            findViewById<Button>(R.id.btnMuteRR) to "RR"
        )
        for (idx in 0 until 4) {
            val (btn, code) = chButtons[idx]
            btn.setOnClickListener {
                chMuted[idx] = !chMuted[idx]
                btn.text = if (chMuted[idx]) "$code: MUTE" else "$code: ON"
                bleClient.sendFrame(DapicProtocol.setChannelGain(idx + 1, chGains[idx], chMuted[idx]))
                appendLog("Channel $code muted = ${chMuted[idx]}")
            }
        }

        findViewById<Button>(R.id.btnXoverFull).setOnClickListener { applyAllChannelFreq(20) }
        findViewById<Button>(R.id.btnXover80).setOnClickListener { applyAllChannelFreq(80) }
        findViewById<Button>(R.id.btnXover120).setOnClickListener { applyAllChannelFreq(120) }

        findViewById<Button>(R.id.btnEqFlat20).setOnClickListener {
            apply20BandCurve(IntArray(20) { 7 }, "Flat 20-EQ")
        }
        findViewById<Button>(R.id.btnEqBass20).setOnClickListener {
            apply20BandCurve(
                intArrayOf(13, 13, 12, 11, 10, 9, 8, 7, 7, 7, 7, 7, 8, 8, 9, 9, 10, 10, 9, 9),
                "Mega Bass 20-EQ"
            )
        }
        findViewById<Button>(R.id.btnEqRock20).setOnClickListener {
            apply20BandCurve(
                intArrayOf(11, 10, 9, 8, 7, 6, 6, 7, 8, 8, 9, 10, 10, 11, 11, 12, 12, 11, 10, 10),
                "Clear V-EQ 20-Band"
            )
        }

        findViewById<Button>(R.id.btnSendHex).setOnClickListener {
            val bytes = DapicProtocol.parseHexString(etHexCommand.text.toString())
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
            tvVol.text = "Master Volume: $p / 63"
            bleClient.sendFrame(DapicProtocol.setVolume(p))
            swDsp.setMasterVolume(p)
        })

        sbBalance.setOnSeekBarChangeListener(simpleSeekListener { p ->
            balanceVal = p
            val offset = p - 7
            val side = when {
                offset < 0 -> "L${-offset}"
                offset > 0 -> "R$offset"
                else -> "0 (Center)"
            }
            tvBalance.text = "Left / Right Balance: $side"
            bleClient.sendFrame(DapicProtocol.setBalanceFader(balanceVal, faderVal))
        })

        sbFader.setOnSeekBarChangeListener(simpleSeekListener { p ->
            faderVal = p
            val offset = p - 7
            val pos = when {
                offset < 0 -> "Rear ${-offset}"
                offset > 0 -> "Front $offset"
                else -> "0 (Center)"
            }
            tvFader.text = "Front / Rear Fader: $pos"
            bleClient.sendFrame(DapicProtocol.setBalanceFader(balanceVal, faderVal))
        })

        val chConfigs = listOf(
            ChannelSliderConfig(0, sbChFL, tvChFL, "CH1 Front-Left Gain"),
            ChannelSliderConfig(1, sbChFR, tvChFR, "CH2 Front-Right Gain"),
            ChannelSliderConfig(2, sbChRL, tvChRL, "CH3 Rear-Left Gain"),
            ChannelSliderConfig(3, sbChRR, tvChRR, "CH4 Rear-Right Gain")
        )
        for (cfg in chConfigs) {
            cfg.seekBar.setOnSeekBarChangeListener(simpleSeekListener { p ->
                chGains[cfg.index] = p
                cfg.label.text = "${cfg.prefix}: $p / 15"
                bleClient.sendFrame(DapicProtocol.setChannelGain(cfg.index + 1, p, chMuted[cfg.index]))
            })
        }

        val freqConfigs = listOf(
            ChannelSliderConfig(0, sbFreqFL, tvFreqFL, "CH1 FL High-Pass Freq"),
            ChannelSliderConfig(1, sbFreqFR, tvFreqFR, "CH2 FR High-Pass Freq"),
            ChannelSliderConfig(2, sbFreqRL, tvFreqRL, "CH3 RL High-Pass Freq"),
            ChannelSliderConfig(3, sbFreqRR, tvFreqRR, "CH4 RR Low/High-Pass Freq")
        )
        for (cfg in freqConfigs) {
            cfg.seekBar.setOnSeekBarChangeListener(simpleSeekListener { p ->
                val hz = 20 + p
                chFreqs[cfg.index] = hz
                cfg.label.text = "${cfg.prefix}: $hz Hz"
                bleClient.sendFrame(DapicProtocol.setChannelCrossover(cfg.index + 1, hz))
                swDsp.applyCrossoverPreview(chFreqs[0], chFreqs[1], chFreqs[2], chFreqs[3])
            })
        }
    }

    private fun applyAllChannelFreq(hz: Int) {
        val progress = (hz - 20).coerceIn(0, 230)
        val configs = listOf(
            ChannelSliderConfig(0, sbFreqFL, tvFreqFL, "CH1 FL High-Pass Freq"),
            ChannelSliderConfig(1, sbFreqFR, tvFreqFR, "CH2 FR High-Pass Freq"),
            ChannelSliderConfig(2, sbFreqRL, tvFreqRL, "CH3 RL High-Pass Freq"),
            ChannelSliderConfig(3, sbFreqRR, tvFreqRR, "CH4 RR Low/High-Pass Freq")
        )
        for (cfg in configs) {
            chFreqs[cfg.index] = hz
            cfg.seekBar.progress = progress
            cfg.label.text = "${cfg.prefix}: $hz Hz"
            bleClient.sendFrame(DapicProtocol.setChannelCrossover(cfg.index + 1, hz))
        }
        swDsp.applyCrossoverPreview(hz, hz, hz, hz)
        appendLog("4-Ch Crossover set to $hz Hz")
    }

    private fun apply20BandCurve(values: IntArray, name: String) {
        for (i in 0 until 20) {
            val v = values.getOrElse(i) { 7 }.coerceIn(0, 14)
            val db = v - 7
            val sign = if (db > 0) "+" else ""
            eq20Sliders[i].progress = v
            eq20Labels[i].text = "Band ${i + 1} (${DapicProtocol.EQ_20_LABELS[i]}): $sign$db dB ($v)"
            bleClient.sendFrame(DapicProtocol.setSingleEqBand(i + 1, v))
            swDsp.setEq20Band(i + 1, v)
        }
        appendLog("Applied 20-Band Preset: $name")
    }

    private fun openBluetoothDevicePicker() {
        if (!hasAllBlePermissions()) {
            ActivityCompat.requestPermissions(this, requiredBlePermissions(), REQ_BLE_PERMS)
            return
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null) {
            tvStatus.text = "Status: Bluetooth unavailable"
            return
        }
        if (!adapter.isEnabled) {
            try {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            } catch (_: SecurityException) {
                appendLog("ERR: Enable Bluetooth manually")
            }
            return
        }

        discoveredDevices.clear()
        pickerDisplayList.clear()

        for (bonded in bleClient.getBondedDevices()) {
            discoveredDevices[bonded.address] = bonded
            pickerDisplayList.add("${bonded.name} [Paired]\n${bonded.address}")
        }

        val adapterList = ArrayAdapter(this, android.R.layout.simple_list_item_1, pickerDisplayList)
        pickerAdapter = adapterList

        bleClient.startDiscoveryForPicker(12000L)

        AlertDialog.Builder(this)
            .setTitle("Select Bluetooth Device")
            .setAdapter(adapterList) { _, which ->
                bleClient.stopScan()
                val selected = discoveredDevices.values.elementAtOrNull(which) ?: return@setAdapter
                tvStatus.text = "Status: Connecting to ${selected.name}..."
                appendLog("Selected: ${selected.name} (${selected.address})")
                bleClient.connect(selected.device)
            }
            .setNeutralButton("Audio BT Settings") { _, _ ->
                bleClient.stopScan()
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton("Close") { _, _ ->
                bleClient.stopScan()
            }
            .setOnDismissListener {
                bleClient.stopScan()
            }
            .show()
    }

    private fun appendLog(line: String) {
        val existing = tvLog.text.toString()
        val lines = existing.split("\n").takeLast(12)
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLE_PERMS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                openBluetoothDevicePicker()
            } else {
                tvStatus.text = "Status: Bluetooth permission denied"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT && resultCode == RESULT_OK) {
            openBluetoothDevicePicker()
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
        swDsp.release()
        super.onDestroy()
    }
}
