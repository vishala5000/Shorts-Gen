package com.shortsgen.app.vm

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shortsgen.app.ShortGenEngine
import com.shortsgen.app.model.GenState
import com.shortsgen.app.model.GeneratedVideo
import com.shortsgen.app.util.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ShortsGen/VM"
        private const val MAX_LINES = 200
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L
    }

    private val engine = ShortGenEngine(app)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _state = MutableStateFlow<GenState>(GenState.Idle)
    val state: StateFlow<GenState> = _state.asStateFlow()

    private val _videos = MutableStateFlow<List<GeneratedVideo>>(emptyList())
    val videos: StateFlow<List<GeneratedVideo>> = _videos.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** One-shot user messages (Snackbar). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val isWorking: Boolean get() = _state.value is GenState.Working

    val zipPath: String?
        get() = (_state.value as? GenState.Done)?.zipPath

    // ---------------------------------------------------------------------------------
    // line editing
    // ---------------------------------------------------------------------------------

    fun setSpeed(value: Float) {
        _speed.value = value.coerceIn(0.5f, 2.0f)
    }

    fun addLine(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (_lines.value.size >= MAX_LINES) {
            _message.value = "Maximum of $MAX_LINES lines reached"
            return false
        }
        _lines.value = _lines.value + text
        return true
    }

    /** Splits a pasted script into one video line per non-empty row. */
    fun addLines(raw: String): Int {
        val parts = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return 0
        _lines.value = (_lines.value + parts).take(MAX_LINES)
        return parts.size
    }

    fun removeLine(index: Int) {
        _lines.value = _lines.value.filterIndexed { i, _ -> i != index }
    }

    fun clearLines() {
        _lines.value = emptyList()
        _videos.value = emptyList()
        _state.value = GenState.Idle
    }

    fun dismissMessage() {
        _message.value = null
    }

    // ---------------------------------------------------------------------------------
    // generation
    // ---------------------------------------------------------------------------------

    fun generate() {
        if (isWorking) return
        val lines = _lines.value.toList()
        if (lines.isEmpty()) {
            _message.value = "Add at least one line first"
            return
        }

        val speedValue = _speed.value
        _videos.value = emptyList()
        job?.cancel()
        job = viewModelScope.launch {
            acquireWakeLock(getApplication())
            val startedAt = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    engine.prepare { message, percent ->
                        _state.value = GenState.Working(
                            step = message,
                            currentIndex = 0,
                            total = lines.size,
                            progress = percent / 100f * 0.05f
                        )
                    }
                    engine.beginRun()
                }

                val results = ArrayList<GeneratedVideo>()
                lines.forEachIndexed { position, line ->
                    coroutineContext.ensureActive()
                    val clipNumber = position + 1
                    val video = withContext(Dispatchers.Default) {
                        engine.generateClip(
                            text = line,
                            index = clipNumber,
                            total = lines.size,
                            speed = speedValue,
                            onProgress = { fraction ->
                                _state.value = GenState.Working(
                                    step = "Clip $clipNumber of ${lines.size}: $line",
                                    currentIndex = clipNumber,
                                    total = lines.size,
                                    progress = 0.05f + 0.9f * (position + fraction) / lines.size
                                )
                            },
                            checkCancelled = { coroutineContext.ensureActive() }
                        )
                    }
                    results.add(video)
                    _videos.value = results.toList()
                }

                _state.value = GenState.Working("Packing ZIP…", lines.size, lines.size, 0.97f)
                val zip = withContext(Dispatchers.IO) { engine.buildZip(results, lines) }
                val elapsed = System.currentTimeMillis() - startedAt

                _state.value = GenState.Done(
                    zipPath = zip.absolutePath,
                    videos = results.toList(),
                    totalMs = elapsed
                )
                _message.value = "${results.size} clip${if (results.size == 1) "" else "s"} ready " +
                    "(${elapsed / 1000}s of work)"
            } catch (cancellation: CancellationException) {
                _state.value = GenState.Idle
                _message.value = "Cancelled"
                throw cancellation
            } catch (t: Throwable) {
                Log.e(TAG, "Generation failed", t)
                _state.value = GenState.Failed(t.message ?: t.javaClass.simpleName ?: "Unknown error")
            } finally {
                releaseWakeLock()
                engine.releaseTts()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    // ---------------------------------------------------------------------------------
    // saving / sharing
    // ---------------------------------------------------------------------------------

    fun saveZip() {
        val path = zipPath ?: run {
            _message.value = "Nothing to save yet"
            return
        }
        saveFileCompat(File(path))
    }

    fun saveVideo(video: GeneratedVideo) = saveFileCompat(File(video.path))

    /** Copies [file] into the Download folder (public API for the Activity). */
    fun saveFileCompat(file: File) {
        if (isWorking) {
            _message.value = "Wait until the current run finishes"
            return
        }
        viewModelScope.launch {
            try {
                val location = withContext(Dispatchers.IO) {
                    if (!file.exists()) error("File not found: ${file.name}")
                    Storage.saveToDownloads(getApplication(), file)
                }
                _message.value = "Saved to $location"
            } catch (t: Throwable) {
                Log.e(TAG, "Save failed", t)
                _message.value = "Save failed: ${t.message}"
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShortsGen::render").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onFailure { Log.w(TAG, "WakeLock unavailable", it) }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    override fun onCleared() {
        super.onCleared()
        cancel()
        releaseWakeLock()
        engine.releaseTts()
    }
}
