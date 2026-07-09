package com.diogo.snesdeco.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diogo.snesdeco.R
import com.diogo.snesdeco.emu.SpriteCapture
import com.diogo.snesdeco.emu.SpriteRipper

class SpriteViewerActivity : AppCompatActivity() {

    private var selected: SpriteRipper.SpriteGroup? = null
    private lateinit var previewImage: ImageView
    private lateinit var previewInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sprite_viewer)

        previewImage = findViewById(R.id.previewImage)
        previewInfo = findViewById(R.id.previewInfo)
        val grid = findViewById<RecyclerView>(R.id.spriteGrid)
        val title = findViewById<TextView>(R.id.viewerTitle)

        val groups = SpriteCapture.groups
        title.text = "${groups.size} sprites montados na cena (toque para ampliar)"

        grid.layoutManager = GridLayoutManager(this, 4)
        grid.adapter = ThumbAdapter(groups) { g -> select(g) }

        if (groups.isNotEmpty()) select(groups[0])

        findViewById<Button>(R.id.saveOneButton).setOnClickListener {
            val g = selected ?: return@setOnClickListener
            val subDir = "sprites_${System.currentTimeMillis()}"
            val ok = saveGroup(g, subDir)
            Toast.makeText(this, if (ok) "Salvo em Download/SNESDeco/$subDir/" else "Erro ao salvar", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.saveAllButton).setOnClickListener {
            val subDir = "sprites_${System.currentTimeMillis()}"
            var saved = 0
            for (g in groups) if (saveGroup(g, subDir)) saved++
            SpriteCapture.cgram?.let { savePalette(it, subDir) }
            Toast.makeText(this, "$saved sprites + paleta em Download/SNESDeco/$subDir/", Toast.LENGTH_LONG).show()
        }
    }

    private fun select(g: SpriteRipper.SpriteGroup) {
        selected = g
        val bmp = toBitmap(g)
        val scale = (480 / maxOf(g.widthPx, g.heightPx)).coerceIn(1, 8)
        previewImage.setImageBitmap(Bitmap.createScaledBitmap(bmp, g.widthPx * scale, g.heightPx * scale, false))
        previewInfo.text = "sprite #%d  %dx%dpx  pos tela (%d,%d)  %d peças OAM".format(
            g.id, g.widthPx, g.heightPx, g.screenX, g.screenY, g.memberCount
        )
    }

    private fun toBitmap(g: SpriteRipper.SpriteGroup): Bitmap {
        val bmp = Bitmap.createBitmap(g.widthPx, g.heightPx, Bitmap.Config.ARGB_8888)
        bmp.setPixels(g.argb, 0, g.widthPx, 0, 0, g.widthPx, g.heightPx)
        return bmp
    }

    private fun saveGroup(g: SpriteRipper.SpriteGroup, subDir: String): Boolean {
        return try {
            val bmp = toBitmap(g)
            val big = Bitmap.createScaledBitmap(bmp, g.widthPx * 4, g.heightPx * 4, false)
            val fileName = "sprite_%02d_%dx%d.png".format(g.id, g.widthPx, g.heightPx)
            val (stream, _) = SaveUtils.openDownloadsFile(this, subDir, fileName, "image/png") ?: return false
            stream.use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun savePalette(cgram: ByteArray, subDir: String) {
        try {
            val cell = 16
            val bmp = Bitmap.createBitmap(16 * cell, 16 * cell, Bitmap.Config.ARGB_8888)
            for (i in 0 until 256) {
                val lo = cgram[i * 2].toInt() and 0xFF
                val hi = cgram[i * 2 + 1].toInt() and 0xFF
                val word = lo or (hi shl 8)
                val r = ((word and 0x1F) * 255) / 31
                val gr = (((word shr 5) and 0x1F) * 255) / 31
                val b = (((word shr 10) and 0x1F) * 255) / 31
                val argb = (0xFF shl 24) or (r shl 16) or (gr shl 8) or b
                val cx = (i % 16) * cell
                val cy = (i / 16) * cell
                for (y in 0 until cell) for (x in 0 until cell) bmp.setPixel(cx + x, cy + y, argb)
            }
            val (stream, _) = SaveUtils.openDownloadsFile(this, subDir, "palette_cgram.png", "image/png") ?: return
            stream.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) { }
    }

    private inner class ThumbAdapter(
        val groups: List<SpriteRipper.SpriteGroup>,
        val onClick: (SpriteRipper.SpriteGroup) -> Unit
    ) : RecyclerView.Adapter<ThumbAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sprite_thumb, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val g = groups[position]
            holder.image.setImageBitmap(toBitmap(g))
            holder.label.text = "#%d %dx%d".format(g.id, g.widthPx, g.heightPx)
            holder.itemView.setOnClickListener { onClick(g) }
        }

        override fun getItemCount(): Int = groups.size

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.thumbImage)
            val label: TextView = view.findViewById(R.id.thumbLabel)
        }
    }
}
