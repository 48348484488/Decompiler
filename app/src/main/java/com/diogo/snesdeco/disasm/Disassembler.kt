package com.diogo.snesdeco.disasm

import com.diogo.snesdeco.rom.AddressMapper

data class DisasmLine(
    val bank: Int,
    val addr: Int,
    val bytes: ByteArray,
    val mnemonic: String,
    val operandText: String,
    val raw: String // full formatted line, ready to display/export
)

/**
 * Linear-sweep disassembler. SNES code isn't self-describing about operand
 * width (that depends on the M/X status flags at runtime), so like most
 * hobby-grade 65816 disassemblers we track REP #imm / SEP #imm / XCE while
 * sweeping and assume 8-bit accumulator+index right after reset (the SNES
 * boot convention). This is a heuristic, not a flow-accurate decoder -
 * it will drift after an untracked branch into code reached with different
 * flags. Good enough to explore a ROM; not a substitute for a real
 * flow-sensitive tool like a proper disassembler+tracer.
 */
class Disassembler(private val rom: ByteArray, private val mapper: AddressMapper) {

    private var mFlag8Bit = true // Accumulator width: true = 8-bit
    private var xFlag8Bit = true // Index width: true = 8-bit

    fun disassembleRange(bank: Int, startAddr: Int, count: Int): List<DisasmLine> {
        val lines = ArrayList<DisasmLine>(count)
        var addr = startAddr
        var currentBank = bank
        var iterations = 0
        while (lines.size < count && iterations < count * 4) {
            iterations++
            val offset = mapper.toFileOffset(currentBank, addr)
            if (offset < 0 || offset >= rom.size) {
                lines.add(DisasmLine(currentBank, addr, ByteArray(0), "???", "", "%02X:%04X  ??  (fora do mapeamento ROM)".format(currentBank, addr)))
                addr += 1
                if (addr > 0xFFFF) {
                    addr -= 0x10000
                    currentBank = (currentBank + 1) and 0xFF
                }
                continue
            }
            val opByte = rom[offset].toInt() and 0xFF
            val op = Opcodes65816.table[opByte]
            val length = instructionLength(op.mode)
            if (offset + length > rom.size) break

            val bytes = rom.copyOfRange(offset, offset + length)
            updateFlags(op, bytes)

            val operandText = formatOperand(op.mode, bytes, currentBank, addr)
            val hexBytes = bytes.joinToString(" ") { "%02X".format(it) }
            val raw = "%02X:%04X  %-11s %s %s".format(currentBank, addr, hexBytes, op.mnemonic, operandText).trimEnd()

            lines.add(DisasmLine(currentBank, addr, bytes, op.mnemonic, operandText, raw))
            addr += length
            if (addr > 0xFFFF) {
                addr -= 0x10000
                currentBank = (currentBank + 1) and 0xFF
            }
        }
        return lines
    }

    private fun updateFlags(op: Opcode, bytes: ByteArray) {
        when (op.mnemonic) {
            "SEP" -> {
                val mask = bytes.getOrElse(1) { 0 }.toInt() and 0xFF
                if (mask and 0x20 != 0) mFlag8Bit = true
                if (mask and 0x10 != 0) xFlag8Bit = true
            }
            "REP" -> {
                val mask = bytes.getOrElse(1) { 0 }.toInt() and 0xFF
                if (mask and 0x20 != 0) mFlag8Bit = false
                if (mask and 0x10 != 0) xFlag8Bit = false
            }
            "XCE" -> {
                // Entering native mode is commonly followed by REP #$30; we don't
                // know E here, so we leave flags untouched and rely on the
                // subsequent REP/SEP the code will almost always issue.
            }
        }
    }

    private fun instructionLength(mode: AddrMode): Int = when (mode) {
        AddrMode.IMP, AddrMode.ACC -> 1
        AddrMode.IMM_A -> if (mFlag8Bit) 2 else 3
        AddrMode.IMM_X -> if (xFlag8Bit) 2 else 3
        AddrMode.IMM8, AddrMode.DP, AddrMode.DP_X, AddrMode.DP_Y,
        AddrMode.DP_IND, AddrMode.DP_IND_X, AddrMode.DP_IND_Y,
        AddrMode.DP_IND_LONG, AddrMode.DP_IND_LONG_Y,
        AddrMode.REL8, AddrMode.SR, AddrMode.SR_IND_Y -> 2
        AddrMode.ABS, AddrMode.ABS_X, AddrMode.ABS_Y,
        AddrMode.ABS_IND, AddrMode.ABS_IND_LONG, AddrMode.ABS_IND_X,
        AddrMode.REL16, AddrMode.BLOCK -> 3
        AddrMode.ABS_LONG, AddrMode.ABS_LONG_X -> 4
    }

    private fun formatOperand(mode: AddrMode, b: ByteArray, bank: Int, addr: Int): String {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)
        return when (mode) {
            AddrMode.IMP -> ""
            AddrMode.ACC -> "A"
            AddrMode.IMM8, AddrMode.IMM_A, AddrMode.IMM_X ->
                if (b.size == 2) "#$%02X".format(u8(1)) else "#$%04X".format(u16(1))
            AddrMode.DP -> "$%02X".format(u8(1))
            AddrMode.DP_X -> "$%02X,X".format(u8(1))
            AddrMode.DP_Y -> "$%02X,Y".format(u8(1))
            AddrMode.DP_IND -> "($%02X)".format(u8(1))
            AddrMode.DP_IND_X -> "($%02X,X)".format(u8(1))
            AddrMode.DP_IND_Y -> "($%02X),Y".format(u8(1))
            AddrMode.DP_IND_LONG -> "[$%02X]".format(u8(1))
            AddrMode.DP_IND_LONG_Y -> "[$%02X],Y".format(u8(1))
            AddrMode.ABS -> "$%04X".format(u16(1))
            AddrMode.ABS_X -> "$%04X,X".format(u16(1))
            AddrMode.ABS_Y -> "$%04X,Y".format(u16(1))
            AddrMode.ABS_LONG -> "$%06X".format(u24(1))
            AddrMode.ABS_LONG_X -> "$%06X,X".format(u24(1))
            AddrMode.ABS_IND -> "($%04X)".format(u16(1))
            AddrMode.ABS_IND_LONG -> "[$%04X]".format(u16(1))
            AddrMode.ABS_IND_X -> "($%04X,X)".format(u16(1))
            AddrMode.REL8 -> {
                val rel = b[1].toInt() // signed byte
                val target = (addr + 2 + rel) and 0xFFFF
                "$%04X".format(target)
            }
            AddrMode.REL16 -> {
                val rel = u16(1).toShort().toInt()
                val target = (addr + 3 + rel) and 0xFFFF
                "$%04X".format(target)
            }
            AddrMode.SR -> "$%02X,S".format(u8(1))
            AddrMode.SR_IND_Y -> "($%02X,S),Y".format(u8(1))
            AddrMode.BLOCK -> "$%02X,$%02X".format(u8(1), u8(2))
        }
    }

    fun resetFlagAssumption() {
        mFlag8Bit = true
        xFlag8Bit = true
    }
}
