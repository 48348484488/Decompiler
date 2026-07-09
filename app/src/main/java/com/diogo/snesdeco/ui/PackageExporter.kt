package com.diogo.snesdeco.ui

import android.content.Context
import android.graphics.Bitmap
import com.diogo.snesdeco.disasm.Disassembler
import com.diogo.snesdeco.emu.ExtractionSession
import com.diogo.snesdeco.emu.LabState
import com.diogo.snesdeco.emu.NativeBridge
import com.diogo.snesdeco.rom.RomRepository

/**
 * Exports the CAPTURED SLICE (what actually executed) as an organized study
 * package under Download/SNESDeco/. This is explicitly not a playable game -
 * it's the disassembly of the executed regions + the assembled sprites +
 * palettes gathered during play, for studying/porting.
 */
object PackageExporter {

    fun export(context: Context): String? {
        val info = RomRepository.info ?: return null
        val rom = RomRepository.bytes ?: return null
        val mapper = RomRepository.mapper() ?: return null
        val cdl = try { NativeBridge.nativeGetCdlMap() } catch (t: Throwable) { ByteArray(0) }

        val title = info.title.ifBlank { "rom" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        val sub = "pacote_${title}_${System.currentTimeMillis()}"
        val coded = cdl.count { (it.toInt() and 0x03) != 0 }
        val pct = if (cdl.isNotEmpty()) coded * 100.0 / cdl.size else 0.0

        writeText(context, sub, "LEIA-ME.txt", buildString {
            appendLine("SNES Deco — pacote do trecho capturado")
            appendLine("========================================")
            appendLine("ROM: ${info.title} (${info.fileName})")
            appendLine("Mapeamento: ${info.mapMode.label}  Tamanho: ${info.displaySize}")
            appendLine()
            appendLine("IMPORTANTE: este pacote contém apenas o que EXECUTOU durante")
            appendLine("o jogo (%.2f%% da ROM). É material de estudo do trecho, não um".format(pct))
            appendLine("jogo completo. O jogo inteiro é o próprio arquivo .sfc original.")
            appendLine()
            appendLine("Conteúdo:")
            appendLine("  codigo/regioes.txt   - regiões de código que rodaram")
            appendLine("  codigo/trecho.asm    - disassembly 65816 dessas regiões")
            appendLine("  sprites/             - sprites montados capturados (PNG)")
            appendLine("  paletas/             - paletas CGRAM capturadas (PNG)")
            appendLine()
            appendLine("Estatísticas:")
            appendLine("  Código capturado: $coded bytes (%.2f%%)".format(pct))
            appendLine("  Sprites únicos: ${ExtractionSession.spriteCount()}")
            appendLine("  Paletas únicas: ${ExtractionSession.paletteCount()}")
        })

        val regions = LabState.regions
        if (regions.isNotEmpty()) {
            writeText(context, "$sub/codigo", "regioes.txt", buildString {
                appendLine("${regions.size} regiões de código executado")
                appendLine("offset  | bytes  | endereço SNES")
                for (r in regions) appendLine("%06X | %6d | %02X:%04X".format(r.offset, r.length, r.bank, r.addr))
            })

            val d = Disassembler(rom, mapper)
            val sb = StringBuilder()
            sb.appendLine("; SNES Deco — trecho executado (${info.title})")
            sb.appendLine("; ${regions.size} regiões · $coded bytes · Map ${info.mapMode.label}")
            sb.appendLine()
            var lineCount = 0
            outer@ for (r in regions) {
                sb.appendLine(";; ==== %02X:%04X  (%d bytes, offset %06X) ====".format(r.bank, r.addr, r.length, r.offset))
                d.resetFlagAssumption()
                var consumed = 0; var bank = r.bank; var addr = r.addr
                while (consumed < r.length) {
                    val batch = d.disassembleRange(bank, addr, 128)
                    if (batch.isEmpty()) break
                    for (ln in batch) {
                        sb.appendLine(ln.raw)
                        consumed += maxOf(ln.bytes.size, 1)
                        if (++lineCount >= 150000) { sb.appendLine(";; (truncado)"); break@outer }
                        if (consumed >= r.length) break
                    }
                    val last = batch.last()
                    bank = last.bank; addr = last.addr + last.bytes.size
                    if (addr > 0xFFFF) { addr -= 0x10000; bank = (bank + 1) and 0xFF }
                }
                sb.appendLine()
            }
            writeText(context, "$sub/codigo", "trecho.asm", sb.toString())
        }

        var si = 0
        for (g in ExtractionSession.spritesSnapshot()) {
            try {
                val bmp = Bitmap.createBitmap(g.widthPx, g.heightPx, Bitmap.Config.ARGB_8888)
                bmp.setPixels(g.argb, 0, g.widthPx, 0, 0, g.widthPx, g.heightPx)
                val big = Bitmap.createScaledBitmap(bmp, g.widthPx * 4, g.heightPx * 4, false)
                val (st, _) = SaveUtils.openDownloadsFile(
                    context, "$sub/sprites", "sprite_%03d_%dx%d.png".format(si, g.widthPx, g.heightPx), "image/png"
                ) ?: continue
                st.use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
                si++
            } catch (_: Exception) {}
        }

        var pi = 0
        for (cg in ExtractionSession.palettesSnapshot()) {
            savePalettePng(context, "$sub/paletas", "palette_%02d.png".format(pi), cg); pi++
        }

        return "Download/SNESDeco/$sub/"
    }

    private fun writeText(context: Context, subDir: String, name: String, text: String) {
        try {
            val (st, _) = SaveUtils.openDownloadsFile(context, subDir, name, "text/plain") ?: return
            st.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {}
    }

    private fun savePalettePng(context: Context, subDir: String, name: String, cgram: ByteArray) {
        try {
            val cell = 16
            val bmp = Bitmap.createBitmap(16 * cell, 16 * cell, Bitmap.Config.ARGB_8888)
            for (i in 0 until 256) {
                val lo = cgram[i * 2].toInt() and 0xFF
                val hi = cgram[i * 2 + 1].toInt() and 0xFF
                val word = lo or (hi shl 8)
                val r = ((word and 0x1F) * 255) / 31
                val g = (((word shr 5) and 0x1F) * 255) / 31
                val b = (((word shr 10) and 0x1F) * 255) / 31
                val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                val cx = (i % 16) * cell; val cy = (i / 16) * cell
                for (y in 0 until cell) for (x in 0 until cell) bmp.setPixel(cx + x, cy + y, argb)
            }
            val (st, _) = SaveUtils.openDownloadsFile(context, subDir, name, "image/png") ?: return
            st.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {}
    }
}
