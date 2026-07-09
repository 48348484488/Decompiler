package com.diogo.snesdeco.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diogo.snesdeco.R
import com.diogo.snesdeco.gfx.TileDecoder
import com.diogo.snesdeco.rom.RomRepository

class TilesFragment : Fragment(R.layout.fragment_tiles) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val offsetInput = view.findViewById<EditText>(R.id.tileOffsetInput)
        val countInput = view.findViewById<EditText>(R.id.tileCountInput)
        val bppGroup = view.findViewById<RadioGroup>(R.id.bppGroup)
        val imageView = view.findViewById<ImageView>(R.id.tileImageView)

        view.findViewById<View>(R.id.tileDecodeButton).setOnClickListener {
            val bpp = when (bppGroup.checkedRadioButtonId) {
                R.id.bpp2Radio -> 2
                R.id.bpp8Radio -> 8
                else -> 4
            }
            decode(offsetInput.text.toString(), countInput.text.toString(), bpp, imageView)
        }

        offsetInput.setText("0")
        decode("0", "256", 4, imageView)
    }

    private fun decode(offsetText: String, countText: String, bpp: Int, imageView: ImageView) {
        val bytes = RomRepository.bytes
        if (bytes == null) {
            Toast.makeText(requireContext(), "Nenhuma ROM carregada", Toast.LENGTH_SHORT).show()
            return
        }
        val offset = try { offsetText.trim().removePrefix("0X").removePrefix("0x").toInt(16) } catch (e: Exception) { 0 }
        val count = countText.trim().toIntOrNull()?.coerceIn(1, 1024) ?: 256
        val cols = 16

        // Reuse the palette decoded in the Paleta tab; fall back to grayscale
        // so tiles are still visible before the user has picked a palette.
        val palette = RomRepository.lastPalette ?: IntArray(1 shl bpp) { i ->
            val v = (i * 255) / ((1 shl bpp) - 1).coerceAtLeast(1)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val pixels = TileDecoder.decodeTileSheet(bytes, offset, bpp, count, cols, palette)
        val rows = (count + cols - 1) / cols
        val w = cols * 8
        val h = rows * 8
        if (w <= 0 || h <= 0) return

        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val scale = 4
        val scaled = Bitmap.createScaledBitmap(bitmap, w * scale, h * scale, false)
        imageView.setImageBitmap(scaled)
    }
}
