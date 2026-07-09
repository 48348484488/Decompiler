package com.diogo.snesdeco.emu

/**
 * Holds the last sprite capture so SpriteViewerActivity can read it
 * (bitmap-sized data doesn't fit through Intents).
 */
object SpriteCapture {
    var groups: List<SpriteRipper.SpriteGroup> = emptyList()
    var cgram: ByteArray? = null
}

/**
 * Reassembles SNES sprites (OBJ) from a snapshot of OAM + VRAM + CGRAM.
 *
 * A game character is normally built from SEVERAL hardware sprites placed
 * side by side (each OAM entry is one 8x8..64x64 piece). Ripping entries
 * individually yields puzzle pieces - so after decoding each piece at its
 * on-screen position, we GROUP pieces whose bounding boxes touch/overlap
 * and composite each group into one image: the whole character, exactly
 * as assembled on screen.
 */
object SpriteRipper {

    /** One assembled on-screen sprite (a group of adjacent OAM pieces). */
    data class SpriteGroup(
        val id: Int,
        val screenX: Int,
        val screenY: Int,
        val widthPx: Int,
        val heightPx: Int,
        val memberCount: Int,
        val argb: IntArray // widthPx*heightPx row-major, 0 = transparent
    )

    private class RawSprite(
        val index: Int,
        val sx: Int,       // signed screen X (-256..255)
        val sy: Int,       // signed screen Y after wrap handling
        val w: Int,
        val h: Int,
        val pixels: IntArray
    )

    // OBSEL size pairs (small, large) per hardware spec; entries 6-7 are rectangular.
    private val sizePairs = arrayOf(
        Pair(Pair(8, 8), Pair(16, 16)),
        Pair(Pair(8, 8), Pair(32, 32)),
        Pair(Pair(8, 8), Pair(64, 64)),
        Pair(Pair(16, 16), Pair(32, 32)),
        Pair(Pair(16, 16), Pair(64, 64)),
        Pair(Pair(32, 32), Pair(64, 64)),
        Pair(Pair(16, 32), Pair(32, 64)),
        Pair(Pair(16, 32), Pair(32, 32))
    )

    fun ripGroups(
        oam: IntArray,
        vram: ByteArray,
        cgram: ByteArray,
        objNameBase: Int,
        objNameSelect: Int,
        objSizeSelect: Int
    ): List<SpriteGroup> {
        val palette = decodeCgram(cgram)
        val raws = ArrayList<RawSprite>()
        val fields = 8

        for (i in 0 until 128) {
            val base = i * fields
            val hpos = oam[base + 0]           // already sign-extended int16 by snes9x
            val vposRaw = oam[base + 1] and 0xFF
            val name = oam[base + 2]
            val pal = oam[base + 3]
            val hflip = oam[base + 5] != 0
            val vflip = oam[base + 6] != 0
            val sizeBit = oam[base + 7]

            val (small, large) = sizePairs[objSizeSelect and 7]
            val (w, h) = if (sizeBit == 0) small else large

            // SNES Y wrap: values near 255 appear partially at the top.
            var sy = vposRaw
            if (sy > 224) sy -= 256

            // Off-screen cull
            if (hpos >= 256 || hpos + w <= 0 || sy >= 224 || sy + h <= 0) continue

            val pixels = decodeSpritePixels(vram, palette, name, pal, hflip, vflip, w, h, objNameBase, objNameSelect)
            if (pixels.none { (it ushr 24) != 0 }) continue // fully transparent

            raws.add(RawSprite(i, hpos, sy, w, h, pixels))
        }

        if (raws.isEmpty()) return emptyList()

        // Union-find: join pieces whose (slightly expanded) boxes touch.
        val parent = IntArray(raws.size) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        val expand = 2
        for (a in raws.indices) for (b in a + 1 until raws.size) {
            val s = raws[a]; val t = raws[b]
            val touch = s.sx - expand < t.sx + t.w && t.sx - expand < s.sx + s.w &&
                        s.sy - expand < t.sy + t.h && t.sy - expand < s.sy + s.h
            if (touch) union(a, b)
        }

        val byRoot = HashMap<Int, MutableList<RawSprite>>()
        for (idx in raws.indices) byRoot.getOrPut(find(idx)) { ArrayList() }.add(raws[idx])

        val groups = ArrayList<SpriteGroup>()
        var gid = 0
        for (members in byRoot.values) {
            val minX = members.minOf { it.sx }
            val minY = members.minOf { it.sy }
            val maxX = members.maxOf { it.sx + it.w }
            val maxY = members.maxOf { it.sy + it.h }
            val gw = maxX - minX
            val gh = maxY - minY
            if (gw <= 0 || gh <= 0 || gw > 512 || gh > 512) continue

            val canvas = IntArray(gw * gh)
            // Draw high index first so LOWER OAM index ends on top (SNES OBJ priority).
            for (m in members.sortedByDescending { it.index }) {
                val ox = m.sx - minX
                val oy = m.sy - minY
                for (y in 0 until m.h) for (x in 0 until m.w) {
                    val c = m.pixels[y * m.w + x]
                    if ((c ushr 24) != 0) canvas[(oy + y) * gw + (ox + x)] = c
                }
            }
            groups.add(SpriteGroup(gid++, minX, minY, gw, gh, members.size, canvas))
        }

        return groups.sortedByDescending { it.widthPx * it.heightPx }
    }

