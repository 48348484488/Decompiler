package com.diogo.snesdeco.emu

/**
 * Reassembles SNES sprites (OBJ) from a snapshot of OAM + VRAM + CGRAM.
 *
 * On the SNES, a hardware sprite is defined by an OAM entry (position, a base
 * tile "Name", palette index, flip flags, and a size bit). The actual pixels
 * live in VRAM as 4bpp planar tiles, and the colors in CGRAM. Larger sprites
 * (16x16, 32x32, 64x64) are laid out as a grid of 8x8 tiles whose Names
 * increase by 1 across a row and by 0x10 down a row (the standard SNES OBJ
 * tile-numbering scheme). This class does that reassembly - i.e. it performs
 * the same "puzzle assembly" the PPU does, so we recover finished sprites.
 */
object SpriteRipper {

    data class Sprite(
        val index: Int,
        val x: Int,
        val y: Int,
        val widthPx: Int,
        val heightPx: Int,
        val paletteIndex: Int,
        val argb: IntArray // widthPx*heightPx, row-major; 0 = transparent
    )

    // OAM Size bit -> (small size, large size) selected by OBJSizeSelect.
    // We approximate using the common size table; the size bit picks which of
    // the pair applies. Result is the sprite's pixel dimensions.
    private fun spriteDimensions(sizeBit: Int, objSizeSelect: Int): Pair<Int, Int> {
        // objSizeSelect chooses one of 8 hardware size pairs. Table of
        // (small, large) edge lengths in pixels.
        val pairs = arrayOf(
            8 to 16, 8 to 32, 8 to 64,
            16 to 32, 16 to 64, 32 to 64,
            16 to 32, 16 to 32
        )
        val (small, large) = pairs[objSizeSelect and 7]
        val edge = if (sizeBit == 0) small else large
        return edge to edge
    }

    /**
     * @param oam flat OAM array from nativeGetOam (8 ints per sprite)
     * @param vram 64KB VRAM snapshot
     * @param cgram 512-byte CGRAM snapshot (BGR555)
     * @param objNameBase base OBJ tile word-address (from nativeGetObjNameBase)
     * @param objNameSelect name-select offset (from nativeGetObjNameSelect)
     */
    fun rip(
        oam: IntArray,
        vram: ByteArray,
        cgram: ByteArray,
        objNameBase: Int,
        objNameSelect: Int,
        objSizeSelect: Int
    ): List<Sprite> {
        val palette = decodeCgramSpritePalettes(cgram)
        val sprites = ArrayList<Sprite>()
        val fields = 8

        for (i in 0 until 128) {
            val base = i * fields
            val hpos = oam[base + 0]
            val vpos = oam[base + 1]
            val name = oam[base + 2]
            val pal = oam[base + 3]
            val hflip = oam[base + 5] != 0
            val vflip = oam[base + 6] != 0
            val sizeBit = oam[base + 7]

            val (w, h) = spriteDimensions(sizeBit, objSizeSelect)
            val tilesW = w / 8
            val tilesH = h / 8

            // Skip sprites parked fully offscreen at the classic hide position.
            if (vpos >= 224 && vpos != 0) {
                // still include; some games park sprites just below - keep it
                // simple and include all, the UI can filter.
            }

            val out = IntArray(w * h)
            // Sprite palettes occupy CGRAM entries 128..255, 16 colors each.
            val palBase = 128 + pal * 16

            for (ty in 0 until tilesH) {
                for (tx in 0 until tilesW) {
                    // Tile numbering: +1 across, +0x10 down (wraps in 8-bit name space).
                    val tileNum = (name + tx + ty * 0x10) and 0x1FF
                    // OBJ tiles live in two 4KB-word pages; names >= 0x100 use
                    // the second page, located via OBJNameSelect.
                    val pageOffset = if (tileNum >= 0x100) (objNameSelect + 1) * 0x1000 else 0
                    val tileWordAddr = (objNameBase + pageOffset + (tileNum and 0xFF) * 16) and 0x7FFF
                    val tileByteAddr = tileWordAddr * 2
                    decodeTileInto(
                        out, w, h,
                        srcVram = vram, tileByteAddr = tileByteAddr,
                        dstX = tx * 8, dstY = ty * 8,
                        palette = palette, palBase = palBase,
                        hflip = hflip, vflip = vflip,
                        tilesW = tilesW, tilesH = tilesH, tx = tx, ty = ty
                    )
                }
            }

            // Only keep sprites that actually have visible pixels.
            if (out.any { (it ushr 24) != 0 }) {
                sprites.add(Sprite(i, hpos, vpos, w, h, pal, out))
            }
        }
        return sprites
    }

    private fun decodeTileInto(
        out: IntArray, spriteW: Int, spriteH: Int,
        srcVram: ByteArray, tileByteAddr: Int,
        dstX: Int, dstY: Int,
        palette: IntArray, palBase: Int,
        hflip: Boolean, vflip: Boolean,
        tilesW: Int, tilesH: Int, tx: Int, ty: Int
    ) {
        // 4bpp planar: 32 bytes/tile. Planes 0-1 interleaved in first 16 bytes,
        // planes 2-3 in next 16 bytes; 2 bytes per row per plane-pair.
        for (row in 0 until 8) {
            val p0 = byteAt(srcVram, tileByteAddr + row * 2)
            val p1 = byteAt(srcVram, tileByteAddr + row * 2 + 1)
            val p2 = byteAt(srcVram, tileByteAddr + 16 + row * 2)
            val p3 = byteAt(srcVram, tileByteAddr + 16 + row * 2 + 1)
            for (col in 0 until 8) {
                val bit = 7 - col
                val idx = ((p0 shr bit) and 1) or
                        (((p1 shr bit) and 1) shl 1) or
                        (((p2 shr bit) and 1) shl 2) or
                        (((p3 shr bit) and 1) shl 3)
                val argb = if (idx == 0) 0 else palette[palBase + idx]

                // Position within the sprite, honoring flips across the whole sprite.
                var px = dstX + col
                var py = dstY + row
                if (hflip) px = spriteW - 1 - px
                if (vflip) py = spriteH - 1 - py
                if (px in 0 until spriteW && py in 0 until spriteH) {
                    out[py * spriteW + px] = argb
                }
            }
        }
    }

    private fun decodeCgramSpritePalettes(cgram: ByteArray): IntArray {
        val out = IntArray(256)
        for (i in 0 until 256) {
            val lo = cgram[i * 2].toInt() and 0xFF
            val hi = cgram[i * 2 + 1].toInt() and 0xFF
            val word = lo or (hi shl 8)
            out[i] = bgr555ToArgb(word)
        }
        return out
    }

    private fun bgr555ToArgb(word: Int): Int {
        val r5 = word and 0x1F
        val g5 = (word shr 5) and 0x1F
        val b5 = (word shr 10) and 0x1F
        val r8 = (r5 * 255) / 31
        val g8 = (g5 * 255) / 31
        val b8 = (b5 * 255) / 31
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }

    private fun byteAt(a: ByteArray, i: Int): Int {
        if (i < 0 || i >= a.size) return 0
        return a[i].toInt() and 0xFF
    }
}
