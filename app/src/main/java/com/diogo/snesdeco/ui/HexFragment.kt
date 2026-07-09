package com.diogo.snesdeco.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diogo.snesdeco.R
import com.diogo.snesdeco.rom.RomRepository

class HexFragment : Fragment(R.layout.fragment_hex) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.hexRecycler)
        val bytes = RomRepository.bytes
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(true)
        recycler.adapter = HexAdapter(bytes ?: ByteArray(0))
    }

    private class HexAdapter(private val rom: ByteArray) : RecyclerView.Adapter<HexAdapter.Holder>() {
        private val rowCount = (rom.size + 15) / 16

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_hex_line, parent, false) as TextView
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val base = position * 16
            val sb = StringBuilder()
            sb.append("%06X  ".format(base))
            val ascii = StringBuilder()
            for (i in 0 until 16) {
                val idx = base + i
                if (idx < rom.size) {
                    val b = rom[idx].toInt() and 0xFF
                    sb.append("%02X ".format(b))
                    ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
                } else {
                    sb.append("   ")
                    ascii.append(' ')
                }
                if (i == 7) sb.append(' ')
            }
            sb.append(" ").append(ascii)
            holder.textView.text = sb.toString()
        }

        override fun getItemCount(): Int = rowCount

        class Holder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }
}
