package io.github.phoenix1o1.uvbridge

import android.app.Activity
import android.content.ContentValues
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast

/** Saves files the page offers for download into the public Downloads folder. */
class FileSaver(private val activity: Activity) {

    fun save(name: String, bytes: ByteArray, onDone: () -> Unit, onError: (String) -> Unit) {
        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "download.bin" }
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(safeName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("cannot create the file")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            Toast.makeText(activity, activity.getString(R.string.saved, safeName), Toast.LENGTH_LONG).show()
            onDone()
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.save_failed, e.message), Toast.LENGTH_LONG).show()
            onError(e.message ?: e.toString())
        }
    }
}