    private fun decodeSpritePixels(
        vram: ByteArray, palette: IntArray,
        name: Int, pal: Int, hflip: Boolean, vflip: Boolean,
        w: Int, h: Int, objNameBase: Int, objNameSelect: Int
    ): IntArray {
        val out = IntArray(w * h)
        val palBase = 128 + (pal and 7) * 16
        val tilesW = w / 8
        val tilesH = h / 8

        for (ty in 0 until tilesH) {
            for (tx in 0 until tilesW) {
                val tileNum = (name + tx + ty * 0x10) and 0x1FF
                val pageOffset = if (tileNum >= 0x100) (objNameSelect + 1) * 0x1000 else 0
                val tileWordAddr = (objNameBase + pageOffset + (tileNum and 0xFF) * 16) and 0x7FFF
                val tileByteAddr = tileWordAddr * 2

                for (row in 0 until 8) {
                    val p0 = byteAt(vram, tileByteAddr + row * 2)
                    val p1 = byteAt(vram, tileByteAddr + row * 2 + 1)
                    val p2 = byteAt(vram, tileByteAddr + 16 + row * 2)
                    val p3 = byteAt(vram, tileByteAddr + 16 + row * 2 + 1)
                    for (col in 0 until 8) {
                        val bit = 7 - col
                        val idx = ((p0 shr bit) and 1) or
                                (((p1 shr bit) and 1) shl 1) or
                                (((p2 shr bit) and 1) shl 2) or
                                (((p3 shr bit) and 1) shl 3)
                        if (idx == 0) continue
                        var px = tx * 8 + col
                        var py = ty * 8 + row
                        if (hflip) px = w - 1 - px
                        if (vflip) py = h - 1 - py
                        out[py * w + px] = palette[palBase + idx]
                    }
                }
            }
        }
        return out
    }

    private fun decodeCgram(cgram: ByteArray): IntArray {
        val out = IntArray(256)
        for (i in 0 until 256) {
            val lo = cgram[i * 2].toInt() and 0xFF
            val hi = cgram[i * 2 + 1].toInt() and 0xFF
            val word = lo or (hi shl 8)
            val r = ((word and 0x1F) * 255) / 31
            val g = (((word shr 5) and 0x1F) * 255) / 31
            val b = (((word shr 10) and 0x1F) * 255) / 31
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun byteAt(a: ByteArray, i: Int): Int {
        if (i < 0 || i >= a.size) return 0
        return a[i].toInt() and 0xFF
    }
}
