package com.diogo.snesdeco.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.diogo.snesdeco.R
import com.diogo.snesdeco.rom.RomLoader
import com.diogo.snesdeco.rom.RomRepository
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var openExplorerButton: Button

    private val pickRomLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadRom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        openExplorerButton = findViewById(R.id.openExplorerButton)

        findViewById<Button>(R.id.pickRomButton).setOnClickListener {
            pickRomLauncher.launch(arrayOf("*/*"))
        }
        openExplorerButton.setOnClickListener {
            startActivity(Intent(this, RomExplorerActivity::class.java))
        }

        findViewById<TextView>(R.id.versionText).text =
            "v${com.diogo.snesdeco.BuildConfig.VERSION_NAME} (code ${com.diogo.snesdeco.BuildConfig.VERSION_CODE})"

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val info = RomRepository.info
        if (info == null) {
            statusText.text = getString(R.string.no_rom_loaded)
            openExplorerButton.visibility = android.view.View.GONE
        } else {
            statusText.text = "\"${info.title}\"\n${info.mapMode.label} · ${info.displaySize} · " +
                (if (info.checksumValid) "checksum OK" else "checksum não bate (normal em ROM hackeada/traduzida)")
            openExplorerButton.visibility = android.view.View.VISIBLE
        }
    }

    private fun loadRom(uri: Uri) {
        statusText.text = "Lendo arquivo..."
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: throw IllegalStateException("Não foi possível abrir o arquivo")

            val name = queryFileName(uri) ?: "rom.sfc"
            val result = RomLoader.load(name, bytes)
            RomRepository.set(result)
            refreshStatus()
            Toast.makeText(this, "ROM carregada: ${result.info.title}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "Erro ao carregar ROM: ${e.message}"
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
