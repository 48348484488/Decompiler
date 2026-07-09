package com.diogo.snesdeco.ui

import android.content.Context
import android.graphics.Bitmap
import com.diogo.snesdeco.disasm.Disassembler
import com.diogo.snesdeco.emu.ExtractionSession
import com.diogo.snesdeco.emu.NativeBridge
import com.diogo.snesdeco.rom.RomRepository

/**
 * Writes everything gathered during a full-capture session into one
 * organized project folder under Download/SNESDeco/:
 *
 *   projeto_[titulo]_[ts]/
 *     info.txt              - ROM header + session stats
 *     codigo/cdl.bin        - raw code/data log map
 *     codigo/regioes.txt    - list of discovered executed-code regions
 *     codigo/disasm_cdl.asm - 65816 disassembly of those regions
 *     sprites/  (PNGs)      - unique assembled sprites collected
 *     paletas/  (PNGs)      - unique CGRAM palettes collected
 */
object ExtractionExporter {

    fun export(context: Context): String? {
        val info = RomRepository.info ?: return null
        val rom = RomRepository.bytes ?: return null
        val mapper = RomRepository.mapper() ?: return null
        val cdl: ByteArray = try {
            NativeBridge.nativeGetCdlMap()
        } catch (t: Throwable) {
            RomRepository.cdlMap ?: ByteArray(0)
        }

        val title = info.title.ifBlank { "rom" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        val sub = "projeto_${title}_${System.currentTimeMillis()}"

        val coded = cdl.count { (it.toInt() and 0x03) != 0 }
        writeText(context, sub, "info.txt", buildString {
            appendLine("SNES Deco — projeto de extração")
            appendLine("ROM: ${info.title} (${info.fileName})")
            appendLine("Mapeamento: ${info.mapMode.label}  Tamanho: ${info.displaySize}")
            appendLine("Chip: ${info.cartridgeTypeLabel}")
            appendLine("Vetores: RESET=$%04X  NMI=$%04X  IRQ=$%04X".format(info.resetVector, info.nmiVector, info.irqVector))
            if (cdl.isNotEmpty()) {
                appendLine("CDL: $coded/${cdl.size} bytes executados (%.2f%%)".format(coded * 100.0 / cdl.size))
            }
            appendLine("Sprites únicos capturados: ${ExtractionSession.spriteCount()}")
            appendLine("Paletas únicas capturadas: ${ExtractionSession.paletteCount()}")
            appendLine("Capturas automáticas feitas: ${ExtractionSession.captures}")
        })

        if (cdl.isNotEmpty()) {
            writeBytes(context, "$sub/codigo", "cdl.bin", cdl)

            // Discover contiguous executed regions.
            val regions = ArrayList<Pair<Int, Int>>() // (offset, length)
            var i = 0
            while (i < cdl.size) {
                if ((cdl[i].toInt() and 0x03) != 0) {
                    var j = i
                    while (j < cdl.size && (cdl[j].toInt() and 0x03) != 0) j++
                    if (j - i >= 4) regions.add(i to (j - i))
                    i = j
                } else i++
            }

            writeText(context, "$sub/codigo", "regioes.txt", buildString {
                appendLine("${regions.size} regiões de código executado (offset | bytes | endereço SNES)")
                for ((off, len) in regions) {
                    val (b, a) = mapper.toSnesAddress(off)
                    appendLine("%06X | %6d | %02X:%04X".format(off, len, b, a))
                }
            })

            // Disassemble every region into one .asm.
            val d = Disassembler(rom, mapper)
            val sb = StringBuilder()
            sb.appendLine("; SNES Deco — disassembly das regiões executadas (CDL)")
            sb.appendLine("; ROM: ${info.title}  Map: ${info.mapMode.label}")
            sb.appendLine()
            var totalLines = 0
            outer@ for ((off, len) in regions) {
                val (b, a) = mapper.toSnesAddress(off)
                sb.appendLine(";; ======== região %06X (%d bytes) — %02X:%04X ========".format(off, len, b, a))
                d.resetFlagAssumption()
                var consumed = 0
                var bank = b
                var addr = a
                while (consumed < len) {
                    val lines = d.disassembleRange(bank, addr, 128)
                    if (lines.isEmpty()) break
                    for (ln in lines) {
                        sb.appendLine(ln.raw)
                        consumed += maxOf(ln.bytes.size, 1)
                        totalLines++
                        if (totalLines >= 120000) {
                            sb.appendLine(";; (truncado em 120000 linhas)")
                            break@outer
                        }
                        if (consumed >= len) break
                    }
                    val last = lines.last()
                    bank = last.bank
                    addr = last.addr + last.bytes.size
                    if (addr > 0xFFFF) { addr -= 0x10000; bank = (bank + 1) and 0xFF }
                }
                sb.appendLine()
            }
            writeText(context, "$sub/codigo", "disasm_cdl.asm", sb.toString())
        }

        // Sprites
        var idx = 0
        for (g in ExtractionSession.spritesSnapshot()) {
            try {
                val bmp = Bitmap.createBitmap(g.widthPx, g.heightPx, Bitmap.Config.ARGB_8888)
                bmp.setPixels(g.argb, 0, g.widthPx, 0, 0, g.widthPx, g.heightPx)
                val big = Bitmap.createScaledBitmap(bmp, g.widthPx * 4, g.heightPx * 4, false)
                val (st, _) = SaveUtils.openDownloadsFile(
                    context, "$sub/sprites",
                    "sprite_%03d_%dx%d.png".format(idx, g.widthPx, g.heightPx), "image/png"
                ) ?: continue
                st.use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
                idx++
            } catch (_: Exception) { }
        }

        // Palettes
        var p = 0
        for (cg in ExtractionSession.palettesSnapshot()) {
            savePalettePng(context, "$sub/paletas", "palette_%02d.png".format(p), cg)
            p++
        }

        return "Download/SNESDeco/$sub/"
    }

    private fun writeText(context: Context, subDir: String, name: String, text: String) {
        try {
            val (st, _) = SaveUtils.openDownloadsFile(context, subDir, name, "text/plain") ?: return
            st.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) { }
    }

    private fun writeBytes(context: Context, subDir: String, name: String, bytes: ByteArray) {
        try {
            val (st, _) = SaveUtils.openDownloadsFile(context, subDir, name, "application/octet-stream") ?: return
            st.use { it.write(bytes) }
        } catch (_: Exception) { }
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
                val cx = (i % 16) * cell
                val cy = (i / 16) * cell
                for (y in 0 until cell) for (x in 0 until cell) bmp.setPixel(cx + x, cy + y, argb)
            }
            val (st, _) = SaveUtils.openDownloadsFile(context, subDir, name, "image/png") ?: return
            st.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) { }
    }
}
