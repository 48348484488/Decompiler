package com.diogo.snesdeco.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.diogo.snesdeco.R
import com.diogo.snesdeco.rom.RomRepository

class InfoFragment : Fragment(R.layout.fragment_info) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = view.findViewById<LinearLayout>(R.id.infoContainer)
        val info = RomRepository.info

        if (info == null) {
            container.addView(rowLabel("Nenhuma ROM carregada. Volte e escolha um arquivo .sfc/.smc."))
            return
        }

        addRow(container, "Arquivo", info.fileName)
        addRow(container, "Título (header)", info.title.ifBlank { "(vazio)" })
        addRow(container, "Modo de mapeamento", info.mapMode.label + if (info.fastRom) " · FastROM" else " · SlowROM")
        addRow(container, "Tamanho da ROM", info.displaySize)
        addRow(container, "Chip / cartucho", info.cartridgeTypeLabel)
        addRow(container, "RAM do cartucho", if (info.ramSizeKb > 0) "${info.ramSizeKb} KB" else "nenhuma")
        addRow(container, "Região", info.destinationLabel)
        addRow(container, "Versão da ROM", "1.${info.version}")
        addRow(container, "Checksum", "0x%04X".format(info.checksum))
        addRow(container, "Complemento", "0x%04X".format(info.checksumComplement))
        addRow(container, "Checksum válido", if (info.checksumValid) "sim ✓" else "não (comum em hacks/traduções/protótipos)")
        addRow(container, "Header de copiadora (512B)", if (info.copierHeaderPresent) "presente (removido na análise)" else "ausente")
        addRow(container, "Offset do header interno", "0x%04X".format(info.headerFileOffset))
        addRow(container, "Vetor RESET", if (info.resetVector >= 0) "$%04X".format(info.resetVector) else "?")
        addRow(container, "Vetor NMI", if (info.nmiVector >= 0) "$%04X".format(info.nmiVector) else "?")
        addRow(container, "Vetor IRQ", if (info.irqVector >= 0) "$%04X".format(info.irqVector) else "?")
    }

    private fun addRow(container: LinearLayout, label: String, value: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 22)
        }
        val labelView = TextView(requireContext()).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#9A9FAE"))
        }
        val valueView = TextView(requireContext()).apply {
            text = value
            textSize = 16f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#F2F2F7"))
        }
        row.addView(labelView)
        row.addView(valueView)
        container.addView(row)
    }

    private fun rowLabel(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(Color.parseColor("#9A9FAE"))
            textSize = 14f
        }
    }
}
