package com.diogo.snesdeco.rom

object RomRepository {
    var info: RomInfo? = null
        private set
    var bytes: ByteArray? = null
        private set

    /** Last palette decoded in the Palette tab, reused as default when viewing tiles. */
    var lastPalette: IntArray? = null

    fun set(result: RomLoader.LoadResult) {
        info = result.info
        bytes = result.cleanBytes
        lastPalette = null
    }

    fun clear() {
        info = null
        bytes = null
        lastPalette = null
    }

    fun mapper(): AddressMapper? {
        val i = info ?: return null
        return AddressMapper(i.mapMode, bytes?.size ?: 0)
    }
}
