package com.shortsgen.app.util

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Zip {

    /**
     * Packs [entries] (display name -> file) into [outFile].
     *
     * MP4 data is already compressed, so deflate is run at level 1: it keeps the
     * container valid for every unzip tool while staying fast.
     */
    fun create(entries: List<Pair<String, File>>, outFile: File): File {
        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile), 256 * 1024)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            for ((name, file) in entries) {
                if (!file.exists()) continue
                zip.putNextEntry(ZipEntry(name))
                BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
                    input.copyTo(zip, 256 * 1024)
                }
                zip.closeEntry()
            }
        }
        return outFile
    }
}
