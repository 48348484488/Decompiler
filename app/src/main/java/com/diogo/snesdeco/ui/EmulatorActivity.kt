package com.diogo.snesdeco.ui

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.diogo.snesdeco.R
import com.diogo.snesdeco.emu.NativeBridge
import com.diogo.snesdeco.emu.SpriteRipper
import com.diogo.snesdeco.rom.RomRepository
import java.nio.ShortBuffer
import kotlin.concurrent.thread

class EmulatorActivity : AppCompatActivity() {

    private lateinit var videoView: ImageView
    private lateinit var pcReadout: TextView
    private lateinit var cdlReadout: TextView

    private var audioTrack: AudioTrack? = null

    @Volatile private var running = false
    private var loopThread: Thread? = null
    private var frameCounter = 0
    private var totalSamplesSeen = 0L
    private var reportedSampleCheck = false
    private var reportedWriteError = false
    @Volatile private var sandboxFrozen = false

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
            val sampleRate = 48000
            // Some OEM Androids (MIUI etc.) silence apps that never request
            // audio focus; ask for it before playing.
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN)
            } catch (_: Exception) { }

            var minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) minBuf = sampleRate * 2 * 2 / 10
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

            // AudioTrack is confirmed working (ps=3 PLAYING) but sound wasn't
            // audible. Force the media stream to a high volume so device
            // volume / silent mode can't be the cause. (This raises MEDIA
            // volume specifically, not ringer.)
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.8).toInt().coerceAtLeast(1), 0)
                val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                    Toast.makeText(this, "Volume de mídia ajustado p/ $vol/$maxVol. Se ainda mudo, tire o celular do silencioso (ícone 🔕 na barra).", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Some devices restrict setStreamVolume; ignore and rely on manual volume.
            }
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
                // Sandbox: if we've hit the edge of the captured slice, stop
                // advancing the game (freeze on the last captured frame) but
                // keep the loop alive so the screen stays shown.
                if (sandboxFrozen) {
                    try { Thread.sleep(50) } catch (e: InterruptedException) {}
                    continue
                }

                NativeBridge.nativeRunFrame()

                // Detect boundary right after running: did the game try to
                // execute code that was never captured?
                if (NativeBridge.nativeIsSandbox() && NativeBridge.nativeBoundaryHit() && !sandboxFrozen) {
                    sandboxFrozen = true
                    val off = NativeBridge.nativeBoundaryOffset()
                    runOnUiThread {
                        findViewById<android.widget.Button>(R.id.btnSandbox).text = "⏸ Congelado (toque=sair)"
                        Toast.makeText(
                            this,
                            "⏸ Congelado: o jogo chegou no fim do trecho capturado (tentou rodar código novo em offset %06X). Tudo que rodou até aqui É código capturado.".format(off),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // Render the last good frame once more, then idle.
                    continue
                }

                val w = NativeBridge.nativeGetFrameWidth()
                val h = NativeBridge.nativeGetFrameHeight()
                val pixels = NativeBridge.nativeGetVideoFrame()
                val samples = NativeBridge.nativeGetAudioSamples()

                if (samples.isNotEmpty()) {
                    val written = audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING) ?: -999
                    if (written < 0 && !reportedWriteError) {
                        reportedWriteError = true
                        runOnUiThread {
                            Toast.makeText(this, "ERRO write audio: código $written", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                totalSamplesSeen += samples.size

                if (!reportedSampleCheck && frameCounter >= 120) {
                    reportedSampleCheck = true
                    val seen = totalSamplesSeen
                    val trackState = audioTrack?.playState
                    // Peak amplitude across the samples we just got tells us if
                    // the DSP is producing real audio or just silence (zeros).
                    var peak = 0
                    for (s in samples) {
                        val a = kotlin.math.abs(s.toInt())
                        if (a > peak) peak = a
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "AUDIO: pico=$peak (0=mudo, >0=tem som) | total=$seen | ps=$trackState",
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

                // Full-capture mode: every ~5s snapshot sprites+palette and
                // dedupe into the session (runs off-thread, guarded by busy).
                if (com.diogo.snesdeco.emu.ExtractionSession.enabled &&
                    frameCounter % 300 == 0 &&
                    com.diogo.snesdeco.emu.ExtractionSession.busy.compareAndSet(false, true)
                ) {
                    try {
                        val oam = NativeBridge.nativeGetOam()
                        val vram = NativeBridge.nativeGetVram()
                        val cgram = NativeBridge.nativeGetCgram()
                        val nb = NativeBridge.nativeGetObjNameBase()
                        val ns = NativeBridge.nativeGetObjNameSelect()
                        val ss = NativeBridge.nativeGetObjSizeSelect()
                        thread {
                            try {
                                val groups = SpriteRipper.ripGroups(oam, vram, cgram, nb, ns, ss)
                                com.diogo.snesdeco.emu.ExtractionSession.addSprites(groups)
                                com.diogo.snesdeco.emu.ExtractionSession.addPalette(cgram)
                                com.diogo.snesdeco.emu.ExtractionSession.captures++
                            } finally {
                                com.diogo.snesdeco.emu.ExtractionSession.busy.set(false)
                            }
                        }
                    } catch (t: Throwable) {
                        com.diogo.snesdeco.emu.ExtractionSession.busy.set(false)
                    }
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
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        bmp.copyPixelsFromBuffer(ShortBuffer.wrap(pixels))

        // fitCenter on the ImageView does NOT upscale this bitmap as expected
        // (confirmed on-device: view is 2590x420 but the frame stayed ~native
        // size). So scale explicitly to the view's real measured size,
        // preserving the SNES aspect ratio, and hand over a ready-to-draw bmp.
        val vw = videoView.width
        val vh = videoView.height
        val out = if (vw > 0 && vh > 0) {
            val scale = minOf(vw.toFloat() / w, vh.toFloat() / h)
            val dstW = (w * scale).toInt().coerceAtLeast(1)
            val dstH = (h * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, dstW, dstH, false)
        } else bmp

        runOnUiThread {
            videoView.setImageBitmap(out)
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
            val ext = if (com.diogo.snesdeco.emu.ExtractionSession.enabled)
                " | EXT: ${com.diogo.snesdeco.emu.ExtractionSession.spriteCount()}spr ${com.diogo.snesdeco.emu.ExtractionSession.paletteCount()}pal"
            else ""
            cdlReadout.text = "CDL %.1f%%%s".format(pct, ext)
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

        findViewById<android.widget.Button>(R.id.btnLab).setOnClickListener {
            openLab()
        }

        // Capture toggle: turn CDL recording on/off. Starts ON (capture from
        // frame 1). Turning it off freezes the captured set so you can then
        // use Sandbox to replay only what you captured.
        val captureBtn = findViewById<android.widget.Button>(R.id.btnCapture)
        captureBtn.text = if (NativeBridge.nativeIsCdlRecording()) "● Capturar: ON" else "○ Capturar: OFF"
        captureBtn.setOnClickListener {
            val turnOn = !NativeBridge.nativeIsCdlRecording()
            NativeBridge.nativeSetCdlRecording(turnOn)
            captureBtn.text = if (turnOn) "● Capturar: ON" else "○ Capturar: OFF"
            Toast.makeText(
                this,
                if (turnOn) "Capturando: todo código que o jogo executar está sendo gravado."
                else "Captura pausada. O que já foi capturado está guardado.",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Restart ROM: reset the console to the very beginning, so you can
        // capture a run from the intro. Optionally clears the captured map.
        findViewById<android.widget.Button>(R.id.btnRestart).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Reiniciar ROM")
                .setMessage("Reiniciar o jogo do começo. Quer também apagar o que já foi capturado, para gravar uma sessão limpa desde o início?")
                .setPositiveButton("Reiniciar e limpar captura") { _, _ ->
                    NativeBridge.nativeResetCdl()
                    NativeBridge.nativeSetCdlRecording(true)
                    NativeBridge.nativeSetSandbox(false)
                    sandboxFrozen = false
                    NativeBridge.nativeResetEmu()
                    captureBtn.text = "● Capturar: ON"
                    findViewById<android.widget.Button>(R.id.btnSandbox).text = "🧪 Sandbox: OFF"
                    Toast.makeText(this, "Jogo reiniciado, captura zerada e ligada. Jogue para capturar desde o início.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Só reiniciar o jogo") { _, _ ->
                    NativeBridge.nativeSetSandbox(false)
                    sandboxFrozen = false
                    NativeBridge.nativeResetEmu()
                    Toast.makeText(this, "Jogo reiniciado (captura mantida).", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Cancelar", null)
                .show()
        }

        val sandboxBtn = findViewById<android.widget.Button>(R.id.btnSandbox)
        sandboxBtn.setOnClickListener {
            // If currently frozen at the boundary, this tap exits the freeze
            // and turns sandbox off so the game runs normally again.
            if (sandboxFrozen) {
                NativeBridge.nativeSetSandbox(false)
                NativeBridge.nativeClearBoundary()
                sandboxFrozen = false
                sandboxBtn.text = "🧪 Sandbox: OFF"
                Toast.makeText(this, "Descongelado. ROM roda normal de novo.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val turnOn = !NativeBridge.nativeIsSandbox()
            if (turnOn) {
                // Sandbox replays within the captured set: stop recording,
                // clear any boundary, reset so it runs from the start, and
                // freeze the moment execution leaves what was captured.
                NativeBridge.nativeSetCdlRecording(false)
                NativeBridge.nativeClearBoundary()
                NativeBridge.nativeSetSandbox(true)
                NativeBridge.nativeResetEmu()
                sandboxFrozen = false
                sandboxBtn.text = "🧪 Sandbox: ON"
                findViewById<android.widget.Button>(R.id.btnCapture).text = "○ Capturar: OFF"
                Toast.makeText(
                    this,
                    "Sandbox ligado: rodando só o trecho capturado. Vai congelar quando o jogo tentar executar código que você ainda não capturou.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                NativeBridge.nativeSetSandbox(false)
                sandboxFrozen = false
                sandboxBtn.text = "🧪 Sandbox: OFF"
                Toast.makeText(this, "Sandbox desligado: ROM roda normal de novo.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openLab() {
        val rom = RomRepository.bytes
        val mapper = RomRepository.mapper()
        if (rom == null || mapper == null) {
            Toast.makeText(this, "Nenhuma ROM carregada", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Abrindo Lab…", Toast.LENGTH_SHORT).show()
        thread {
            val cdl = try { NativeBridge.nativeGetCdlMap() } catch (t: Throwable) { ByteArray(0) }
            // Discover contiguous executed-code regions from the CDL.
            val regions = ArrayList<com.diogo.snesdeco.emu.CodeRegion>()
            var i = 0
            while (i < cdl.size) {
                if ((cdl[i].toInt() and 0x03) != 0) {
                    var j = i
                    while (j < cdl.size && (cdl[j].toInt() and 0x03) != 0) j++
                    if (j - i >= 3) {
                        val (b, a) = mapper.toSnesAddress(i)
                        regions.add(com.diogo.snesdeco.emu.CodeRegion(i, j - i, b, a))
                    }
                    i = j
                } else i++
            }
            com.diogo.snesdeco.emu.LabState.regions = regions.sortedByDescending { it.length }
            runOnUiThread {
                startActivity(android.content.Intent(this, LabActivity::class.java))
            }
        }
    }

    private fun bindButton(viewId: Int, button: Int) {
        val v = findViewById<android.view.View>(viewId)
        v.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    NativeBridge.nativeSetButton(button, true)
                    view.alpha = 0.6f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    NativeBridge.nativeSetButton(button, false)
                    view.alpha = 1.0f
                }
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
