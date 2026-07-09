package com.diogo.snesdeco.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.diogo.snesdeco.R
import com.diogo.snesdeco.emu.NativeBridge
import com.diogo.snesdeco.emu.SpriteCapture
import com.diogo.snesdeco.emu.SpriteRipper
import com.diogo.snesdeco.rom.RomRepository
import java.io.File
import java.io.OutputStream
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
    private var pendingSaveAction: (() -> Unit)? = null

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingSaveAction?.invoke()
        } else {
            Toast.makeText(this, "Sem permissão de armazenamento, não dá pra salvar.", Toast.LENGTH_LONG).show()
        }
        pendingSaveAction = null
    }

    /** Runs [action] now if we can write to Downloads already, otherwise asks first. */
    private fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            action() // MediaStore Downloads needs no runtime permission on API 29+
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingSaveAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Opens an OutputStream for a new file under Download/SNESDeco/[subDir]/[fileName]. */
    private fun openDownloadsFile(subDir: String, fileName: String, mimeType: String): Pair<OutputStream, String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/SNESDeco/$subDir"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = contentResolver.openOutputStream(uri) ?: return null
            stream to "Download/SNESDeco/$subDir/$fileName"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SNESDeco/$subDir")
            dir.mkdirs()
            val f = File(dir, fileName)
            java.io.FileOutputStream(f) to "Download/SNESDeco/$subDir/$fileName"
        }
    }

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
                NativeBridge.nativeRunFrame()

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

        val recBtn = findViewById<android.widget.Button>(R.id.btnRec)
        recBtn.setOnClickListener {
            val nowRecording = !NativeBridge.nativeIsCdlRecording()
            NativeBridge.nativeSetCdlRecording(nowRecording)
            recBtn.text = if (nowRecording) "● REC" else "▶ parar/salvar"
            if (!nowRecording) {
                // Stopped: persist the accumulated CDL map to a file.
                saveCdl()
            } else {
                Toast.makeText(this, "Gravando código executado (CDL)…", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<android.widget.Button>(R.id.btnRip).setOnClickListener {
            ripSprites()
        }

        findViewById<android.widget.Button>(R.id.btnExport).setOnClickListener {
            Toast.makeText(this, "Exportando projeto…", Toast.LENGTH_SHORT).show()
            thread {
                val path = try { ExtractionExporter.export(this) } catch (e: Exception) { null }
                runOnUiThread {
                    if (path != null) {
                        Toast.makeText(this, "Projeto exportado em:\n$path", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Erro ao exportar (ROM carregada?)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun saveCdl() {
        withStoragePermission {
            try {
                val cdl = NativeBridge.nativeGetCdlMap()
                val fileName = "cdl_${System.currentTimeMillis()}.bin"
                val (stream, displayPath) = openDownloadsFile("cdl", fileName, "application/octet-stream")
                    ?: throw java.io.IOException("não foi possível criar o arquivo")
                stream.use { it.write(cdl) }
                val coded = cdl.count { (it.toInt() and 0x03) != 0 }
                Toast.makeText(
                    this,
                    "CDL salvo em $displayPath\n$coded bytes de código mapeados",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao salvar CDL: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun ripSprites() {
        try {
            val oam = NativeBridge.nativeGetOam()
            val vram = NativeBridge.nativeGetVram()
            val cgram = NativeBridge.nativeGetCgram()
            val nameBase = NativeBridge.nativeGetObjNameBase()
            val nameSelect = NativeBridge.nativeGetObjNameSelect()
            val sizeSelect = NativeBridge.nativeGetObjSizeSelect()

            thread {
                val groups = SpriteRipper.ripGroups(oam, vram, cgram, nameBase, nameSelect, sizeSelect)
                runOnUiThread {
                    if (groups.isEmpty()) {
                        Toast.makeText(this, "Nenhum sprite visível na cena agora.", Toast.LENGTH_SHORT).show()
                    } else {
                        SpriteCapture.groups = groups
                        SpriteCapture.cgram = cgram
                        startActivity(android.content.Intent(this, SpriteViewerActivity::class.java))
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao capturar sprites: ${e.message}", Toast.LENGTH_LONG).show()
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
