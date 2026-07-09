package com.diogo.snesdeco.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diogo.snesdeco.R
import com.diogo.snesdeco.disasm.Disassembler
import com.diogo.snesdeco.rom.RomRepository

class DisasmFragment : Fragment(R.layout.fragment_disasm) {

    private lateinit var adapter: DisasmAdapter
    private lateinit var recycler: RecyclerView
    private var disassembler: Disassembler? = null

    private var currentBank = 0
    private var currentAddr = 0x8000
    private val batchSize = 200

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val info = RomRepository.info
        val bytes = RomRepository.bytes
        val mapper = RomRepository.mapper()

        recycler = view.findViewById(R.id.disasmRecycler)
        adapter = DisasmAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val addrInput = view.findViewById<EditText>(R.id.addrInput)
        view.findViewById<View>(R.id.goButton).setOnClickListener {
            parseAndJump(addrInput.text.toString())
        }
        view.findViewById<View>(R.id.resetVectorButton).setOnClickListener {
            info?.let { jumpTo(startBankFor(it.resetVector), it.resetVector) }
        }
        view.findViewById<View>(R.id.nmiVectorButton).setOnClickListener {
            info?.let { jumpTo(startBankFor(it.nmiVector), it.nmiVector) }
        }
        view.findViewById<View>(R.id.irqVectorButton).setOnClickListener {
            info?.let { jumpTo(startBankFor(it.irqVector), it.irqVector) }
        }

        if (info == null || bytes == null || mapper == null) {
            Toast.makeText(requireContext(), "Nenhuma ROM carregada", Toast.LENGTH_SHORT).show()
            return
        }

        disassembler = Disassembler(bytes, mapper)
        val startAddr = if (info.resetVector in 0..0xFFFF) info.resetVector else 0x8000
        jumpTo(startBankFor(startAddr), startAddr)

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val last = lm.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 10) loadMore()
            }
        })
    }

    // PC bank for LoROM/HiROM is conventionally $00 for the reset vector; we
    // keep it simple and let the user override via the "banco:endereço" field.
    private fun startBankFor(addr: Int): Int = 0x80

    private fun parseAndJump(text: String) {
        val cleaned = text.trim().uppercase()
        try {
            if (cleaned.contains(":")) {
                val (b, a) = cleaned.split(":", limit = 2)
                jumpTo(b.toInt(16), a.toInt(16))
            } else {
                jumpTo(currentBank, cleaned.toInt(16))
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Formato inválido. Use ex: 00:8000", Toast.LENGTH_SHORT).show()
        }
    }

    private fun jumpTo(bank: Int, addr: Int) {
        val d = disassembler ?: return
        currentBank = bank
        currentAddr = addr
        d.resetFlagAssumption()
        val lines = d.disassembleRange(bank, addr, batchSize)
        adapter.replace(lines)
        recycler.scrollToPosition(0)
        val last = lines.lastOrNull()
        if (last != null) {
            currentBank = last.bank
            currentAddr = last.addr + last.bytes.size
        }
    }

    private fun loadMore() {
        val d = disassembler ?: return
        val more = d.disassembleRange(currentBank, currentAddr, batchSize)
        if (more.isEmpty()) return
        adapter.append(more)
        val last = more.last()
        currentBank = last.bank
        currentAddr = last.addr + last.bytes.size
    }
}
