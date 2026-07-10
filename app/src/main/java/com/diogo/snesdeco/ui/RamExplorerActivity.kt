package com.diogo.snesdeco.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diogo.snesdeco.R
import com.diogo.snesdeco.emu.NativeBridge

/**
 * Cheat-Engine-style RAM search over the SNES's 128 KB work RAM.
 *
 * Flow: type the current value of something (health, coins) and Buscar to get
 * all matching addresses; let it change in game, then filter by new value or
 * by direction (increased/decreased/unchanged). Repeat until few remain, then
 * tap an address to poke a new value. Results refresh live so you watch them
 * change as you play.
 */
class RamExplorerActivity : AppCompatActivity() {

    private lateinit var adapter: ResultAdapter
    private lateinit var resultCount: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ram_explorer)

        val valueInput = findViewById<EditText>(R.id.valueInput)
        resultCount = findViewById(R.id.resultCount)
        val list = findViewById<RecyclerView>(R.id.resultsList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ResultAdapter { addr -> promptPoke(addr) }
        list.adapter = adapter

        findViewById<Button>(R.id.searchButton).setOnClickListener {
            val v = valueInput.text.toString().toIntOrNull()
            if (v == null || v !in 0..255) {
                Toast.makeText(this, "Digite um valor de 0 a 255.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val count = if (NativeBridge.nativeSearchCount() == 0) {
                NativeBridge.nativeSearchReset(v)
            } else {
                NativeBridge.nativeSearchEqual(v)
            }
            updateResults(count)
        }

        findViewById<Button>(R.id.decreasedButton).setOnClickListener { updateResults(NativeBridge.nativeSearchDelta(-1)) }
        findViewById<Button>(R.id.increasedButton).setOnClickListener { updateResults(NativeBridge.nativeSearchDelta(1)) }
        findViewById<Button>(R.id.unchangedButton).setOnClickListener { updateResults(NativeBridge.nativeSearchDelta(0)) }
        findViewById<Button>(R.id.newSearchButton).setOnClickListener {
            NativeBridge.nativeSearchReset(-1) // snapshot all; next Buscar/delta narrows
            resultCount.text = "Busca reiniciada. Digite um valor e Buscar, ou use as setas conforme o jogo muda."
            adapter.submit(IntArray(0))
        }

        startLiveRefresh()
    }

    private fun updateResults(count: Int) {
        if (count > 5000) {
            resultCount.text = "$count endereços — muitos ainda. Deixe o valor mudar no jogo e filtre de novo."
            adapter.submit(IntArray(0))
        } else {
            resultCount.text = "$count endereços candidatos (toque para editar)"
            adapter.submit(NativeBridge.nativeSearchResults(200))
        }
    }

    private fun startLiveRefresh() {
        refreshing = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!refreshing) return
                val count = NativeBridge.nativeSearchCount()
                if (count in 1..5000) {
                    adapter.submit(NativeBridge.nativeSearchResults(200))
                }
                handler.postDelayed(this, 700)
            }
        }, 700)
    }

    override fun onDestroy() {
        refreshing = false
        super.onDestroy()
    }

    private fun promptPoke(addr: Int) {
        val input = EditText(this).apply { hint = "novo valor (0-255)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        android.app.AlertDialog.Builder(this)
            .setTitle("Editar RAM $%05X".format(addr))
            .setView(input)
            .setPositiveButton("Gravar") { _, _ ->
                val nv = input.text.toString().toIntOrNull()
                if (nv != null && nv in 0..255) {
                    NativeBridge.nativeWriteRam(addr, nv)
                    Toast.makeText(this, "Gravado $nv em $%05X".format(addr), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Valor inválido.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private class ResultAdapter(val onClick: (Int) -> Unit) : RecyclerView.Adapter<ResultAdapter.Holder>() {
        private var data = IntArray(0) // [addr, val, addr, val, ...]

        fun submit(newData: IntArray) {
            data = newData
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_disasm_line, parent, false) as TextView
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val addr = data[position * 2]
            val value = data[position * 2 + 1]
            holder.tv.text = "$%05X   =  %3d   (0x%02X)".format(addr, value, value)
            holder.tv.setOnClickListener { onClick(addr) }
        }

        override fun getItemCount(): Int = data.size / 2
        class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)
    }
}
