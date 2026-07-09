package com.diogo.snesdeco.emu

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-capture ("Modo Extração") session state. When enabled BEFORE loading a
 * ROM, the emulator auto-captures during play: the CDL records every executed
 * instruction (already on from frame 1), and every few seconds the current
 * assembled sprites + CGRAM palette are snapshotted and deduplicated here.
 * ExtractionExporter then writes everything as one organized project folder.
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
