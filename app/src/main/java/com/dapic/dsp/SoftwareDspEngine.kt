package com.dapic.dsp

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb

class SoftwareDspEngine(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var presetReverb: PresetReverb? = null

    var enabled: Boolean = true
    private val bands20 = IntArray(20) { 7 }

    init {
        initEffects()
    }

    private fun initEffects() {
        try {
            equalizer = Equalizer(0, 0).apply { enabled = true }
        } catch (_: Throwable) {
            equalizer = null
        }

        try {
            bassBoost = BassBoost(0, 0).apply { enabled = true }
        } catch (_: Throwable) {
            bassBoost = null
        }

        try {
            presetReverb = PresetReverb(0, 0).apply {
                preset = PresetReverb.PRESET_NONE
                enabled = false
            }
        } catch (_: Throwable) {
            presetReverb = null
        }
    }

    fun setMasterVolume(level0to63: Int) {
        if (!enabled) return
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val mapped = ((level0to63 / 63.0f) * maxVol).toInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mapped, 0)
        } catch (_: Throwable) {
        }
    }

    fun setConcertHall(active: Boolean, modeIndex: Int) {
        val reverb = presetReverb ?: return
        try {
            if (!enabled || !active) {
                reverb.preset = PresetReverb.PRESET_NONE
                reverb.enabled = false
                return
            }
            reverb.preset = when (modeIndex) {
                0 -> PresetReverb.PRESET_SMALLROOM
                1 -> PresetReverb.PRESET_MEDIUMHALL
                2 -> PresetReverb.PRESET_LARGEHALL
                else -> PresetReverb.PRESET_PLATE
            }
            reverb.enabled = true
        } catch (_: Throwable) {
        }
    }

    fun setEq20Band(bandIndex1Based: Int, value0to14: Int) {
        val idx = bandIndex1Based - 1
        if (idx !in 0 until 20) return
        bands20[idx] = value0to14
        apply20BandsToHardwareEq()
    }

    private fun apply20BandsToHardwareEq() {
        val eq = equalizer ?: return
        if (!enabled) return
        try {
            val numHwBands = eq.numberOfBands.toInt()
            if (numHwBands <= 0) return
            val range = eq.bandLevelRange
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()

            for (hwBand in 0 until numHwBands) {
                val startIdx = (hwBand * 20) / numHwBands
                val endIdx = (((hwBand + 1) * 20) / numHwBands).coerceAtLeast(startIdx + 1)
                var sum = 0
                var count = 0
                for (i in startIdx until endIdx.coerceAtMost(20)) {
                    sum += bands20[i]
                    count++
                }
                val avg0to14 = if (count > 0) sum / count else 7
                val norm = (avg0to14 - 7) / 7.0f
                val targetMb = if (norm >= 0f) {
                    (norm * maxMb).toInt()
                } else {
                    (-norm * minMb).toInt()
                }
                eq.setBandLevel(hwBand.toShort(), targetMb.coerceIn(minMb, maxMb).toShort())
            }
        } catch (_: Throwable) {
        }
    }

    fun setBassBoostStrength(strength0to1000: Int) {
        val bb = bassBoost ?: return
        if (!enabled) return
        try {
            if (bb.strengthSupported) {
                bb.setStrength(strength0to1000.coerceIn(0, 1000).toShort())
            }
        } catch (_: Throwable) {
        }
    }

    fun applyCrossoverPreview(flHz: Int, frHz: Int, rlHz: Int, rrHz: Int) {
        // Software preview: adjusts sub-bass boost inversely to high-pass cutoff
        val avgCutoff = (flHz + frHz + rlHz + rrHz) / 4
        val bassStrength = ((250 - avgCutoff).coerceIn(0, 230) * 1000) / 230
        setBassBoostStrength(bassStrength)
    }

    fun release() {
        try { equalizer?.release() } catch (_: Throwable) {}
        try { bassBoost?.release() } catch (_: Throwable) {}
        try { presetReverb?.release() } catch (_: Throwable) {}
    }
}
