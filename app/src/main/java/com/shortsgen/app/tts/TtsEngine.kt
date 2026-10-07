package com.shortsgen.app.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.IOException

/**
 * Offline text-to-speech based on the Piper `en_US-ljspeech-medium` VITS model,
 * running through sherpa-onnx (ONNX Runtime + espeak-ng). Everything is bundled
 * inside the APK, nothing is downloaded at runtime.
 *
 * The `.onnx` model / `tokens.txt` are read straight from the APK assets through the
 * AssetManager. `espeak-ng-data` however is opened with plain `fopen()` inside native
 * code, so it has to be unpacked to the app's private storage once (see [prepare]).
 */
class TtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "ShortsGen/TTS"

        /** Name of the model folder inside app/src/main/assets */
        const val MODEL_DIR = "vits-piper-en_US-ljspeech-medium"
        const val MODEL_FILE = "en_US-ljspeech-medium.onnx"
        const val MODEL_JSON = "en_US-ljspeech-medium.onnx.json"
        const val TOKENS_FILE = "tokens.txt"
        const val ESPEAK_DATA_DIR = "espeak-ng-data"

        /** Bump this whenever the bundled voice data changes. */
        private const val VOICE_DATA_VERSION = "1"

        // Values taken from en_US-ljspeech-medium.onnx.json ("inference" section).
        private const val NOISE_SCALE = 0.667f
        private const val NOISE_SCALE_W = 0.333f
        private const val LENGTH_SCALE = 1.0f
        private const val NUM_THREADS = 2
    }

    private var tts: OfflineTts? = null

    /** Sample rate of the generated PCM (22050 Hz for this model). */
    var sampleRate: Int = 22050
        private set

    val isReady: Boolean get() = tts != null

    /**
     * Unpacks espeak-ng-data (only the first time) and creates the synthesizer.
     * Blocking + CPU heavy: call from a background thread.
     */
    fun prepare(onProgress: (message: String, percent: Int) -> Unit = { _, _ -> }) {
        tts?.let { return }

        onProgress("Unpacking voice data…", 0)
        val espeakDataDir = ensureEspeakData(onProgress)

        onProgress("Loading voice model (63 MB)…", 90)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$MODEL_DIR/$MODEL_FILE",
                    lexicon = "",
                    tokens = "$MODEL_DIR/$TOKENS_FILE",
                    dataDir = espeakDataDir.absolutePath,
                    dictDir = "",
                    noiseScale = NOISE_SCALE,
                    noiseScaleW = NOISE_SCALE_W,
                    lengthScale = LENGTH_SCALE
                ),
                numThreads = NUM_THREADS,
                debug = false,
                provider = "cpu"
            ),
            ruleFsts = "",
            ruleFars = "",
            maxNumSentences = 1,
            silenceScale = 0.2f
        )

        val engine = OfflineTts(assetManager = context.assets, config = config)
        sampleRate = engine.sampleRate()
        tts = engine
        Log.i(TAG, "TTS ready, sampleRate=$sampleRate speakers=${engine.numSpeakers()}")
        onProgress("Voice model ready", 100)
    }

    /**
     * Synthesizes [text] and returns mono float PCM in [-1, 1] plus the sample rate.
     */
    fun synthesize(text: String, speed: Float = 1.0f): Pair<FloatArray, Int> {
        val engine = tts ?: throw IllegalStateException("TTS engine is not initialised")
        val audio: GeneratedAudio = engine.generate(text = text, sid = 0, speed = speed)
        if (audio.samples.isEmpty()) {
            throw IOException("The voice model produced no audio for this line")
        }
        return audio.samples to audio.sampleRate
    }

    fun release() {
        try {
            tts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed", t)
        }
        tts = null
    }

    // ---------------------------------------------------------------------------------
    // espeak-ng-data handling
    // ---------------------------------------------------------------------------------

    private fun ensureEspeakData(onProgress: (String, Int) -> Unit): File {
        val root = File(context.filesDir, MODEL_DIR)
        val target = File(root, ESPEAK_DATA_DIR)
        val marker = File(root, "$ESPEAK_DATA_DIR.version")

        if (marker.exists() &&
            marker.readText().trim() == VOICE_DATA_VERSION &&
            File(target, "phontab").exists() &&
            File(target, "phondata").exists()
        ) {
            onProgress("Voice data already unpacked", 85)
            return target
        }

        val assetRoot = "$MODEL_DIR/$ESPEAK_DATA_DIR"
        val entries = listAssetFiles(assetRoot)
        if (entries.isEmpty()) {
            throw IOException("assets/$assetRoot is missing - run scripts/prepare_assets.sh")
        }

        target.mkdirs()
        var done = 0
        for (assetPath in entries) {
            val relative = assetPath.substring(assetRoot.length + 1)
            val out = File(target, relative)
            out.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            done++
            if (done % 25 == 0 || done == entries.size) {
                onProgress("Unpacking voice data… $done/${entries.size}", 85 * done / entries.size)
            }
        }
        marker.parentFile?.mkdirs()
        marker.writeText(VOICE_DATA_VERSION)
        Log.i(TAG, "Unpacked $done espeak-ng files into ${target.absolutePath}")
        return target
    }

    /** Depth-first listing of every *file* below [path] inside the APK assets. */
    private fun listAssetFiles(path: String): List<String> {
        val children = context.assets.list(path) ?: return emptyList()
        if (children.isEmpty()) return listOf(path)
        val result = ArrayList<String>()
        for (child in children.sorted()) {
            result.addAll(listAssetFiles("$path/$child"))
        }
        return result
    }
}
