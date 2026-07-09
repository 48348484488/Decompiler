package com.diogo.snesdeco.gfx

/**
 * SNES CGRAM colors are 15-bit BGR555: bit15 unused, bits10-14 = B,
 * bits5-9 = G, bits0-4 = R, little-endian 16-bit words in ROM.
 */
object PaletteDecoder {

    /** Decodes [wordCount] consecutive BGR555 words starting at [offset] into ARGB ints. */
    fun decodePalette(rom: ByteArray, offset: Int, wordCount: Int): IntArray {
        val out = IntArray(wordCount)
        for (i in 0 until wordCount) {
            val lo = offset + i * 2
            if (lo + 1 >= rom.size) { out[i] = 0xFF000000.toInt(); continue }
            val word = (rom[lo].toInt() and 0xFF) or ((rom[lo + 1].toInt() and 0xFF) shl 8)
            out[i] = bgr555ToArgb(word)
        }
        return out
    }

    fun bgr555ToArgb(word: Int): Int {
        val r5 = word and 0x1F
        val g5 = (word shr 5) and 0x1F
        val b5 = (word shr 10) and 0x1F
        val r8 = (r5 * 255) / 31
        val g8 = (g5 * 255) / 31
        val b8 = (b5 * 255) / 31
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }
}
