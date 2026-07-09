package com.diogo.snesdeco.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object SaveUtils {
    /** Opens an OutputStream for a new file under Download/SNESDeco/[subDir]/[fileName]. */
    fun openDownloadsFile(context: Context, subDir: String, fileName: String, mimeType: String): Pair<OutputStream, String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = "${Environment.DIRECTORY_DOWNLOADS}/SNESDeco/$subDir"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = context.contentResolver.openOutputStream(uri) ?: return null
            stream to "Download/SNESDeco/$subDir/$fileName"
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SNESDeco/$subDir")
            dir.mkdirs()
            val f = File(dir, fileName)
            FileOutputStream(f) to "Download/SNESDeco/$subDir/$fileName"
        }
    }
}
