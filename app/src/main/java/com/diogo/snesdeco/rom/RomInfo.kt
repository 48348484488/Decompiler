package com.diogo.snesdeco.rom

enum class MapMode(val label: String) {
    LOROM("LoROM"),
    HIROM("HiROM"),
    EXHIROM("ExHiROM"),
    EXLOROM("ExLoROM"),
    UNKNOWN("Desconhecido")
}

data class RomInfo(
    val fileName: String,
    val fileSizeBytes: Int,
    val copierHeaderPresent: Boolean,
    val mapMode: MapMode,
    val fastRom: Boolean,
    val title: String,
    val cartridgeTypeRaw: Int,
    val cartridgeTypeLabel: String,
    val romSizeKb: Int,
    val ramSizeKb: Int,
    val destinationCode: Int,
    val destinationLabel: String,
    val version: Int,
    val checksum: Int,
    val checksumComplement: Int,
    val checksumValid: Boolean,
    val headerFileOffset: Int,
    val resetVector: Int,
    val nmiVector: Int,
    val irqVector: Int
) {
    val displaySize: String
        get() = if (romSizeKb >= 1024) "${romSizeKb / 1024} Mb (${romSizeKb} KB)" else "$romSizeKb KB"
}

object RomTables {
    private val destinations = mapOf(
        0x00 to "Japão", 0x01 to "América do Norte", 0x02 to "Europa/Oceania/Ásia",
        0x03 to "Suécia", 0x04 to "Finlândia", 0x05 to "Dinamarca", 0x06 to "França",
        0x07 to "Holanda", 0x08 to "Espanha", 0x09 to "Alemanha", 0x0A to "Itália",
        0x0B to "China/Hong Kong", 0x0C to "Indonésia", 0x0D to "Coreia",
        0x0E to "Global/Internacional", 0x0F to "Canadá", 0x10 to "Brasil", 0x11 to "Austrália"
    )

    fun destinationLabel(code: Int): String = destinations[code] ?: "Desconhecido (0x${code.toString(16)})"

    fun cartridgeTypeLabel(code: Int): String = when (code) {
        0x00 -> "ROM"
        0x01 -> "ROM+RAM"
        0x02 -> "ROM+RAM+Bateria"
        0x03 -> "ROM+DSP"
        0x04 -> "ROM+DSP+RAM"
        0x05 -> "ROM+DSP+RAM+Bateria"
        0x13 -> "ROM+SuperFX"
        0x14 -> "ROM+SuperFX+RAM"
        0x15 -> "ROM+SuperFX+RAM+Bateria"
        0x1A -> "ROM+SuperFX2"
        0x32 -> "ROM+SA-1"
        0x34 -> "ROM+SA-1+RAM"
        0x35 -> "ROM+SA-1+RAM+Bateria"
        0x43 -> "ROM+SDD-1"
        0x45 -> "ROM+SDD-1+RAM+Bateria"
        0xF3 -> "ROM+CX4"
        0xF5 -> "ROM+SPC7110+RTC"
        0xF6 -> "ROM+SPC7110"
        0xF9 -> "ROM+SPC7110+RTC+Bateria"
        else -> "Chip 0x${code.toString(16).uppercase()} (não catalogado)"
    }
}
