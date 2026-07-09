#include "cdl.h"
#include <cstring>
#include <vector>

static std::vector<uint8_t> s9x_cdl_map;
static uint8_t  s9x_cdl_bank = 0;
static uint16_t s9x_cdl_addr = 0;
static uint32_t s9x_cdl_offset = 0;
static bool     s9x_cdl_recording = true;

void S9xCdlSetRecording(bool on) { s9x_cdl_recording = on; }
bool S9xCdlIsRecording(void) { return s9x_cdl_recording; }

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
