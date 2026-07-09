package com.diogo.snesdeco.rom

/**
 * Parses a raw .sfc/.smc byte array into a RomInfo + gives access to the
 * "clean" ROM bytes (copier header stripped) for the disassembler / gfx viewers.
 *
 * Detection is heuristic (same approach most homebrew SNES tools use): we score
 * both the LoROM ($7FC0) and HiROM ($FFC0) header candidate locations and pick
 * whichever looks more like a real header (valid checksum complement, sane
 * ROM size byte, printable title).
 */
object RomLoader {

    data class LoadResult(val info: RomInfo, val cleanBytes: ByteArray)

    fun load(fileName: String, rawBytes: ByteArray): LoadResult {
        val hasCopierHeader = (rawBytes.size % 0x8000) == 512
        val clean = if (hasCopierHeader) rawBytes.copyOfRange(512, rawBytes.size) else rawBytes

        val loRomOffset = 0x7FC0
        val hiRomOffset = 0xFFC0

        val loScore = scoreHeader(clean, loRomOffset, MapMode.LOROM)
        val hiScore = scoreHeader(clean, hiRomOffset, MapMode.HIROM)

        val (chosenOffset, chosenMode, baseScore) = if (hiScore.first >= loScore.first) {
            Triple(hiRomOffset, MapMode.HIROM, hiScore)
        } else {
            Triple(loRomOffset, MapMode.LOROM, loScore)
        }

        val info = parseHeaderAt(fileName, clean, chosenOffset, chosenMode, hasCopierHeader)
        return LoadResult(info, clean)
    }

    /** Returns (score, mapByte) — higher score = more plausible header. */
    private fun scoreHeader(bytes: ByteArray, offset: Int, mode: MapMode): Pair<Int, Int> {
        if (offset + 0x20 > bytes.size) return -1 to 0
        var score = 0

        val mapByte = u8(bytes, offset + 0x15)
        val lowNibble = mapByte and 0x0F
        val expectedNibble = if (mode == MapMode.LOROM) 0x0 else 0x1
        if (lowNibble == expectedNibble) score += 3

        val romSizeByte = u8(bytes, offset + 0x17)
        if (romSizeByte in 5..14) score += 2

        val checksum = u16(bytes, offset + 0x1E)
        val complement = u16(bytes, offset + 0x1C)
        if ((checksum xor complement) == 0xFFFF) score += 6

        val title = extractTitle(bytes, offset)
        val printable = title.count { it.code in 0x20..0x7E }
        if (title.isNotBlank() && printable >= title.length - 2) score += 2

        val resetVectorOffset = offset + 0x3C // header_base + $3C == $FFFC / $7FFC (RESET vector)
        if (resetVectorOffset + 1 < bytes.size) {
            val reset = u16(bytes, resetVectorOffset)
            // Reset vector should point into bank 0 code space somewhere sane.
            if (reset in 0x8000..0xFFFF) score += 1
        }

        return score to mapByte
    }

    private fun extractTitle(bytes: ByteArray, offset: Int): String {
        val end = minOf(offset + 21, bytes.size)
        if (offset >= bytes.size) return ""
        val slice = bytes.copyOfRange(offset, end)
        return String(slice, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
    }

    private fun parseHeaderAt(
        fileName: String,
        bytes: ByteArray,
        offset: Int,
        mode: MapMode,
        copierHeaderPresent: Boolean
    ): RomInfo {
        val mapByte = u8(bytes, offset + 0x15)
        val fastRom = (mapByte and 0x10) != 0
        val finalMode = refineExtendedMode(mode, mapByte, bytes.size)

        val cartType = u8(bytes, offset + 0x16)
        val romSizeByte = u8(bytes, offset + 0x17)
        val ramSizeByte = u8(bytes, offset + 0x18)
        val destCode = u8(bytes, offset + 0x19)
        val version = u8(bytes, offset + 0x1B)
        val complement = u16(bytes, offset + 0x1C)
        val checksum = u16(bytes, offset + 0x1E)

        // Vectors sit right after the header, in the native/emulation vector tables.
        // Emulation-mode RESET vector is always at header_offset + 0x3C ($FFFC / $7FFC).
        val resetVector = u16safe(bytes, offset + 0x3C)
        val nmiVector = u16safe(bytes, offset + 0x3A)
        val irqVector = u16safe(bytes, offset + 0x3E)

        return RomInfo(
            fileName = fileName,
            fileSizeBytes = bytes.size,
            copierHeaderPresent = copierHeaderPresent,
            mapMode = finalMode,
            fastRom = fastRom,
            title = extractTitle(bytes, offset),
            cartridgeTypeRaw = cartType,
            cartridgeTypeLabel = RomTables.cartridgeTypeLabel(cartType),
            romSizeKb = if (romSizeByte in 0..23) 1 shl romSizeByte else 0,
            ramSizeKb = if (ramSizeByte in 0..15 && ramSizeByte != 0) 1 shl ramSizeByte else 0,
            destinationCode = destCode,
            destinationLabel = RomTables.destinationLabel(destCode),
            version = version,
            checksum = checksum,
            checksumComplement = complement,
            checksumValid = (checksum xor complement) == 0xFFFF,
            headerFileOffset = offset,
            resetVector = resetVector,
            nmiVector = nmiVector,
            irqVector = irqVector
        )
    }

    private fun refineExtendedMode(base: MapMode, mapByte: Int, fileSize: Int): MapMode {
        val lowNibble = mapByte and 0x0F
        return when {
            base == MapMode.HIROM && fileSize > 3 * 1024 * 1024 -> MapMode.EXHIROM
            base == MapMode.LOROM && lowNibble == 0x2 -> MapMode.EXLOROM
            else -> base
        }
    }

    private fun u8(bytes: ByteArray, index: Int): Int {
        if (index < 0 || index >= bytes.size) return 0
        return bytes[index].toInt() and 0xFF
    }

    private fun u16(bytes: ByteArray, index: Int): Int {
        return u8(bytes, index) or (u8(bytes, index + 1) shl 8)
    }

    private fun u16safe(bytes: ByteArray, index: Int): Int {
        if (index + 1 >= bytes.size) return -1
        return u16(bytes, index)
    }
}
