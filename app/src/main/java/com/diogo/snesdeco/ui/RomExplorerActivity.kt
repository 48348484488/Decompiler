package com.diogo.snesdeco.ui

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.diogo.snesdeco.R
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class RomExplorerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rom_explorer)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        viewPager.adapter = ExplorerPagerAdapter(this)
        viewPager.offscreenPageLimit = 4

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = ExplorerPagerAdapter.TAB_TITLES[position]
        }.attach()

        findViewById<android.widget.Button>(R.id.playButton).setOnClickListener {
            startActivity(Intent(this, EmulatorActivity::class.java))
        }
    }
}
