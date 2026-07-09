package com.diogo.snesdeco.emu

object NativeBridge {
    init {
        System.loadLibrary("snesdeco_core")
    }

    external fun nativeInit(): Boolean
    external fun nativeLoadRom(romData: ByteArray): Boolean
    external fun nativeRunFrame()
    external fun nativeGetVideoFrame(): ShortArray
    external fun nativeGetFrameWidth(): Int
    external fun nativeGetFrameHeight(): Int
    external fun nativeGetAudioSamples(): ShortArray
    external fun nativeSetButton(button: Int, pressed: Boolean)
    external fun nativeGetCdlMap(): ByteArray
    external fun nativeResetCdl()
    external fun nativeGetCurrentBank(): Int
    external fun nativeGetCurrentAddr(): Int
    external fun nativeGetCurrentOffset(): Int
    external fun nativeIsRomLoaded(): Boolean

    // CDL recording control + sprite-ripping snapshots
    external fun nativeSetCdlRecording(on: Boolean)
    external fun nativeIsCdlRecording(): Boolean
    // Sandbox: freeze at the boundary of the captured slice
    external fun nativeSetSandbox(on: Boolean)
    external fun nativeIsSandbox(): Boolean
    external fun nativeBoundaryHit(): Boolean
    external fun nativeClearBoundary()
    external fun nativeBoundaryOffset(): Int
    external fun nativeGetCgram(): ByteArray
    external fun nativeGetVram(): ByteArray
    external fun nativeGetOam(): IntArray
    external fun nativeGetObjNameBase(): Int
    external fun nativeGetObjNameSelect(): Int
    external fun nativeGetObjSizeSelect(): Int
    external fun nativeReadRom(offset: Int, length: Int): ByteArray
    external fun nativePatchRom(offset: Int, data: ByteArray): Int
    external fun nativeResetEmu()

    // Mirrors bridge.h's S9xDecoButton enum on the native side.
    object Button {
        const val B = 0
        const val Y = 1
        const val SELECT = 2
        const val START = 3
        const val UP = 4
        const val DOWN = 5
        const val LEFT = 6
        const val RIGHT = 7
        const val A = 8
        const val X = 9
        const val L = 10
        const val R = 11
    }
}
