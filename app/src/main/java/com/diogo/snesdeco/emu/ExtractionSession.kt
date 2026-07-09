package com.diogo.snesdeco.emu

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internal workspace ("Lab") store. When capture is enabled BEFORE loading a
 * ROM, the emulator auto-collects during play: the CDL records every executed
 * instruction, and every few seconds the current assembled sprites + CGRAM
 * palette are snapshotted and deduplicated here. Nothing is exported to files;
 * this all lives inside the app so the user can browse, edit ASM, and test
 * patches live. LabState below holds the last captured CDL for the Lab screen.
 */
object ExtractionSession {
    @Volatile var enabled = false
    val busy = AtomicBoolean(false)
    @Volatile var captures = 0

    private val spriteMap = LinkedHashMap<Int, SpriteRipper.SpriteGroup>()
    private val paletteMap = LinkedHashMap<Int, ByteArray>()

    private const val MAX_SPRITES = 400
    private const val MAX_PALETTES = 64

    fun reset() {
        synchronized(spriteMap) { spriteMap.clear() }
        synchronized(paletteMap) { paletteMap.clear() }
        captures = 0
    }

    fun spriteCount(): Int = synchronized(spriteMap) { spriteMap.size }
    fun paletteCount(): Int = synchronized(paletteMap) { paletteMap.size }

    fun spritesSnapshot(): List<SpriteRipper.SpriteGroup> =
        synchronized(spriteMap) { spriteMap.values.toList() }

    fun palettesSnapshot(): List<ByteArray> =
        synchronized(paletteMap) { paletteMap.values.toList() }

    fun addSprites(groups: List<SpriteRipper.SpriteGroup>) {
        synchronized(spriteMap) {
            for (g in groups) {
                if (spriteMap.size >= MAX_SPRITES) return
                val h = g.widthPx * 31 + g.heightPx * 131 + g.argb.contentHashCode()
                if (!spriteMap.containsKey(h)) spriteMap[h] = g
            }
        }
    }

    fun addPalette(cgram: ByteArray) {
        synchronized(paletteMap) {
            if (paletteMap.size >= MAX_PALETTES) return
            val h = cgram.contentHashCode()
            if (!paletteMap.containsKey(h)) paletteMap[h] = cgram.copyOf()
        }
    }
}

/** A discovered contiguous run of executed code in the ROM. */
data class CodeRegion(val offset: Int, val length: Int, val bank: Int, val addr: Int)

/** Holds the CDL-derived code regions for the Lab, computed on demand. */
object LabState {
    @Volatile var regions: List<CodeRegion> = emptyList()
}
