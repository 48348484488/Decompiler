package com.diogo.snesdeco.ui

import android.graphics.Bitmap
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

    @Volatile private var audioReady = false
    @Volatile private var turboActive = false
    private var audioDiagShown = false

    @Volatile private var running = false
    private var loopThread: Thread? = null
    private var frameCounter = 0

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
            // Request audio focus (some OEM skins mute apps that don't hold it).
            try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN)
                // Nudge media volume up so device volume isn't the culprit.
                val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                if (am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (maxVol * 0.7).toInt().coerceAtLeast(1), 0)
                }
            } catch (_: Exception) { }

            // OpenSL ES runs audio in the native engine - more reliable than
            // AudioTrack, which reported PLAYING but stayed silent on device.
            val ok = NativeBridge.nativeAudioInit(48000)
            audioReady = ok
            if (!ok) {
                Toast.makeText(this, "Áudio: OpenSL ES falhou ao iniciar.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            audioReady = false
            Toast.makeText(this, "Áudio: exceção - ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
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

                // Push audio straight from the core into OpenSL ES. In turbo we
                // skip audio (it would sound sped-up/garbled) and run flat out.
                if (audioReady && !turboActive) {
                    NativeBridge.nativeAudioPump()
                }

                renderFrame(pixels, w, h)

                frameCounter++
                if (frameCounter % 15 == 0) {
                    updateReadouts()
                }

                // One-time definitive audio diagnostic at ~3s.
                if (!audioDiagShown && frameCounter >= 180) {
                    audioDiagShown = true
                    val peak = NativeBridge.nativeAudioPeak()
                    val totalK = NativeBridge.nativeAudioTotalK()
                    val sc = NativeBridge.nativeAudioSampleCount()
                    val muted = NativeBridge.nativeAudioIsMuted()
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "ÁUDIO: pico=$peak sc=$sc mute=$muted | ${totalK}K enviadas | init=$audioReady",
                            Toast.LENGTH_LONG
                        ).show()
                    }
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

                // Turbo: skip frame pacing to run as fast as the CPU allows.
                if (turboActive) {
                    nextFrameTime = System.nanoTime()
                    continue
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

        findViewById<android.widget.Button>(R.id.btnRestart).setOnClickListener {
            NativeBridge.nativeResetEmu()
            Toast.makeText(this, "Jogo reiniciado.", Toast.LENGTH_SHORT).show()
        }

        // Turbo: hold to fast-forward, release to return to normal speed.
        findViewById<android.widget.Button>(R.id.btnTurbo).setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { turboActive = true; view.alpha = 0.6f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { turboActive = false; view.alpha = 1.0f }
            }
            true
        }

        findViewById<android.widget.Button>(R.id.btnTools).setOnClickListener {
            showToolsMenu()
        }
    }

    private fun showToolsMenu() {
        val recording = NativeBridge.nativeIsCdlRecording()
        val options = arrayOf(
            "🔬 Lab — código capturado (disasm + editar/testar)",
            "🎨 Ripar sprites da cena atual",
            "🧠 Explorador de RAM (buscar valores)",
            if (recording) "⏸ Pausar captura de código" else "● Retomar captura de código",
            "🗑 Zerar captura de código"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Ferramentas")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openLab()
                    1 -> ripSprites()
                    2 -> startActivity(android.content.Intent(this, RamExplorerActivity::class.java))
                    3 -> {
                        val turnOn = !NativeBridge.nativeIsCdlRecording()
                        NativeBridge.nativeSetCdlRecording(turnOn)
                        Toast.makeText(this, if (turnOn) "Captura retomada." else "Captura pausada.", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        NativeBridge.nativeResetCdl()
                        Toast.makeText(this, "Captura de código zerada.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun ripSprites() {
        try {
            val oam = NativeBridge.nativeGetOam()
            val vram = NativeBridge.nativeGetVram()
            val cgram = NativeBridge.nativeGetCgram()
            val nameBase = NativeBridge.nativeGetObjNameBase()
            val nameSelect = NativeBridge.nativeGetObjNameSelect()
            val sizeSelect = NativeBridge.nativeGetObjSizeSelect()
            Toast.makeText(this, "Capturando sprites…", Toast.LENGTH_SHORT).show()
            thread {
                val groups = SpriteRipper.ripGroups(oam, vram, cgram, nameBase, nameSelect, sizeSelect)
                runOnUiThread {
                    if (groups.isEmpty()) {
                        Toast.makeText(this, "Nenhum sprite visível na cena agora.", Toast.LENGTH_SHORT).show()
                    } else {
                        com.diogo.snesdeco.emu.SpriteCapture.groups = groups
                        com.diogo.snesdeco.emu.SpriteCapture.cgram = cgram
                        startActivity(android.content.Intent(this, SpriteViewerActivity::class.java))
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao capturar sprites: ${e.message}", Toast.LENGTH_LONG).show()
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
        if (audioReady) {
            NativeBridge.nativeAudioShutdown()
            audioReady = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (NativeBridge.nativeIsRomLoaded() && !running) {
            setupAudio()
            startLoop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        loopThread?.join(200)
        if (audioReady) {
            NativeBridge.nativeAudioShutdown()
            audioReady = false
        }
    }
}
