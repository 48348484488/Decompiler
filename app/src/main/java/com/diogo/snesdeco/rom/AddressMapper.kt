package com.diogo.snesdeco.rom

/**
 * Converts between "SNES CPU address space" (bank:addr, e.g. $C2:8000) and the
 * plain byte offset inside the (copier-header-stripped) ROM file.
 */
class AddressMapper(private val mode: MapMode, private val romSizeBytes: Int) {

    /** Returns file offset, or -1 if the address doesn't map to ROM (e.g. RAM/registers). */
    fun toFileOffset(bank: Int, addr: Int): Int {
        val b = bank and 0xFF
        val a = addr and 0xFFFF
        return when (mode) {
            MapMode.LOROM, MapMode.EXLOROM -> {
                if (a < 0x8000) return -1 // WRAM/PPU/registers mirror, not ROM
                val bankIndex = b and 0x7F
                val off = bankIndex * 0x8000 + (a - 0x8000)
                if (off < romSizeBytes) off else -1
            }
            MapMode.HIROM -> {
                if (b in 0x00..0x3F && a < 0x8000) return -1
                val bankIndex = b and 0x3F
                val off = bankIndex * 0x10000 + a
                if (off < romSizeBytes) off else -1
            }
            MapMode.EXHIROM -> {
                // Simplified ExHiROM mapping (banks $C0-$FF low half + $40-$7D upper half).
                val off = if (b >= 0xC0) {
                    (b and 0x3F) * 0x10000 + a
                } else {
                    ((b and 0x3F) + 0x40) * 0x10000 + a
                }
                if (off in 0 until romSizeBytes) off else -1
            }
            MapMode.UNKNOWN -> -1
        }
    }

    /** Best-effort inverse: given a file offset, what bank:addr would show it (for display). */
    fun toSnesAddress(fileOffset: Int): Pair<Int, Int> {
        return when (mode) {
            MapMode.LOROM, MapMode.EXLOROM -> {
                val bank = (fileOffset / 0x8000) or 0x80
                val addr = (fileOffset % 0x8000) + 0x8000
                bank to addr
            }
            MapMode.HIROM, MapMode.EXHIROM -> {
                val bank = (fileOffset / 0x10000) or 0xC0
                val addr = fileOffset % 0x10000
                bank to addr
            }
            MapMode.UNKNOWN -> 0 to fileOffset
        }
    }
}
