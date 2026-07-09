package com.diogo.snesdeco.disasm

/**
 * Addressing modes of the WDC 65816 (SNES CPU). IMM_A / IMM_X are the two
 * modes whose operand width depends on the processor status flags (M/X),
 * which is why the disassembler needs to track REP/SEP while it sweeps.
 */
enum class AddrMode {
    IMP, ACC, IMM8, IMM_A, IMM_X,
    DP, DP_X, DP_Y,
    DP_IND, DP_IND_X, DP_IND_Y,
    DP_IND_LONG, DP_IND_LONG_Y,
    ABS, ABS_X, ABS_Y,
    ABS_LONG, ABS_LONG_X,
    ABS_IND, ABS_IND_LONG, ABS_IND_X,
    REL8, REL16,
    SR, SR_IND_Y,
    BLOCK
}

data class Opcode(val mnemonic: String, val mode: AddrMode)

object Opcodes65816 {
    val table: Array<Opcode> = arrayOf(
        Opcode("BRK", AddrMode.IMM8), Opcode("ORA", AddrMode.DP_IND_X), Opcode("COP", AddrMode.IMM8), Opcode("ORA", AddrMode.SR),
        Opcode("TSB", AddrMode.DP), Opcode("ORA", AddrMode.DP), Opcode("ASL", AddrMode.DP), Opcode("ORA", AddrMode.DP_IND_LONG),
        Opcode("PHP", AddrMode.IMP), Opcode("ORA", AddrMode.IMM_A), Opcode("ASL", AddrMode.ACC), Opcode("PHD", AddrMode.IMP),
        Opcode("TSB", AddrMode.ABS), Opcode("ORA", AddrMode.ABS), Opcode("ASL", AddrMode.ABS), Opcode("ORA", AddrMode.ABS_LONG),

        Opcode("BPL", AddrMode.REL8), Opcode("ORA", AddrMode.DP_IND_Y), Opcode("ORA", AddrMode.DP_IND), Opcode("ORA", AddrMode.SR_IND_Y),
        Opcode("TRB", AddrMode.DP), Opcode("ORA", AddrMode.DP_X), Opcode("ASL", AddrMode.DP_X), Opcode("ORA", AddrMode.DP_IND_LONG_Y),
        Opcode("CLC", AddrMode.IMP), Opcode("ORA", AddrMode.ABS_Y), Opcode("INC", AddrMode.ACC), Opcode("TCS", AddrMode.IMP),
        Opcode("TRB", AddrMode.ABS), Opcode("ORA", AddrMode.ABS_X), Opcode("ASL", AddrMode.ABS_X), Opcode("ORA", AddrMode.ABS_LONG_X),

        Opcode("JSR", AddrMode.ABS), Opcode("AND", AddrMode.DP_IND_X), Opcode("JSL", AddrMode.ABS_LONG), Opcode("AND", AddrMode.SR),
        Opcode("BIT", AddrMode.DP), Opcode("AND", AddrMode.DP), Opcode("ROL", AddrMode.DP), Opcode("AND", AddrMode.DP_IND_LONG),
        Opcode("PLP", AddrMode.IMP), Opcode("AND", AddrMode.IMM_A), Opcode("ROL", AddrMode.ACC), Opcode("PLD", AddrMode.IMP),
        Opcode("BIT", AddrMode.ABS), Opcode("AND", AddrMode.ABS), Opcode("ROL", AddrMode.ABS), Opcode("AND", AddrMode.ABS_LONG),

        Opcode("BMI", AddrMode.REL8), Opcode("AND", AddrMode.DP_IND_Y), Opcode("AND", AddrMode.DP_IND), Opcode("AND", AddrMode.SR_IND_Y),
        Opcode("BIT", AddrMode.DP_X), Opcode("AND", AddrMode.DP_X), Opcode("ROL", AddrMode.DP_X), Opcode("AND", AddrMode.DP_IND_LONG_Y),
        Opcode("SEC", AddrMode.IMP), Opcode("AND", AddrMode.ABS_Y), Opcode("DEC", AddrMode.ACC), Opcode("TSC", AddrMode.IMP),
        Opcode("BIT", AddrMode.ABS_X), Opcode("AND", AddrMode.ABS_X), Opcode("ROL", AddrMode.ABS_X), Opcode("AND", AddrMode.ABS_LONG_X),

        Opcode("RTI", AddrMode.IMP), Opcode("EOR", AddrMode.DP_IND_X), Opcode("WDM", AddrMode.IMM8), Opcode("EOR", AddrMode.SR),
        Opcode("MVP", AddrMode.BLOCK), Opcode("EOR", AddrMode.DP), Opcode("LSR", AddrMode.DP), Opcode("EOR", AddrMode.DP_IND_LONG),
        Opcode("PHA", AddrMode.IMP), Opcode("EOR", AddrMode.IMM_A), Opcode("LSR", AddrMode.ACC), Opcode("PHK", AddrMode.IMP),
        Opcode("JMP", AddrMode.ABS), Opcode("EOR", AddrMode.ABS), Opcode("LSR", AddrMode.ABS), Opcode("EOR", AddrMode.ABS_LONG),

        Opcode("BVC", AddrMode.REL8), Opcode("EOR", AddrMode.DP_IND_Y), Opcode("EOR", AddrMode.DP_IND), Opcode("EOR", AddrMode.SR_IND_Y),
        Opcode("MVN", AddrMode.BLOCK), Opcode("EOR", AddrMode.DP_X), Opcode("LSR", AddrMode.DP_X), Opcode("EOR", AddrMode.DP_IND_LONG_Y),
        Opcode("CLI", AddrMode.IMP), Opcode("EOR", AddrMode.ABS_Y), Opcode("PHY", AddrMode.IMP), Opcode("TCD", AddrMode.IMP),
        Opcode("JML", AddrMode.ABS_LONG), Opcode("EOR", AddrMode.ABS_X), Opcode("LSR", AddrMode.ABS_X), Opcode("EOR", AddrMode.ABS_LONG_X),

        Opcode("RTS", AddrMode.IMP), Opcode("ADC", AddrMode.DP_IND_X), Opcode("PER", AddrMode.REL16), Opcode("ADC", AddrMode.SR),
        Opcode("STZ", AddrMode.DP), Opcode("ADC", AddrMode.DP), Opcode("ROR", AddrMode.DP), Opcode("ADC", AddrMode.DP_IND_LONG),
        Opcode("PLA", AddrMode.IMP), Opcode("ADC", AddrMode.IMM_A), Opcode("ROR", AddrMode.ACC), Opcode("RTL", AddrMode.IMP),
        Opcode("JMP", AddrMode.ABS_IND), Opcode("ADC", AddrMode.ABS), Opcode("ROR", AddrMode.ABS), Opcode("ADC", AddrMode.ABS_LONG),

        Opcode("BVS", AddrMode.REL8), Opcode("ADC", AddrMode.DP_IND_Y), Opcode("ADC", AddrMode.DP_IND), Opcode("ADC", AddrMode.SR_IND_Y),
        Opcode("STZ", AddrMode.DP_X), Opcode("ADC", AddrMode.DP_X), Opcode("ROR", AddrMode.DP_X), Opcode("ADC", AddrMode.DP_IND_LONG_Y),
        Opcode("SEI", AddrMode.IMP), Opcode("ADC", AddrMode.ABS_Y), Opcode("PLY", AddrMode.IMP), Opcode("TDC", AddrMode.IMP),
        Opcode("JMP", AddrMode.ABS_IND_X), Opcode("ADC", AddrMode.ABS_X), Opcode("ROR", AddrMode.ABS_X), Opcode("ADC", AddrMode.ABS_LONG_X),

        Opcode("BRA", AddrMode.REL8), Opcode("STA", AddrMode.DP_IND_X), Opcode("BRL", AddrMode.REL16), Opcode("STA", AddrMode.SR),
        Opcode("STY", AddrMode.DP), Opcode("STA", AddrMode.DP), Opcode("STX", AddrMode.DP), Opcode("STA", AddrMode.DP_IND_LONG),
        Opcode("DEY", AddrMode.IMP), Opcode("BIT", AddrMode.IMM_A), Opcode("TXA", AddrMode.IMP), Opcode("PHB", AddrMode.IMP),
        Opcode("STY", AddrMode.ABS), Opcode("STA", AddrMode.ABS), Opcode("STX", AddrMode.ABS), Opcode("STA", AddrMode.ABS_LONG),

        Opcode("BCC", AddrMode.REL8), Opcode("STA", AddrMode.DP_IND_Y), Opcode("STA", AddrMode.DP_IND), Opcode("STA", AddrMode.SR_IND_Y),
        Opcode("STY", AddrMode.DP_X), Opcode("STA", AddrMode.DP_X), Opcode("STX", AddrMode.DP_Y), Opcode("STA", AddrMode.DP_IND_LONG_Y),
        Opcode("TYA", AddrMode.IMP), Opcode("STA", AddrMode.ABS_Y), Opcode("TXS", AddrMode.IMP), Opcode("TXY", AddrMode.IMP),
        Opcode("STZ", AddrMode.ABS), Opcode("STA", AddrMode.ABS_X), Opcode("STZ", AddrMode.ABS_X), Opcode("STA", AddrMode.ABS_LONG_X),

        Opcode("LDY", AddrMode.IMM_X), Opcode("LDA", AddrMode.DP_IND_X), Opcode("LDX", AddrMode.IMM_X), Opcode("LDA", AddrMode.SR),
        Opcode("LDY", AddrMode.DP), Opcode("LDA", AddrMode.DP), Opcode("LDX", AddrMode.DP), Opcode("LDA", AddrMode.DP_IND_LONG),
        Opcode("TAY", AddrMode.IMP), Opcode("LDA", AddrMode.IMM_A), Opcode("TAX", AddrMode.IMP), Opcode("PLB", AddrMode.IMP),
        Opcode("LDY", AddrMode.ABS), Opcode("LDA", AddrMode.ABS), Opcode("LDX", AddrMode.ABS), Opcode("LDA", AddrMode.ABS_LONG),

        Opcode("BCS", AddrMode.REL8), Opcode("LDA", AddrMode.DP_IND_Y), Opcode("LDA", AddrMode.DP_IND), Opcode("LDA", AddrMode.SR_IND_Y),
        Opcode("LDY", AddrMode.DP_X), Opcode("LDA", AddrMode.DP_X), Opcode("LDX", AddrMode.DP_Y), Opcode("LDA", AddrMode.DP_IND_LONG_Y),
        Opcode("CLV", AddrMode.IMP), Opcode("LDA", AddrMode.ABS_Y), Opcode("TSX", AddrMode.IMP), Opcode("TYX", AddrMode.IMP),
        Opcode("LDY", AddrMode.ABS_X), Opcode("LDA", AddrMode.ABS_X), Opcode("LDX", AddrMode.ABS_Y), Opcode("LDA", AddrMode.ABS_LONG_X),

        Opcode("CPY", AddrMode.IMM_X), Opcode("CMP", AddrMode.DP_IND_X), Opcode("REP", AddrMode.IMM8), Opcode("CMP", AddrMode.SR),
        Opcode("CPY", AddrMode.DP), Opcode("CMP", AddrMode.DP), Opcode("DEC", AddrMode.DP), Opcode("CMP", AddrMode.DP_IND_LONG),
        Opcode("INY", AddrMode.IMP), Opcode("CMP", AddrMode.IMM_A), Opcode("DEX", AddrMode.IMP), Opcode("WAI", AddrMode.IMP),
        Opcode("CPY", AddrMode.ABS), Opcode("CMP", AddrMode.ABS), Opcode("DEC", AddrMode.ABS), Opcode("CMP", AddrMode.ABS_LONG),

        Opcode("BNE", AddrMode.REL8), Opcode("CMP", AddrMode.DP_IND_Y), Opcode("CMP", AddrMode.DP_IND), Opcode("CMP", AddrMode.SR_IND_Y),
        Opcode("PEI", AddrMode.DP_IND), Opcode("CMP", AddrMode.DP_X), Opcode("DEC", AddrMode.DP_X), Opcode("CMP", AddrMode.DP_IND_LONG_Y),
        Opcode("CLD", AddrMode.IMP), Opcode("CMP", AddrMode.ABS_Y), Opcode("PHX", AddrMode.IMP), Opcode("STP", AddrMode.IMP),
        Opcode("JML", AddrMode.ABS_IND_LONG), Opcode("CMP", AddrMode.ABS_X), Opcode("DEC", AddrMode.ABS_X), Opcode("CMP", AddrMode.ABS_LONG_X),

        Opcode("CPX", AddrMode.IMM_X), Opcode("SBC", AddrMode.DP_IND_X), Opcode("SEP", AddrMode.IMM8), Opcode("SBC", AddrMode.SR),
        Opcode("CPX", AddrMode.DP), Opcode("SBC", AddrMode.DP), Opcode("INC", AddrMode.DP), Opcode("SBC", AddrMode.DP_IND_LONG),
        Opcode("INX", AddrMode.IMP), Opcode("SBC", AddrMode.IMM_A), Opcode("NOP", AddrMode.IMP), Opcode("XBA", AddrMode.IMP),
        Opcode("CPX", AddrMode.ABS), Opcode("SBC", AddrMode.ABS), Opcode("INC", AddrMode.ABS), Opcode("SBC", AddrMode.ABS_LONG),

        Opcode("BEQ", AddrMode.REL8), Opcode("SBC", AddrMode.DP_IND_Y), Opcode("SBC", AddrMode.DP_IND), Opcode("SBC", AddrMode.SR_IND_Y),
        Opcode("PEA", AddrMode.ABS), Opcode("SBC", AddrMode.DP_X), Opcode("INC", AddrMode.DP_X), Opcode("SBC", AddrMode.DP_IND_LONG_Y),
        Opcode("SED", AddrMode.IMP), Opcode("SBC", AddrMode.ABS_Y), Opcode("PLX", AddrMode.IMP), Opcode("XCE", AddrMode.IMP),
        Opcode("JSR", AddrMode.ABS_IND_X), Opcode("SBC", AddrMode.ABS_X), Opcode("INC", AddrMode.ABS_X), Opcode("SBC", AddrMode.ABS_LONG_X)
    )
}
