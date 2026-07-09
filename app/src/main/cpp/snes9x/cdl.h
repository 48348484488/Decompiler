// Code/Data Logger for SNES Deco.
// Not part of upstream snes9x - added to let the app mark, byte by byte,
// which ROM offsets were actually executed as CPU instructions while
// playing. This is the standard "CDL" technique used by SNES ROM hacking
// tools (Mesen-S, bsnes-plus) to separate real code from graphics/data in
// a disassembly.
#ifndef _S9X_CDL_H_
#define _S9X_CDL_H_

#include <cstdint>
#include <cstddef>

#define CDL_FLAG_CODE      0x01 // first byte of an executed instruction (opcode)
#define CDL_FLAG_OPERAND   0x02 // operand byte(s) belonging to an executed instruction

void S9xCdlInit(size_t romSize);
void S9xCdlReset(void);
void S9xCdlMarkExec(uint32_t romOffset, int length);

// Recording gate: when off, S9xCdlMarkExec is a no-op. Lets the app capture
// only the window the user cares about (press REC, play, press stop).
void S9xCdlSetRecording(bool on);
bool S9xCdlIsRecording(void);

// Sandbox mode: when on, the CPU hook checks each executed address against the
// captured CDL map. Hitting an address that was NOT previously captured trips
// the boundary flag (and remembers where), so the app can freeze the emulator
// exactly at the edge of what was captured. Requires recording to be OFF (you
// capture first, then replay inside the captured set).
void S9xCdlSetSandbox(bool on);
bool S9xCdlIsSandbox(void);
bool S9xCdlBoundaryHit(void);
void S9xCdlClearBoundary(void);
uint32_t S9xCdlBoundaryOffset(void);
// Returns true if this offset was captured (code or operand) - used by the hook.
bool S9xCdlIsCaptured(uint32_t romOffset);

// Live "currently executing" program counter, for the real-time view.
void S9xCdlSetCurrentPC(uint8_t bank, uint16_t addr, uint32_t romOffset);
uint8_t S9xCdlGetCurrentBank(void);
uint16_t S9xCdlGetCurrentAddr(void);
uint32_t S9xCdlGetCurrentOffset(void);

const uint8_t *S9xCdlGetMap(size_t *outSize);

#endif
