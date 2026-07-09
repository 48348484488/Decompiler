#include "cdl.h"
#include <cstring>
#include <vector>

static std::vector<uint8_t> s9x_cdl_map;
static uint8_t  s9x_cdl_bank = 0;
static uint16_t s9x_cdl_addr = 0;
static uint32_t s9x_cdl_offset = 0;
static bool     s9x_cdl_recording = true;
static bool     s9x_cdl_sandbox = false;
static bool     s9x_cdl_boundary = false;
static uint32_t s9x_cdl_boundary_off = 0;
static int      s9x_cdl_uncaptured_streak = 0;   // consecutive uncaptured ROM instrs
static uint64_t s9x_cdl_sandbox_instrs = 0;      // instrs since sandbox began

// Freeze only after this many CONSECUTIVE uncaptured ROM instructions. Boot
// and init code legitimately touch scattered bytes that a short capture may
// have missed; a sustained run of uncaptured code is the true boundary.
static const int SANDBOX_STREAK_LIMIT = 240;
// Give the machine a warmup window before sandbox can freeze at all, so the
// unavoidable boot path doesn't trip it instantly.
static const uint64_t SANDBOX_WARMUP = 200000;

void S9xCdlSetRecording(bool on) { s9x_cdl_recording = on; }
bool S9xCdlIsRecording(void) { return s9x_cdl_recording; }

void S9xCdlSetSandbox(bool on)
{
	s9x_cdl_sandbox = on;
	s9x_cdl_boundary = false;
	s9x_cdl_uncaptured_streak = 0;
	s9x_cdl_sandbox_instrs = 0;
}
bool S9xCdlIsSandbox(void) { return s9x_cdl_sandbox; }
bool S9xCdlBoundaryHit(void) { return s9x_cdl_boundary; }
void S9xCdlClearBoundary(void) { s9x_cdl_boundary = false; s9x_cdl_uncaptured_streak = 0; }
uint32_t S9xCdlBoundaryOffset(void) { return s9x_cdl_boundary_off; }

bool S9xCdlIsCaptured(uint32_t romOffset)
{
	if (romOffset >= s9x_cdl_map.size()) return false;
	return (s9x_cdl_map[romOffset] & (CDL_FLAG_CODE | CDL_FLAG_OPERAND)) != 0;
}

void S9xCdlInit(size_t romSize)
{
	s9x_cdl_map.assign(romSize, 0);
}

void S9xCdlReset(void)
{
	std::fill(s9x_cdl_map.begin(), s9x_cdl_map.end(), 0);
}

void S9xCdlMarkExec(uint32_t romOffset, int length)
{
	// Sandbox mode: don't record; instead watch for a sustained run of
	// uncaptured ROM code, which marks the real edge of the captured slice.
	if (s9x_cdl_sandbox)
	{
		s9x_cdl_sandbox_instrs++;
		if (S9xCdlIsCaptured(romOffset))
		{
			s9x_cdl_uncaptured_streak = 0;
		}
		else
		{
			s9x_cdl_uncaptured_streak++;
			if (!s9x_cdl_boundary &&
			    s9x_cdl_sandbox_instrs > SANDBOX_WARMUP &&
			    s9x_cdl_uncaptured_streak >= SANDBOX_STREAK_LIMIT)
			{
				s9x_cdl_boundary = true;
				s9x_cdl_boundary_off = romOffset;
			}
		}
		return;
	}

	if (!s9x_cdl_recording)
		return;
	if (s9x_cdl_map.empty())
		return;
	if (romOffset >= s9x_cdl_map.size())
		return;

	s9x_cdl_map[romOffset] |= CDL_FLAG_CODE;
	for (int i = 1; i < length; i++)
	{
		uint32_t off = romOffset + (uint32_t) i;
		if (off >= s9x_cdl_map.size())
			break;
		s9x_cdl_map[off] |= CDL_FLAG_OPERAND;
	}
}

void S9xCdlSetCurrentPC(uint8_t bank, uint16_t addr, uint32_t romOffset)
{
	s9x_cdl_bank = bank;
	s9x_cdl_addr = addr;
	s9x_cdl_offset = romOffset;
}

uint8_t S9xCdlGetCurrentBank(void) { return s9x_cdl_bank; }
uint16_t S9xCdlGetCurrentAddr(void) { return s9x_cdl_addr; }
uint32_t S9xCdlGetCurrentOffset(void) { return s9x_cdl_offset; }

const uint8_t *S9xCdlGetMap(size_t *outSize)
{
	if (outSize)
		*outSize = s9x_cdl_map.size();
	return s9x_cdl_map.empty() ? nullptr : s9x_cdl_map.data();
}
