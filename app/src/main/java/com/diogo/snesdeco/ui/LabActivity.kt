package com.diogo.snesdeco.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diogo.snesdeco.R
import com.diogo.snesdeco.disasm.DisasmLine
import com.diogo.snesdeco.disasm.Disassembler
import com.diogo.snesdeco.disasm.MiniAssembler65816
import com.diogo.snesdeco.emu.CodeRegion
import com.diogo.snesdeco.emu.ExtractionSession
import com.diogo.snesdeco.emu.LabState
import com.diogo.snesdeco.emu.NativeBridge
import com.diogo.snesdeco.rom.RomRepository

class LabActivity : AppCompatActivity() {

    private lateinit var adapter: LineAdapter
    private var lines: List<DisasmLine> = emptyList()
    private var selectedLine: DisasmLine? = null
    private var selectedFileOffset: Int = -1
    private var lastPatch: Pair<Int, ByteArray>? = null // (offset, originalBytes) for undo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)

        val summary = findViewById<TextView>(R.id.labSummary)
        val spinner = findViewById<Spinner>(R.id.regionSpinner)
        val list = findViewById<RecyclerView>(R.id.disasmList)
        val editField = findViewById<EditText>(R.id.editInstruction)

        val regions = LabState.regions
        val totalCode = regions.sumOf { it.length }
        summary.text = "${regions.size} regiões · $totalCode bytes de código · ${ExtractionSession.spriteCount()} sprites capturados"

        list.layoutManager = LinearLayoutManager(this)
        adapter = LineAdapter { line, offset ->
            selectedLine = line
            selectedFileOffset = offset
            editField.setText("${line.mnemonic} ${line.operandText}".trim())
            findViewById<TextView>(R.id.editHint).text =
                "Editando %02X:%04X (offset %06X)".format(line.bank, line.addr, offset)
        }
        list.adapter = adapter

        if (regions.isEmpty()) {
            summary.text = "Nenhum código capturado ainda. Jogue com o Modo Extração ligado, depois abra o Lab."
        } else {
            val labels = regions.mapIndexed { idx, r ->
                "#%d  %02X:%04X  (%d bytes)".format(idx, r.bank, r.addr, r.length)
            }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    showRegion(regions[position])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
            showRegion(regions[0])
        }

        findViewById<Button>(R.id.testButton).setOnClickListener { testPatch(editField) }
        findViewById<Button>(R.id.revertButton).setOnClickListener { revertPatch() }
        findViewById<Button>(R.id.spritesButton).setOnClickListener {
            if (ExtractionSession.spriteCount() == 0) {
                Toast.makeText(this, "Nenhum sprite capturado ainda.", Toast.LENGTH_SHORT).show()
            } else {
                com.diogo.snesdeco.emu.SpriteCapture.groups = ExtractionSession.spritesSnapshot()
                com.diogo.snesdeco.emu.SpriteCapture.cgram = ExtractionSession.palettesSnapshot().firstOrNull()
                startActivity(Intent(this, SpriteViewerActivity::class.java))
            }
        }
    }

    private fun showRegion(region: CodeRegion) {
        val rom = RomRepository.bytes ?: return
        val mapper = RomRepository.mapper() ?: return
        val d = Disassembler(rom, mapper)
        d.resetFlagAssumption()
        // Disassemble roughly the region length in instructions (cap for UI).
        val approxInstr = (region.length / 2).coerceIn(1, 400)
        lines = d.disassembleRange(region.bank, region.addr, approxInstr)
        adapter.submit(lines, mapper)
    }

    private fun testPatch(editField: EditText) {
        val line = selectedLine
        if (line == null || selectedFileOffset < 0) {
            Toast.makeText(this, "Toque numa linha primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val text = editField.text.toString().trim()
        val result = MiniAssembler65816.assembleLine(text)
        if (result.bytes == null) {
            Toast.makeText(this, "Não consegui montar: ${result.error}", Toast.LENGTH_LONG).show()
            return
        }
        val newBytes = result.bytes
        val originalLen = line.bytes.size
        if (newBytes.size != originalLen) {
            Toast.makeText(
                this,
                "A instrução nova tem ${newBytes.size} bytes, a original ${originalLen}. Para testar ao vivo sem quebrar os offsets, use uma instrução do MESMO tamanho (dica: complete com NOP).",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Save original bytes for undo, then patch + reset.
        val original = NativeBridge.nativeReadRom(selectedFileOffset, originalLen)
        val written = NativeBridge.nativePatchRom(selectedFileOffset, newBytes)
        if (written <= 0) {
            Toast.makeText(this, "Falha ao aplicar patch.", Toast.LENGTH_SHORT).show()
            return
        }
        lastPatch = selectedFileOffset to original
        NativeBridge.nativeResetEmu()
        Toast.makeText(
            this,
            "Patch aplicado em %06X e emulador reiniciado. Volte ao jogo para ver o efeito.".format(selectedFileOffset),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun revertPatch() {
        val patch = lastPatch
        if (patch == null) {
            Toast.makeText(this, "Nada para desfazer.", Toast.LENGTH_SHORT).show()
            return
        }
        NativeBridge.nativePatchRom(patch.first, patch.second)
        NativeBridge.nativeResetEmu()
        lastPatch = null
        Toast.makeText(this, "Patch desfeito e emulador reiniciado.", Toast.LENGTH_SHORT).show()
    }

    private class LineAdapter(val onClick: (DisasmLine, Int) -> Unit) :
        RecyclerView.Adapter<LineAdapter.Holder>() {
        private val items = ArrayList<DisasmLine>()
        private var mapper: com.diogo.snesdeco.rom.AddressMapper? = null

        fun submit(newItems: List<DisasmLine>, mapper: com.diogo.snesdeco.rom.AddressMapper) {
            items.clear(); items.addAll(newItems); this.mapper = mapper
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_disasm_line, parent, false) as TextView
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = items[position]
            holder.tv.text = line.raw
            holder.tv.setOnClickListener {
                val off = mapper?.toFileOffset(line.bank, line.addr) ?: -1
                onClick(line, off)
            }
        }

        override fun getItemCount(): Int = items.size
        class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)
    }
}
