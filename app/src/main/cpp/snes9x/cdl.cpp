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

void S9xCdlSetRecording(bool on) { s9x_cdl_recording = on; }
bool S9xCdlIsRecording(void) { return s9x_cdl_recording; }

void S9xCdlSetSandbox(bool on)
{
	s9x_cdl_sandbox = on;
	s9x_cdl_boundary = false;
}
bool S9xCdlIsSandbox(void) { return s9x_cdl_sandbox; }
bool S9xCdlBoundaryHit(void) { return s9x_cdl_boundary; }
void S9xCdlClearBoundary(void) { s9x_cdl_boundary = false; }
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
	// Sandbox mode: don't record; instead flag when execution reaches code
	// that was never captured. That's the boundary of the captured slice.
	if (s9x_cdl_sandbox)
	{
		if (!s9x_cdl_boundary && !S9xCdlIsCaptured(romOffset))
		{
			s9x_cdl_boundary = true;
			s9x_cdl_boundary_off = romOffset;
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
