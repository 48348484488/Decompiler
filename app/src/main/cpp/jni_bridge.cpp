// SNES Deco - JNI bridge around the vendored snes9x core.
//
// This file plays the role that libretro.cpp plays for the official
// libretro port: it implements the small set of S9x* functions the core
// expects a frontend to provide (video/audio delivery, message logging,
// pause/exit hooks, snapshot file I/O we don't support), and adds the
// JNI entry points Kotlin calls into.
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <vector>
#include <mutex>

#include "snes9x.h"
#include "memmap.h"
#include "apu/apu.h"
#include "gfx.h"
#include "controls.h"
#include "conffile.h"
#include "fscompat.h"
#include "display.h"
#include "movie.h"
#include "snapshot.h"
#include "cdl.h"
#include "bridge.h"

#define LOG_TAG "SNESDecoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------
// State shared between the core callbacks and the JNI entry points.
// ---------------------------------------------------------------------
static bool s9x_initialized = false;
static bool s9x_rom_loaded = false;

static std::vector<uint16_t> s9x_last_frame; // RGB565, fixed SNES_WIDTH x SNES_HEIGHT
static int s9x_last_frame_width = SNES_WIDTH;
static int s9x_last_frame_height = SNES_HEIGHT;

static std::vector<int16_t> s9x_audio_accum; // interleaved stereo samples collected this call to nativeRunFrame

// ---------------------------------------------------------------------
// snes9x porting interface (the functions the core calls into us for).
// Mirrors libretro.cpp's contract but stripped to what SNES Deco needs.
// ---------------------------------------------------------------------
void S9xMessage(int type, int number, const char *message)
{
	LOGI("S9xMessage[%d/%d]: %s", type, number, message ? message : "");
}

void S9xExit(void) {}
void S9xSetPause(uint32) {}
void S9xClearPause(uint32) {}

bool8 S9xInitUpdate() { return TRUE; }

bool8 S9xDeinitUpdate(int width, int height)
{
	if (width <= 0 || height <= 0)
		return TRUE;

	int copyW = width;
	int copyH = height;
	if (copyW > SNES_WIDTH) copyW = SNES_WIDTH;
	if (copyH > MAX_SNES_HEIGHT) copyH = MAX_SNES_HEIGHT;

	if ((int) s9x_last_frame.size() < SNES_WIDTH * MAX_SNES_HEIGHT)
		s9x_last_frame.assign(SNES_WIDTH * MAX_SNES_HEIGHT, 0);

	const int strideElems = GFX.Pitch / 2; // uint16 elements per row in GFX.Screen
	for (int y = 0; y < copyH; y++)
	{
		const uint16_t *src = GFX.Screen + (size_t) y * strideElems;
		uint16_t *dst = s9x_last_frame.data() + (size_t) y * SNES_WIDTH;
		memcpy(dst, src, (size_t) copyW * sizeof(uint16_t));
	}

	s9x_last_frame_width = copyW;
	s9x_last_frame_height = copyH;
	return TRUE;
}

bool8 S9xContinueUpdate(int width, int height)
{
	return S9xDeinitUpdate(width, height);
}

void S9xSyncSpeed(void)
{
	if (Settings.Mute)
	{
		S9xClearSamples();
		return;
	}

	int avail = S9xGetSampleCount();
	if (avail <= 0)
		return;

	size_t oldSize = s9x_audio_accum.size();
	s9x_audio_accum.resize(oldSize + (size_t) avail);
	S9xMixSamples((uint8 *) (s9x_audio_accum.data() + oldSize), avail);
}

bool8 S9xOpenSoundDevice(void) { return TRUE; }

void S9xParsePortConfig(ConfigFile &, int) {}
void S9xInitInputDevices() {}
void S9xHandlePortCommand(s9xcommand_t, short, short) {}
bool S9xPollButton(uint32, bool *) { return false; }
bool S9xPollAxis(uint32, short *) { return false; }
bool S9xPollPointer(uint32, short *, short *) { return false; }
void S9xToggleSoundChannel(int) {}
void S9xExtraUsage(void) {}
void S9xParseArg(char **, int &, int) {}

