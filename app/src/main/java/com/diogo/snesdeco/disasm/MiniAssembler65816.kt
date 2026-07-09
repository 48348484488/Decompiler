package com.diogo.snesdeco.disasm

/**
 * Minimal 65816 assembler for the Lab's "edit one instruction and test" flow.
 *
 * It reuses the existing Opcodes65816 table as a reverse lookup: given a
 * mnemonic + an operand written in the SAME syntax the disassembler prints
 * ($1234, #$12, $12,X, etc.), it finds the matching opcode byte and encodes
 * the operand. Immediate width (IMM_A/IMM_X) is inferred from how many hex
 * digits the user wrote (#$12 = 1 byte, #$1234 = 2 bytes).
 *
 * This is intentionally scoped to single-line edits used for patch testing,
 * not a full multi-pass assembler with labels.
 */
object MiniAssembler65816 {

    data class Result(val bytes: ByteArray?, val error: String?)

    private data class Encoded(val opcode: Int, val mode: AddrMode)

    fun assembleLine(line: String): Result {
        // Strip any leading "BANK:ADDR bytes" prefix the disasm view shows, and comments.
        var text = line.trim()
        // Remove a leading "xx:xxxx  hh hh hh " address+bytes prefix if present.
        val prefix = Regex("^[0-9A-Fa-f]{2}:[0-9A-Fa-f]{4}\\s+([0-9A-Fa-f]{2}\\s+)+")
        text = prefix.replace(text, "")
        text = text.substringBefore(';').trim()
        if (text.isEmpty()) return Result(null, "linha vazia")

        val parts = text.split(Regex("\\s+"), limit = 2)
        val mnemonic = parts[0].uppercase()
        val operand = if (parts.size > 1) parts[1].replace(" ", "") else ""

        val mode = classifyOperand(operand)
            ?: return Result(null, "operando não reconhecido: '$operand'")

        val enc = findOpcode(mnemonic, mode, operand)
            ?: return Result(null, "instrução não suportada: $mnemonic $operand")

        return Result(encode(enc, operand), null)
    }

    private fun classifyOperand(op: String): AddrMode? {
        if (op.isEmpty()) return AddrMode.IMP
        if (op.equals("A", true)) return AddrMode.ACC
        val u = op.uppercase()

        fun hexLen(s: String): Int = s.count { it.isDigit() || it in 'A'..'F' }

        return when {
            u.startsWith("#$") -> if (hexLen(u.substring(2)) <= 2) AddrMode.IMM_A else AddrMode.IMM_A // width resolved later
            u.startsWith("[$") && u.endsWith("],Y") -> AddrMode.DP_IND_LONG_Y
            u.startsWith("[$") && u.endsWith("]") -> AddrMode.DP_IND_LONG
            u.startsWith("($") && u.endsWith(",X)") -> AddrMode.DP_IND_X
            u.startsWith("($") && u.endsWith("),Y") -> AddrMode.DP_IND_Y
            u.startsWith("($") && u.endsWith(")") -> {
                val digits = hexLen(u.filter { it != '$' && it != '(' && it != ')' })
                if (digits <= 2) AddrMode.DP_IND else AddrMode.ABS_IND
            }
            u.endsWith(",X") -> {
                val digits = hexLen(u.filter { it.isDigit() || it in 'A'..'F' })
                when {
                    digits <= 2 -> AddrMode.DP_X
                    digits <= 4 -> AddrMode.ABS_X
                    else -> AddrMode.ABS_LONG_X
                }
            }
            u.endsWith(",Y") -> {
                val digits = hexLen(u.filter { it.isDigit() || it in 'A'..'F' })
                if (digits <= 2) AddrMode.DP_Y else AddrMode.ABS_Y
            }
            u.endsWith(",S") -> AddrMode.SR
            u.startsWith("$") -> {
                val digits = hexLen(u.substring(1))
                when {
                    digits <= 2 -> AddrMode.DP
                    digits <= 4 -> AddrMode.ABS
                    else -> AddrMode.ABS_LONG
                }
            }
            else -> null
        }
    }

    private fun findOpcode(mnemonic: String, mode: AddrMode, operand: String): Encoded? {
        // Immediate: try the exact byte width the user typed first.
        val table = Opcodes65816.table
        // Build candidate modes (immediates can be IMM_A/IMM_X/IMM8).
        val candidateModes: List<AddrMode> = when (mode) {
            AddrMode.IMM_A -> listOf(AddrMode.IMM_A, AddrMode.IMM_X, AddrMode.IMM8)
            AddrMode.ABS -> listOf(AddrMode.ABS, AddrMode.REL8, AddrMode.REL16) // branches print as $addr
            else -> listOf(mode)
        }
        for (m in candidateModes) {
            for (i in table.indices) {
                if (table[i].mnemonic == mnemonic && table[i].mode == m) {
                    return Encoded(i, m)
                }
            }
        }
        return null
    }

    private fun encode(enc: Encoded, operand: String): ByteArray {
        val out = ArrayList<Int>()
        out.add(enc.opcode)
        val hex = operand.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        val value = if (hex.isEmpty()) 0 else hex.toLong(16)

        when (enc.mode) {
            AddrMode.IMP, AddrMode.ACC -> { /* no operand */ }
            AddrMode.IMM8, AddrMode.DP, AddrMode.DP_X, AddrMode.DP_Y,
            AddrMode.DP_IND, AddrMode.DP_IND_X, AddrMode.DP_IND_Y,
            AddrMode.DP_IND_LONG, AddrMode.DP_IND_LONG_Y, AddrMode.SR, AddrMode.SR_IND_Y ->
                out.add((value and 0xFF).toInt())
            AddrMode.IMM_A, AddrMode.IMM_X -> {
                // 1 byte if user wrote <=2 hex digits, else 2 bytes.
                if (hex.length <= 2) out.add((value and 0xFF).toInt())
                else { out.add((value and 0xFF).toInt()); out.add(((value shr 8) and 0xFF).toInt()) }
            }
            AddrMode.ABS, AddrMode.ABS_X, AddrMode.ABS_Y,
            AddrMode.ABS_IND, AddrMode.ABS_IND_LONG, AddrMode.ABS_IND_X -> {
                out.add((value and 0xFF).toInt()); out.add(((value shr 8) and 0xFF).toInt())
            }
            AddrMode.ABS_LONG, AddrMode.ABS_LONG_X -> {
                out.add((value and 0xFF).toInt())
                out.add(((value shr 8) and 0xFF).toInt())
                out.add(((value shr 16) and 0xFF).toInt())
            }
            AddrMode.REL8 -> out.add(0) // caller should avoid editing branches for now
            AddrMode.REL16, AddrMode.BLOCK -> { out.add(0); out.add(0) }
        }
        return ByteArray(out.size) { out[it].toByte() }
    }
}
