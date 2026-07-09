package com.diogo.snesdeco.ui

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.diogo.snesdeco.R
import com.diogo.snesdeco.emu.NativeBridge
import com.diogo.snesdeco.rom.RomRepository
import java.nio.ShortBuffer
import kotlin.concurrent.thread

class EmulatorActivity : AppCompatActivity() {

    private lateinit var videoView: ImageView
    private lateinit var pcReadout: TextView
    private lateinit var cdlReadout: TextView

    private var bitmap: Bitmap? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var running = false
    private var loopThread: Thread? = null
    private var frameCounter = 0
    private var totalSamplesSeen = 0L
    private var reportedSampleCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulator)

        videoView = findViewById(R.id.videoView)
        pcReadout = findViewById(R.id.pcReadout)
        cdlReadout = findViewById(R.id.cdlReadout)

        val bytes = RomRepository.bytes
        if (bytes == null) {
            Toast.makeText(this, "Nenhuma ROM carregada", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupButtons()

        thread {
            val initOk = NativeBridge.nativeInit()
            val loadOk = initOk && NativeBridge.nativeLoadRom(bytes)
            runOnUiThread {
                if (!loadOk) {
                    Toast.makeText(this, "Falha ao iniciar o core do emulador", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    setupAudio()
                    startLoop()
                }
            }
        }
    }

    private fun setupAudio() {
        try {
            val sampleRate = 32040
            var minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) minBuf = sampleRate * 2 * 2 / 10 // ~100ms fallback if the device query fails
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Toast.makeText(this, "Áudio: falhou ao inicializar (state=${track.state}, minBuf=$minBuf)", Toast.LENGTH_LONG).show()
                audioTrack = null
                return
            }

            track.play()
            audioTrack = track
            Toast.makeText(this, "Áudio OK: bufBytes=${minBuf * 4}, playState=${track.playState}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Áudio: exceção - ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            audioTrack = null
        }
    }

    private fun startLoop() {
        running = true
        loopThread = thread(start = true) {
            val frameIntervalNs = 16_666_667L // ~60 fps (NTSC)
            var nextFrameTime = System.nanoTime()

            while (running) {
                NativeBridge.nativeRunFrame()

                val w = NativeBridge.nativeGetFrameWidth()
                val h = NativeBridge.nativeGetFrameHeight()
                val pixels = NativeBridge.nativeGetVideoFrame()
                val samples = NativeBridge.nativeGetAudioSamples()

                if (samples.isNotEmpty()) {
                    audioTrack?.write(samples, 0, samples.size)
                }
                totalSamplesSeen += samples.size

                if (!reportedSampleCheck && frameCounter >= 120) {
                    reportedSampleCheck = true
                    val seen = totalSamplesSeen
                    val trackState = audioTrack?.playState
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Diagnóstico: $seen amostras geradas em 120 frames | AudioTrack playState=$trackState",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                if (frameCounter % 60 == 0) {
                    android.util.Log.i("SNESDeco", "frame=$frameCounter samples=${samples.size} playState=${audioTrack?.playState}")
                }

                renderFrame(pixels, w, h)

                frameCounter++
                if (frameCounter % 15 == 0) {
                    updateReadouts()
                }

                nextFrameTime += frameIntervalNs
                val sleepNs = nextFrameTime - System.nanoTime()
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) } catch (e: InterruptedException) { }
                } else {
                    nextFrameTime = System.nanoTime()
                }
            }
        }
    }

    private fun renderFrame(pixels: ShortArray, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || pixels.isEmpty()) return
        var bmp = bitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            bitmap = bmp
        }
        bmp.copyPixelsFromBuffer(ShortBuffer.wrap(pixels))
        runOnUiThread {
            videoView.setImageBitmap(bmp)
        }
    }

    private fun updateReadouts() {
        val bank = NativeBridge.nativeGetCurrentBank()
        val addr = NativeBridge.nativeGetCurrentAddr()
        val cdl = NativeBridge.nativeGetCdlMap()
        RomRepository.cdlMap = cdl
        val coded = cdl.count { (it.toInt() and 0x03) != 0 }
        val pct = if (cdl.isNotEmpty()) (coded * 100.0 / cdl.size) else 0.0

        runOnUiThread {
            pcReadout.text = "PC: %02X:%04X".format(bank, addr)
            cdlReadout.text = "CDL: %.1f%% da ROM já mapeada como código/operando".format(pct)
        }
    }

    private fun setupButtons() {
        bindButton(R.id.btnUp, NativeBridge.Button.UP)
        bindButton(R.id.btnDown, NativeBridge.Button.DOWN)
        bindButton(R.id.btnLeft, NativeBridge.Button.LEFT)
        bindButton(R.id.btnRight, NativeBridge.Button.RIGHT)
        bindButton(R.id.btnA, NativeBridge.Button.A)
        bindButton(R.id.btnB, NativeBridge.Button.B)
        bindButton(R.id.btnX, NativeBridge.Button.X)
        bindButton(R.id.btnY, NativeBridge.Button.Y)
        bindButton(R.id.btnL, NativeBridge.Button.L)
        bindButton(R.id.btnR, NativeBridge.Button.R)
        bindButton(R.id.btnStart, NativeBridge.Button.START)
        bindButton(R.id.btnSelect, NativeBridge.Button.SELECT)
    }

    private fun bindButton(viewId: Int, button: Int) {
        val v = findViewById<Button>(viewId)
        v.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> NativeBridge.nativeSetButton(button, true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeBridge.nativeSetButton(button, false)
            }
            true
        }
    }

    override fun onPause() {
        super.onPause()
        running = false
        loopThread?.join(200)
        audioTrack?.pause()
        audioTrack?.flush()
    }

    override fun onResume() {
        super.onResume()
        if (NativeBridge.nativeIsRomLoaded() && !running) {
            audioTrack?.play()
            startLoop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        loopThread?.join(200)
        audioTrack?.stop()
        audioTrack?.release()
    }
}