bool8 S9xOpenSnapshotFile(const char *, bool8, STREAM *) { return FALSE; }
void S9xCloseSnapshotFile(STREAM) {}
void S9xAutoSaveSRAM(void) {}

std::string S9xGetDirectory(enum s9x_getdirtype) { return "/data/local/tmp"; }
std::string S9xGetFilenameInc(std::string, enum s9x_getdirtype) { return "/data/local/tmp/snesdeco.tmp"; }
const char *S9xStringInput(const char *) { return nullptr; }

// ---------------------------------------------------------------------
// Internal setup helpers
// ---------------------------------------------------------------------
static void s9xdeco_init_core()
{
	if (s9x_initialized)
		return;

	memset(&Settings, 0, sizeof(Settings));
	Settings.MouseMaster = TRUE;
	Settings.SuperScopeMaster = TRUE;
	Settings.JustifierMaster = TRUE;
	Settings.MultiPlayer5Master = TRUE;
	Settings.FrameTimePAL = 20000;
	Settings.FrameTimeNTSC = 16667;
	Settings.SixteenBitSound = TRUE;
	Settings.Stereo = TRUE;
	Settings.SoundPlaybackRate = 32040;
	Settings.SoundInputRate = 32040;
	Settings.Transparency = TRUE;
	Settings.AutoDisplayMessages = FALSE;
	Settings.BlockInvalidVRAMAccessMaster = TRUE;
	Settings.HDMATimingHack = 100;
	Settings.DontSaveOopsSnapshot = TRUE;
	// SNES hardware allows 34 sprite tiles per scanline. This was left at 0
	// by the memset above, which culls EVERY sprite (backgrounds render but
	// characters/objects vanish). Must be set for sprites to appear.
	Settings.MaxSpriteTilesPerLine = 34;
	Settings.InterpolationMethod = 2;

	CPU.Flags = 0;

	if (!Memory.Init() || !S9xInitAPU())
	{
		LOGE("Memory.Init/S9xInitAPU failed");
		return;
	}

	S9xInitSound(32);
	S9xSetSoundMute(FALSE);
	S9xSetSamplesAvailableCallback(nullptr, nullptr);

	S9xGraphicsInit();
	S9xInitInputDevices();
	S9xSetController(0, CTL_JOYPAD, 0, 0, 0, 0);
	S9xSetController(1, CTL_JOYPAD, 1, 0, 0, 0);

	S9xUnmapAllControls();
	S9xMapButton(((1) << 4) | BTN_A, S9xGetCommandT("Joypad1 A"), false);
	S9xMapButton(((1) << 4) | BTN_B, S9xGetCommandT("Joypad1 B"), false);
	S9xMapButton(((1) << 4) | BTN_X, S9xGetCommandT("Joypad1 X"), false);
	S9xMapButton(((1) << 4) | BTN_Y, S9xGetCommandT("Joypad1 Y"), false);
	S9xMapButton(((1) << 4) | BTN_L, S9xGetCommandT("Joypad1 L"), false);
	S9xMapButton(((1) << 4) | BTN_R, S9xGetCommandT("Joypad1 R"), false);
	S9xMapButton(((1) << 4) | BTN_UP, S9xGetCommandT("Joypad1 Up"), false);
	S9xMapButton(((1) << 4) | BTN_DOWN, S9xGetCommandT("Joypad1 Down"), false);
	S9xMapButton(((1) << 4) | BTN_LEFT, S9xGetCommandT("Joypad1 Left"), false);
	S9xMapButton(((1) << 4) | BTN_RIGHT, S9xGetCommandT("Joypad1 Right"), false);
	S9xMapButton(((1) << 4) | BTN_START, S9xGetCommandT("Joypad1 Start"), false);
	S9xMapButton(((1) << 4) | BTN_SELECT, S9xGetCommandT("Joypad1 Select"), false);

	s9x_last_frame.assign(SNES_WIDTH * MAX_SNES_HEIGHT, 0);
	s9x_initialized = true;
}

