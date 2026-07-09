package com.diogo.snesdeco.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diogo.snesdeco.R
import com.diogo.snesdeco.gfx.PaletteDecoder
import com.diogo.snesdeco.rom.RomRepository

class PaletteFragment : Fragment(R.layout.fragment_palette) {

    private lateinit var adapter: SwatchAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.paletteRecycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), 8)
        adapter = SwatchAdapter { index, argb ->
            Toast.makeText(requireContext(), "Cor #$index: #%06X".format(argb and 0xFFFFFF), Toast.LENGTH_SHORT).show()
        }
        recycler.adapter = adapter

        val offsetInput = view.findViewById<EditText>(R.id.paletteOffsetInput)
        val countInput = view.findViewById<EditText>(R.id.paletteCountInput)

        view.findViewById<View>(R.id.paletteDecodeButton).setOnClickListener {
            decode(offsetInput.text.toString(), countInput.text.toString())
        }

        // Sensible default so the tab isn't empty on first visit.
        offsetInput.setText("0")
        decode("0", "16")
    }

    private fun decode(offsetText: String, countText: String) {
        val bytes = RomRepository.bytes
        if (bytes == null) {
            Toast.makeText(requireContext(), "Nenhuma ROM carregada", Toast.LENGTH_SHORT).show()
            return
        }
        val offset = try { offsetText.trim().removePrefix("0X").removePrefix("0x").toInt(16) } catch (e: Exception) { 0 }
        val count = countText.trim().toIntOrNull()?.coerceIn(1, 256) ?: 16

        val palette = PaletteDecoder.decodePalette(bytes, offset, count)
        RomRepository.lastPalette = palette
        adapter.submit(palette)
    }

    private class SwatchAdapter(val onClick: (Int, Int) -> Unit) : RecyclerView.Adapter<SwatchAdapter.Holder>() {
        private var colors = IntArray(0)

        fun submit(newColors: IntArray) {
            colors = newColors
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_palette_swatch, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val color = colors[position]
            holder.swatch.setBackgroundColor(color)
            holder.label.text = "%02X".format(position)
            holder.itemView.setOnClickListener { onClick(position, color) }
        }

        override fun getItemCount(): Int = colors.size

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val swatch: View = view.findViewById(R.id.swatchView)
            val label: TextView = view.findViewById(R.id.swatchLabel)
        }
    }
}
