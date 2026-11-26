package com.dapic.dsp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private lateinit var bleClient: DapicBleClient
    private lateinit var tvStatus: TextView
    private lateinit var tvVol: TextView
    private lateinit var tvBass: TextView
    private lateinit var tvTreble: TextView
    private lateinit var tvBand1: TextView
    private lateinit var tvLog: TextView
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvVol = findViewById(R.id.tvVol)
        tvBass = findViewById(R.id.tvBass)
        tvTreble = findViewById(R.id.tvTreble)
        tvBand1 = findViewById(R.id.tvBand1)
        tvLog = findViewById(R.id.tvLog)

        bleClient = DapicBleClient(
            context = this,
            onConnected = { dev ->
                tvStatus.text = "Status: Connected (${dev.name ?: dev.address})"
                bleClient.syncDspState()
            },
            onDisconnected = {
                tvStatus.text = "Status: Disconnected"
            },
            onNotifyBytes = { bytes ->
                tvLog.text = "RX: ${DapicProtocol.toHex(bytes)}"
            }
        )

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            tvStatus.text = "Status: Scanning for ANGHUI / SMARTBT..."
            requestBleAndScan()
        }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            bleClient.syncDspState()
            tvLog.text = "TX: Sync DSP state"
        }

        findViewById<Button>(R.id.btnMute).setOnClickListener {
            muted = !muted
            bleClient.sendFrame(DapicProtocol.setMute(muted))
            tvLog.text = "TX: Mute = $muted"
        }

        findViewById<SeekBar>(R.id.sbVol).setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvVol.text = "Volume: $p"
            bleClient.sendFrame(DapicProtocol.setVolume(p))
        })

        findViewById<SeekBar>(R.id.sbBass).setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvBass.text = "Bass: $p"
            bleClient.sendFrame(DapicProtocol.setTone(1, p))
        })

        findViewById<SeekBar>(R.id.sbTreble).setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvTreble.text = "Treble: $p"
            bleClient.sendFrame(DapicProtocol.setTone(2, p))
        })

        findViewById<SeekBar>(R.id.sbBand1).setOnSeekBarChangeListener(simpleSeekListener { p ->
            tvBand1.text = "EQ Band 1: $p"
            bleClient.sendFrame(DapicProtocol.setSingleEqBand(1, p))
        })
    }

    private fun requestBleAndScan() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        ActivityCompat.requestPermissions(this, perms, 101)
        bleClient.startScan()
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
