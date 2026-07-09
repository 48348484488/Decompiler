cmake_minimum_required(VERSION 3.22.1)
project(snesdeco_core)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(S9X_DIR ${CMAKE_CURRENT_SOURCE_DIR}/snes9x)

# Same flag set as snes9x's own official Android/libretro build
# (libretro/jni/Android.mk), so we're compiling this core the same way
# upstream does on Android.
add_compile_definitions(
    ANDROID
    __LIBRETRO__
    HAVE_STRINGS_H
    RIGHTSHIFT_IS_SAR
)

set(S9X_CORE_SOURCES
    ${S9X_DIR}/bsx.cpp
    ${S9X_DIR}/c4.cpp
    ${S9X_DIR}/c4emu.cpp
    ${S9X_DIR}/cheats.cpp
    ${S9X_DIR}/cheats2.cpp
    ${S9X_DIR}/clip.cpp
    ${S9X_DIR}/conffile.cpp
    ${S9X_DIR}/controls.cpp
    ${S9X_DIR}/cpu.cpp
    ${S9X_DIR}/cpuexec.cpp
    ${S9X_DIR}/cpuops.cpp
    ${S9X_DIR}/crosshairs.cpp
    ${S9X_DIR}/dma.cpp
    ${S9X_DIR}/dsp.cpp
    ${S9X_DIR}/dsp1.cpp
    ${S9X_DIR}/dsp2.cpp
    ${S9X_DIR}/dsp3.cpp
    ${S9X_DIR}/dsp4.cpp
    ${S9X_DIR}/fxinst.cpp
    ${S9X_DIR}/fxemu.cpp
    ${S9X_DIR}/gfx.cpp
    ${S9X_DIR}/globals.cpp
    ${S9X_DIR}/loadzip.cpp
    ${S9X_DIR}/memmap.cpp
    ${S9X_DIR}/obc1.cpp
    ${S9X_DIR}/msu1.cpp
    ${S9X_DIR}/ppu.cpp
    ${S9X_DIR}/stream.cpp
    ${S9X_DIR}/sa1.cpp
    ${S9X_DIR}/sa1cpu.cpp
    ${S9X_DIR}/screenshot.cpp
    ${S9X_DIR}/sdd1.cpp
    ${S9X_DIR}/sdd1emu.cpp
    ${S9X_DIR}/seta.cpp
    ${S9X_DIR}/seta010.cpp
    ${S9X_DIR}/seta011.cpp
    ${S9X_DIR}/seta018.cpp
    ${S9X_DIR}/snapshot.cpp
    ${S9X_DIR}/snes9x.cpp
    ${S9X_DIR}/spc7110.cpp
    ${S9X_DIR}/srtc.cpp
    ${S9X_DIR}/tile.cpp
    ${S9X_DIR}/tileimpl-n1x1.cpp
    ${S9X_DIR}/tileimpl-n2x1.cpp
    ${S9X_DIR}/tileimpl-h2x1.cpp
    ${S9X_DIR}/sha256.cpp
    ${S9X_DIR}/bml.cpp
    ${S9X_DIR}/movie.cpp
    ${S9X_DIR}/fscompat.cpp
    ${S9X_DIR}/apu/apu.cpp
    ${S9X_DIR}/apu/bapu/dsp/sdsp.cpp
    ${S9X_DIR}/apu/bapu/smp/smp.cpp
    ${S9X_DIR}/apu/bapu/smp/smp_state.cpp
    ${S9X_DIR}/cdl.cpp
)

add_library(snesdeco_core SHARED
    ${S9X_CORE_SOURCES}
    jni_bridge.cpp
)

target_include_directories(snesdeco_core PRIVATE
    ${S9X_DIR}
    ${S9X_DIR}/apu
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(snesdeco_core ${log-lib})
