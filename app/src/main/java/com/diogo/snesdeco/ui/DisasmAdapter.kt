package com.diogo.snesdeco.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.diogo.snesdeco.R
import com.diogo.snesdeco.disasm.DisasmLine

class DisasmAdapter : RecyclerView.Adapter<DisasmAdapter.LineHolder>() {

    private val lines = ArrayList<DisasmLine>()

    fun replace(newLines: List<DisasmLine>) {
        lines.clear()
        lines.addAll(newLines)
        notifyDataSetChanged()
    }

    fun append(moreLines: List<DisasmLine>) {
        val start = lines.size
        lines.addAll(moreLines)
        notifyItemRangeInserted(start, moreLines.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_disasm_line, parent, false) as TextView
        return LineHolder(view)
    }

    override fun onBindViewHolder(holder: LineHolder, position: Int) {
        holder.textView.text = lines[position].raw
    }

    override fun getItemCount(): Int = lines.size

    class LineHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
