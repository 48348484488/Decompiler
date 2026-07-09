package com.diogo.snesdeco.gfx

/**
 * Decodes SNES planar tile graphics. Tiles are always 8x8 pixels; the byte
 * size per tile depends on color depth: 2bpp = 16 bytes, 4bpp = 32 bytes,
 * 8bpp = 64 bytes. Bitplanes are stored in interleaved pairs, 2 bytes per
 * row per plane-pair (this is the standard SNES/SFC tile format, same as
 * used by Mode 0-6 backgrounds and sprites; Mode 7 uses a different,
 * non-planar 8bpp-linear format and isn't handled here).
 */
object TileDecoder {

    fun bytesPerTile(bpp: Int): Int = when (bpp) {
        2 -> 16
        4 -> 32
        8 -> 64
        else -> 32
    }

    /** Returns an 8x8 array (row-major, 64 entries) of palette indices (0..2^bpp-1). */
    fun decodeTile(rom: ByteArray, offset: Int, bpp: Int): IntArray {
        val indices = IntArray(64)
        val planePairs = bpp / 2
        for (row in 0 until 8) {
            var rowBits = IntArray(8) // accumulate per-pixel index bits
            for (pairIdx in 0 until planePairs) {
                val pairBase = offset + pairIdx * 16 + row * 2
                val planeLoByte = safeByte(rom, pairBase)
                val planeHiByte = safeByte(rom, pairBase + 1)
                for (px in 0 until 8) {
                    val bit = 7 - px
                    val loBit = (planeLoByte shr bit) and 1
                    val hiBit = (planeHiByte shr bit) and 1
                    rowBits[px] = rowBits[px] or (loBit shl (pairIdx * 2)) or (hiBit shl (pairIdx * 2 + 1))
                }
            }
            for (px in 0 until 8) {
                indices[row * 8 + px] = rowBits[px]
            }
        }
        return indices
    }

    /** Decodes [count] consecutive tiles and lays them out in a grid, returning ARGB pixels for a [colsxrows*8]x[...] bitmap. */
    fun decodeTileSheet(rom: ByteArray, romOffset: Int, bpp: Int, count: Int, cols: Int, palette: IntArray): IntArray {
        val tileBytes = bytesPerTile(bpp)
        val rows = (count + cols - 1) / cols
        val widthPx = cols * 8
        val heightPx = rows * 8
        val out = IntArray(widthPx * heightPx)
        for (t in 0 until count) {
            val tileOffset = romOffset + t * tileBytes
            val idxArr = decodeTile(rom, tileOffset, bpp)
            val tileCol = t % cols
            val tileRow = t / cols
            for (py in 0 until 8) {
                for (px in 0 until 8) {
                    val colorIndex = idxArr[py * 8 + px]
                    val argb = if (colorIndex == 0) 0x00000000 else palette.getOrElse(colorIndex) { 0xFFFF00FF.toInt() }
                    val outX = tileCol * 8 + px
                    val outY = tileRow * 8 + py
                    out[outY * widthPx + outX] = argb
                }
            }
        }
        return out
    }

    private fun safeByte(rom: ByteArray, index: Int): Int {
        if (index < 0 || index >= rom.size) return 0
        return rom[index].toInt() and 0xFF
    }
}
