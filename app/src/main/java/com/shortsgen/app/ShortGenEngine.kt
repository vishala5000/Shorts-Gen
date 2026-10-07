package com.shortsgen.app

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import com.shortsgen.app.model.GeneratedVideo
import com.shortsgen.app.tts.TtsEngine
import com.shortsgen.app.util.Zip
import com.shortsgen.app.video.VideoComposer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Glue between the offline TTS engine and the video composer.
 * One instance per run: it owns the (heavy) voice model and the output folders.
 */
class ShortGenEngine(private val context: Context) {

    companion object {
        private const val TAG = "ShortsGen/Engine"
        private const val FONT_ASSET = "font.ttf"
    }

    private val tts = TtsEngine(context)
    private var typeface: Typeface? = null

    private val storageRoot: File =
        context.getExternalFilesDir(null) ?: File(context.filesDir, "shared")

    val videosDir: File get() = File(storageRoot, "videos")
    val zipsDir: File get() = File(storageRoot, "zips")

    /** Unpacks the voice data and loads the model + font. Blocking. */
    fun prepare(onProgress: (message: String, percent: Int) -> Unit) {
        if (typeface == null) {
            typeface = Typeface.createFromAsset(context.assets, FONT_ASSET)
        }
        tts.prepare(onProgress)
    }

    /** Wipes the output of a previous run. */
    fun beginRun() {
        videosDir.deleteRecursively()
        videosDir.mkdirs()
        zipsDir.mkdirs()
    }

    /**
     * Speaks [text] and renders the matching 1080x1920 clip.
     * Blocking / CPU heavy -> call from a background thread.
     */
    fun generateClip(
        text: String,
        index: Int,
        total: Int,
        speed: Float,
        onProgress: (Float) -> Unit,
        checkCancelled: () -> Unit
    ): GeneratedVideo {
        val face = typeface ?: throw IllegalStateException("Font is not loaded yet")
        checkCancelled()

        val startedAt = System.currentTimeMillis()
        val (samples, sampleRate) = tts.synthesize(text, speed)
        Log.i(
            TAG,
            "TTS line $index/$total: ${samples.size} samples @${sampleRate}Hz " +
                "(${samples.size * 1000L / sampleRate} ms) in ${System.currentTimeMillis() - startedAt} ms"
        )

        val outFile = File(videosDir, "line_%02d.mp4".format(Locale.US, index))
        val composer = VideoComposer(face)
        val durationUs = composer.compose(
            text = text,
            samples = samples,
            sampleRate = sampleRate,
            outFile = outFile,
            onProgress = onProgress,
            checkCancelled = checkCancelled
        )

        return GeneratedVideo(
            index = index,
            line = text,
            path = outFile.absolutePath,
            sizeBytes = outFile.length(),
            durationMs = durationUs / 1000L
        )
    }

    /** Zips every clip (plus a lines.txt manifest) into Download-ready archive. */
    fun buildZip(videos: List<GeneratedVideo>, lines: List<String>): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(zipsDir, "ShortsGen_$stamp.zip")

        val manifest = File(zipsDir, "lines.txt")
        manifest.parentFile?.mkdirs()
        manifest.writeText(buildString {
            appendLine("# ShortsGen - one video per line")
            appendLine("# ${videos.size} clips, generated ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            videos.forEachIndexed { i, video ->
                appendLine()
                appendLine("[${i + 1}] ${video.fileName}  (${video.durationMs} ms)")
                appendLine(lines.getOrElse(video.index - 1) { video.line })
            }
        })

        val entries = ArrayList<Pair<String, File>>()
        videos.sortedBy { it.index }.forEach { entries.add(it.fileName to File(it.path)) }
        entries.add("lines.txt" to manifest)

        Zip.create(entries, zipFile)
        Log.i(TAG, "ZIP ready: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        return zipFile
    }

    /** Frees the native model (~200 MB of RAM). The unpacked voice data stays on disk. */
    fun releaseTts() {
        tts.release()
    }
}