// ---------------------------------------------------------------------
// JNI entry points
// ---------------------------------------------------------------------
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeInit(JNIEnv *, jobject)
{
	s9xdeco_init_core();
	return s9x_initialized ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeLoadRom(JNIEnv *env, jobject, jbyteArray romData)
{
	if (!s9x_initialized)
		s9xdeco_init_core();
	if (!s9x_initialized)
		return JNI_FALSE;

	jsize len = env->GetArrayLength(romData);
	std::vector<uint8_t> buf((size_t) len);
	env->GetByteArrayRegion(romData, 0, len, reinterpret_cast<jbyte *>(buf.data()));

	bool ok = Memory.LoadROMMem(buf.data(), (int32_t) buf.size()) != 0;
	if (ok)
	{
		S9xCdlInit(Memory.CalculatedSize);
		S9xCdlSetRecording(true); // capture from the first frame the game runs
		S9xReset();
	}
	s9x_rom_loaded = ok;
	LOGI("nativeLoadRom: %s (%d bytes, calculatedSize=%u)", ok ? "OK" : "FAILED", (int) len, Memory.CalculatedSize);
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeRunFrame(JNIEnv *, jobject)
{
	if (!s9x_rom_loaded)
		return;
	s9x_audio_accum.clear();
	S9xMainLoop();
}

JNIEXPORT jobject JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetVideoFrame(JNIEnv *env, jobject)
{
	jshortArray arr = env->NewShortArray(SNES_WIDTH * s9x_last_frame_height);
	if (arr == nullptr)
		return nullptr;
	env->SetShortArrayRegion(arr, 0, SNES_WIDTH * s9x_last_frame_height,
		reinterpret_cast<jshort *>(s9x_last_frame.data()));
	return arr;
}

JNIEXPORT jint JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetFrameWidth(JNIEnv *, jobject)
{
	return SNES_WIDTH;
}

JNIEXPORT jint JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetFrameHeight(JNIEnv *, jobject)
{
	return s9x_last_frame_height;
}

JNIEXPORT jshortArray JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetAudioSamples(JNIEnv *env, jobject)
{
	jshortArray arr = env->NewShortArray((jsize) s9x_audio_accum.size());
	if (arr == nullptr || s9x_audio_accum.empty())
		return arr;
	env->SetShortArrayRegion(arr, 0, (jsize) s9x_audio_accum.size(),
		reinterpret_cast<jshort *>(s9x_audio_accum.data()));
	return arr;
}

JNIEXPORT void JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeSetButton(JNIEnv *, jobject, jint button, jboolean pressed)
{
	if (!s9x_initialized)
		return;
	S9xReportButton(((1) << 4) | (uint32_t) button, pressed == JNI_TRUE);
}

JNIEXPORT jbyteArray JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetCdlMap(JNIEnv *env, jobject)
{
	size_t size = 0;
	const uint8_t *map = S9xCdlGetMap(&size);
	jbyteArray arr = env->NewByteArray((jsize) size);
	if (arr != nullptr && map != nullptr && size > 0)
		env->SetByteArrayRegion(arr, 0, (jsize) size, reinterpret_cast<const jbyte *>(map));
	return arr;
}

JNIEXPORT void JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeResetCdl(JNIEnv *, jobject)
{
	S9xCdlReset();
}

JNIEXPORT jint JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetCurrentBank(JNIEnv *, jobject)
{
	return S9xCdlGetCurrentBank();
}

JNIEXPORT jint JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetCurrentAddr(JNIEnv *, jobject)
{
	return S9xCdlGetCurrentAddr();
}

JNIEXPORT jint JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetCurrentOffset(JNIEnv *, jobject)
{
	return (jint) S9xCdlGetCurrentOffset();
}

JNIEXPORT jboolean JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeIsRomLoaded(JNIEnv *, jobject)
{
	return s9x_rom_loaded ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------
// CDL recording control + sprite-ripping snapshots (SNES Deco features)
// ---------------------------------------------------------------------

// Toggle whether the CPU dispatch hook records into the CDL map.
JNIEXPORT void JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeSetCdlRecording(JNIEnv *, jobject, jboolean on)
{
	S9xCdlSetRecording(on == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeIsCdlRecording(JNIEnv *, jobject)
{
	return S9xCdlIsRecording() ? JNI_TRUE : JNI_FALSE;
}

// Snapshot the 256-entry CGRAM palette (BGR555, 2 bytes each = 512 bytes).
JNIEXPORT jbyteArray JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetCgram(JNIEnv *env, jobject)
{
	jbyteArray arr = env->NewByteArray(512);
	if (arr == nullptr) return nullptr;
	// PPU.CGDATA is uint16[256]; expose as little-endian bytes.
	uint8_t buf[512];
	for (int i = 0; i < 256; i++)
	{
		buf[i * 2]     = (uint8_t) (PPU.CGDATA[i] & 0xFF);
		buf[i * 2 + 1] = (uint8_t) ((PPU.CGDATA[i] >> 8) & 0xFF);
	}
	env->SetByteArrayRegion(arr, 0, 512, reinterpret_cast<jbyte *>(buf));
	return arr;
}

// Snapshot VRAM (64 KB) - where sprite/BG tile graphics live.
JNIEXPORT jbyteArray JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetVram(JNIEnv *env, jobject)
{
	jbyteArray arr = env->NewByteArray(0x10000);
	if (arr == nullptr) return nullptr;
	env->SetByteArrayRegion(arr, 0, 0x10000, reinterpret_cast<const jbyte *>(Memory.VRAM));
	return arr;
}

// Snapshot the OAM sprite table as a flat int array. For each of the 128
// sprites we emit 8 ints: HPos, VPos, Name(tile), Palette, Priority, HFlip,
// VFlip, Size. The Kotlin side reassembles the actual sprite pixels from
// this + VRAM + CGRAM (the "puzzle assembly" the PPU normally does).
JNIEXPORT jintArray JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetOam(JNIEnv *env, jobject)
{
	const int FIELDS = 8;
	jint tmp[128 * FIELDS];
	for (int i = 0; i < 128; i++)
	{
		const SOBJ &o = PPU.OBJ[i];
		int base = i * FIELDS;
		tmp[base + 0] = (jint) o.HPos;
		tmp[base + 1] = (jint) o.VPos;
		tmp[base + 2] = (jint) o.Name;
		tmp[base + 3] = (jint) o.Palette;
		tmp[base + 4] = (jint) o.Priority;
		tmp[base + 5] = (jint) o.HFlip;
		tmp[base + 6] = (jint) o.VFlip;
		tmp[base + 7] = (jint) o.Size;
	}
	jintArray arr = env->NewIntArray(128 * FIELDS);
	if (arr == nullptr) return nullptr;
	env->SetIntArrayRegion(arr, 0, 128 * FIELDS, tmp);
	return arr;
}

// The base VRAM word-address where OBJ (sprite) tiles begin, and the
// name-select offset - needed to locate a sprite's tile in VRAM.
JNIEXPORT jint JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetObjNameBase(JNIEnv *, jobject)
{
	return (jint) PPU.OBJNameBase;
}

JNIEXPORT jint JNICALL
Java_com_diogo_snesdeco_emu_NativeBridge_nativeGetObjNameSelect(JNIEnv *, jobject)
{
	return (jint) PPU.OBJNameSelect;
}

} // extern "C"
