package com.diogo.snesdeco.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ExplorerPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    companion object {
        val TAB_TITLES = listOf("Info", "Disasm", "Hex", "Paleta", "Tiles")
    }

    override fun getItemCount(): Int = TAB_TITLES.size

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> InfoFragment()
        1 -> DisasmFragment()
        2 -> HexFragment()
        3 -> PaletteFragment()
        4 -> TilesFragment()
        else -> InfoFragment()
    }
}
