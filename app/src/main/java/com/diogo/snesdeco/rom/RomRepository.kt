package com.diogo.snesdeco.rom

object RomRepository {
    var info: RomInfo? = null
        private set
    var bytes: ByteArray? = null
        private set

    /** Last palette decoded in the Palette tab, reused as default when viewing tiles. */
    var lastPalette: IntArray? = null

    /** Code/Data Logger map accumulated by the emulator core during a play session. */
    var cdlMap: ByteArray? = null

    fun set(result: RomLoader.LoadResult) {
        info = result.info
        bytes = result.cleanBytes
        lastPalette = null
        cdlMap = null
    }

    fun clear() {
        info = null
        bytes = null
        lastPalette = null
        cdlMap = null
    }

    fun mapper(): AddressMapper? {
        val i = info ?: return null
        return AddressMapper(i.mapMode, bytes?.size ?: 0)
    }
}
