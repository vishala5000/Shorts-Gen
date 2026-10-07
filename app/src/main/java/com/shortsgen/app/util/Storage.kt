package com.shortsgen.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Copies finished files into the user's Download folder (and shares them). */
object Storage {

    const val SUB_DIR = "ShortsGen"

    fun mimeFor(file: File): String = when {
        file.name.endsWith(".zip") -> "application/zip"
        file.name.endsWith(".mp4") -> "video/mp4"
        file.name.endsWith(".txt") -> "text/plain"
        else -> "application/octet-stream"
    }

    /** True on Android 9 and older, where writing to /Download needs a runtime permission. */
    fun needsLegacyWritePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * @return a human readable location, e.g. `Download/ShortsGen/clip.zip`
     */
    fun saveToDownloads(context: Context, source: File): String {
        val mime = mimeFor(source)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create an entry in Downloads")

            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
            } ?: error("Could not open the Downloads output stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR/${source.name}"
        }

        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR)
        dir.mkdirs()
        val target = File(dir, source.name)
        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf(mime), null
        )
        return "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR/${source.name}"
    }

    /** A content:// uri that can be handed to another app (player, Files, chat…). */
    fun shareUri(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )

    fun shareIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(file)
            putExtra(Intent.EXTRA_STREAM, shareUri(context, file))
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun viewIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shareUri(context, file), mimeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
