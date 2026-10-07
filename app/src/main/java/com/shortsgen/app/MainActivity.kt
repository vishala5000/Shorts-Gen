package com.shortsgen.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.shortsgen.app.model.GenState
import com.shortsgen.app.ui.LineAdapter
import com.shortsgen.app.ui.VideoAdapter
import com.shortsgen.app.util.Storage
import com.shortsgen.app.vm.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * ShortsGen - type your script line by line, get one 1080x1920 H.264 clip per line
 * (white text on black, spoken by the bundled offline Piper voice) and a ZIP with
 * all of them in your Download folder.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var inputLine: TextInputEditText
    private lateinit var btnAdd: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var linesCount: TextView
    private lateinit var linesList: RecyclerView
    private lateinit var speedSlider: Slider
    private lateinit var speedValue: TextView
    private lateinit var btnGenerate: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var statusText: TextView
    private lateinit var resultsCard: View
    private lateinit var resultText: TextView
    private lateinit var btnSaveZip: MaterialButton
    private lateinit var btnShareZip: MaterialButton
    private lateinit var videosList: RecyclerView

    private lateinit var lineAdapter: LineAdapter
    private lateinit var videoAdapter: VideoAdapter

    private var sliderUpdating = false
    private var pendingSaveAfterPermission: File? = null

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val file = pendingSaveAfterPermission
        pendingSaveAfterPermission = null
        if (granted && file != null) {
            viewModel.saveFileCompat(file)
        } else if (file != null) {
            snack("Storage permission is needed to write into Download on this Android version")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        inputLine = findViewById(R.id.input_line)
        btnAdd = findViewById(R.id.btn_add)
        btnClear = findViewById(R.id.btn_clear)
        linesCount = findViewById(R.id.lines_count)
        linesList = findViewById(R.id.lines_list)
        speedSlider = findViewById(R.id.speed_slider)
        speedValue = findViewById(R.id.speed_value)
        btnGenerate = findViewById(R.id.btn_generate)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        resultsCard = findViewById(R.id.results_card)
        resultText = findViewById(R.id.result_text)
        btnSaveZip = findViewById(R.id.btn_save_zip)
        btnShareZip = findViewById(R.id.btn_share_zip)
        videosList = findViewById(R.id.videos_list)

        lineAdapter = LineAdapter { index -> viewModel.removeLine(index) }
        linesList.layoutManager = LinearLayoutManager(this)
        linesList.adapter = lineAdapter
        linesList.isNestedScrollingEnabled = false

        videoAdapter = VideoAdapter(
            onPlay = { video ->
                val file = File(video.path)
                if (!file.exists()) {
                    snack("That clip is gone (storage was cleaned). Re-generate to get it back.")
                    return@VideoAdapter
                }
                runCatching { startActivity(Storage.viewIntent(this, file)) }
                    .onFailure { snack("No video player found on this device") }
            },
            onSave = { video -> saveWithPermission(File(video.path)) },
            onShare = { video ->
                val file = File(video.path)
                if (file.exists()) {
                    runCatching { startActivity(Storage.shareIntent(this, file)) }
                        .onFailure { snack("Nothing can open this file") }
                }
            }
        )
        videosList.layoutManager = LinearLayoutManager(this)
        videosList.adapter = videoAdapter
        videosList.isNestedScrollingEnabled = false

        btnAdd.setOnClickListener { addCurrentLine() }
        btnClear.setOnClickListener {
            if (viewModel.lines.value.isNotEmpty()) {
                viewModel.clearLines()
                snack("Cleared")
            }
        }
        inputLine.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                addCurrentLine()
                true
            } else {
                false
            }
        }

        speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                sliderUpdating = true
                viewModel.setSpeed(value)
                speedValue.text = formatSpeed(value)
                sliderUpdating = false
            }
        }
        speedValue.text = formatSpeed(speedSlider.value)

        btnGenerate.setOnClickListener {
            if (viewModel.isWorking) {
                viewModel.cancel()
            } else {
                if (viewModel.lines.value.isEmpty()) {
                    snack("Add at least one line first")
                } else {
                    resultsCard.visibility = View.GONE
                    viewModel.generate()
                }
            }
        }
        btnSaveZip.setOnClickListener { saveWithPermission(File(viewModel.zipPath ?: return@setOnClickListener)) }
        btnShareZip.setOnClickListener {
            val path = viewModel.zipPath ?: return@setOnClickListener
            val file = File(path)
            if (file.exists()) {
                runCatching { startActivity(Storage.shareIntent(this, file)) }
                    .onFailure { snack("Nothing can open this file") }
            }
        }

        observeState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import -> {
            showImportDialog()
            true
        }
        R.id.action_about -> {
            showAboutDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------------------------------------------------------------------------------

    private fun addCurrentLine() {
        val text = inputLine.text?.toString().orEmpty()
        if (text.isBlank()) {
            snack("Type a line first")
            return
        }
        if (viewModel.addLine(text)) {
            inputLine.text?.clear()
            inputLine.requestFocus()
        }
    }

    private fun showImportDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.import_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 6
            maxLines = 12
            setPadding(48, 32, 48, 32)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                editText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_action) { _, _ ->
                val added = viewModel.addLines(editText.text?.toString().orEmpty())
                snack(if (added > 0) "Added $added line${if (added == 1) "" else "s"}" else "Nothing to add")
            }
            .show()
    }

    private fun showAboutDialog() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_message, version))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun saveWithPermission(file: File) {
        if (!file.exists()) {
            snack("That file is gone (storage was cleaned). Re-generate to get it back.")
            return
        }
        if (Storage.needsLegacyWritePermission() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveAfterPermission = file
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        viewModel.saveFileCompat(file)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.lines.collect { list ->
                        lineAdapter.submit(list)
                        linesCount.text = resources.getQuantityString(
                            R.plurals.lines_count, list.size, list.size
                        )
                        val working = viewModel.isWorking
                        btnAdd.isEnabled = !working
                        btnClear.isEnabled = !working && list.isNotEmpty()
                        inputLine.isEnabled = !working
                        btnGenerate.isEnabled = !working || list.isNotEmpty()
                    }
                }
                launch {
                    viewModel.speed.collect { value ->
                        if (!sliderUpdating && Math.abs(speedSlider.value - value) > 0.001f) {
                            speedSlider.value = value
                        }
                        speedValue.text = formatSpeed(value)
                    }
                }
                launch {
                    viewModel.videos.collect { list -> videoAdapter.submit(list) }
                }
                launch {
                    viewModel.state.collect { renderState(it) }
                }
                launch {
                    viewModel.message.collect { text ->
                        if (!text.isNullOrBlank()) {
                            snack(text)
                            viewModel.dismissMessage()
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: GenState) {
        when (state) {
            is GenState.Idle -> {
                progress.visibility = View.GONE
                statusText.visibility = View.GONE
                btnGenerate.text = getString(R.string.generate)
                btnGenerate.isEnabled = viewModel.lines.value.isNotEmpty()
                btnAdd.isEnabled = true
                btnClear.isEnabled = viewModel.lines.value.isNotEmpty()
                inputLine.isEnabled = true
                speedSlider.isEnabled = true
            }

            is GenState.Working -> {
                progress.visibility = View.VISIBLE
                statusText.visibility = View.VISIBLE
                progress.isIndeterminate = false
                progress.progress = (state.progress.coerceIn(0f, 1f) * 100).toInt()
                statusText.text = if (state.total > 0) {
                    getString(R.string.status_working, state.step, state.currentIndex, state.total)
                } else {
                    state.step
                }
                btnGenerate.text = getString(R.string.cancel)
                btnGenerate.isEnabled = true
                btnAdd.isEnabled = false
                btnClear.isEnabled = false
                inputLine.isEnabled = false
                speedSlider.isEnabled = false
                btnSaveZip.isEnabled = false
                btnShareZip.isEnabled = false
            }

            is GenState.Done -> {
                progress.visibility = View.GONE
                statusText.visibility = View.GONE
                btnGenerate.text = getString(R.string.generate)
                btnGenerate.isEnabled = viewModel.lines.value.isNotEmpty()
                btnAdd.isEnabled = true
                btnClear.isEnabled = true
                inputLine.isEnabled = true
                speedSlider.isEnabled = true
                resultsCard.visibility = View.VISIBLE

                val zipFile = File(state.zipPath)
                val seconds = state.videos.sumOf { it.durationMs } / 1000f
                resultText.text = getString(
                    R.string.result_summary,
                    state.videos.size,
                    seconds,
                    zipFile.length() / 1_048_576f,
                    zipFile.name,
                    state.totalMs / 1000f
                )
                val zipReady = zipFile.exists()
                btnSaveZip.isEnabled = zipReady
                btnShareZip.isEnabled = zipReady
            }

            is GenState.Failed -> {
                progress.visibility = View.GONE
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_failed, state.message)
                btnGenerate.text = getString(R.string.generate)
                btnGenerate.isEnabled = viewModel.lines.value.isNotEmpty()
                btnAdd.isEnabled = true
                btnClear.isEnabled = true
                inputLine.isEnabled = true
                speedSlider.isEnabled = true
            }
        }
    }

    private fun formatSpeed(value: Float): String =
        String.format(Locale.US, "%.2fx speed", value)

    private fun snack(text: String) {
        val view = findViewById<View>(android.R.id.content)
        Snackbar.make(view, text, Snackbar.LENGTH_LONG).show()
    }
}
