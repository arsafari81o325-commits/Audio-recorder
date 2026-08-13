package com.example.audiorecorder

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: Button
    private lateinit var btnPauseResume: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var waveform: WaveformView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecordingAdapter
    private lateinit var repository: RecordingRepository
    private lateinit var preferences: PreferencesManager
    private lateinit var permissionHelper: PermissionHelper

    private val viewModel: RecordingListViewModel by viewModels()

    private var mediaPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    private var pendingResultCode: Int = -1
    private var pendingProjectionData: Intent? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            pendingResultCode = result.resultCode
            pendingProjectionData = result.data
            checkNotificationAndStartService()
        } else {
            Toast.makeText(
                this,
                "مجوز ضبط صفحه لازم است (کد: ${result.resultCode}, data: ${result.data})",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        showLastCrashIfAny()

        repository = (application as AudioRecorderApp).repository
        preferences = (application as AudioRecorderApp).preferences
        permissionHelper = PermissionHelper(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        btnRecord = findViewById(R.id.btnRecord)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        tvStatus = findViewById(R.id.tvStatus)
        tvEmpty = findViewById(R.id.tvEmpty)
        waveform = findViewById(R.id.waveform)
        recyclerView = findViewById(R.id.recyclerView)

        setupRecyclerView()
        setupButtons()
        observeRecordings()
        observeServiceState()
        observeSessionErrors()
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            onPlay = { item -> playRecording(item) },
            onDelete = { item -> confirmDelete(item) },
            onInfo = { item -> showBookmarks(item) },
            onRename = { item -> showRenameDialog(item) },
            onShare = { item -> shareRecording(item) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        btnRecord.setOnClickListener {
            when (val state = (application as AudioRecorderApp).recordingSession.state.value) {
                is RecordingSessionState.Recording,
                is RecordingSessionState.Paused,
                is RecordingSessionState.Initializing -> stopRecording()
                else -> requestRecordAudioThenProject()
            }
        }
        btnPauseResume.setOnClickListener {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_PAUSE_RESUME
            }
            startService(intent)
        }
    }

    private fun playRecording(item: RecordingEntity) {
        try {
            abandonAudioFocus()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(item.filePath)
                setOnPreparedListener {
                    if (requestAudioFocus()) {
                        start()
                    } else {
                        release(); mediaPlayer = null
                        Toast.makeText(this@MainActivity, "پخش صدا در حال حاضر ممکن نیست", Toast.LENGTH_SHORT).show()
                    }
                }
                setOnCompletionListener {
                    abandonAudioFocus()
                    release(); mediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("MediaPlayer", "Error: what=$what extra=$extra")
                    abandonAudioFocus()
                    release(); mediaPlayer = null
                    Toast.makeText(this@MainActivity, "خطا در پخش فایل", Toast.LENGTH_SHORT).show()
                    true
                }
                prepareAsync()
            }
            Toast.makeText(this, "در حال آماده‌سازی پخش: ${item.displayName}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در پخش: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaPlayer?.pause()
                        AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.start()
                    }
                }
                .build()
            audioFocusRequest = request
            audioManager?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaPlayer?.pause()
                    AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.start()
                }
            }
            audioFocusListener = listener
            audioManager?.requestAudioFocus(
                listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            audioFocusListener?.let { audioManager?.abandonAudioFocus(it) }
            audioFocusListener = null
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as AudioRecorderApp).recordingSession.state.collect { state ->
                    updateRecordingUI(state)
                }
            }
        }
    }

    private fun updateRecordingUI(state: RecordingSessionState) {
        when (state) {
            is RecordingSessionState.Recording -> {
                btnRecord.isEnabled = true
                btnRecord.text = "⏹ توقف ضبط"
                btnRecord.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                btnPauseResume.visibility = android.view.View.VISIBLE
                btnPauseResume.text = "⏸ توقف موقت"
                btnPauseResume.contentDescription = "توقف موقت ضبط"
                waveform.visibility = android.view.View.VISIBLE
                val time = String.format("%02d:%02d", state.elapsedSeconds / 60, state.elapsedSeconds % 60)
                tvStatus.text = "🔴 در حال ضبط... $time"
                if (state.amplitude > 0) waveform.pushAmplitude(state.amplitude)
            }
            is RecordingSessionState.Paused -> {
                btnRecord.isEnabled = true
                btnRecord.text = "⏹ توقف ضبط"
                btnRecord.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                btnPauseResume.visibility = android.view.View.VISIBLE
                btnPauseResume.text = "▶ ادامه"
                btnPauseResume.contentDescription = "ادامه ضبط"
                waveform.visibility = android.view.View.VISIBLE
                val time = String.format("%02d:%02d", state.elapsedSeconds / 60, state.elapsedSeconds % 60)
                tvStatus.text = "🔴 در حال ضبط... $time (متوقف موقت)"
            }
            is RecordingSessionState.Initializing -> {
                btnRecord.isEnabled = true
                btnRecord.text = "⏹ توقف ضبط"
                btnRecord.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                btnPauseResume.visibility = android.view.View.GONE
                waveform.visibility = android.view.View.GONE
                tvStatus.text = "⏳ در حال آماده‌سازی ضبط..."
            }
            is RecordingSessionState.Stopping -> {
                btnRecord.isEnabled = false
                btnPauseResume.visibility = android.view.View.GONE
                tvStatus.text = "⏳ در حال توقف..."
            }
            is RecordingSessionState.Error -> {
                btnRecord.isEnabled = true
                btnRecord.text = "🔴 شروع ضبط"
                btnRecord.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                btnPauseResume.visibility = android.view.View.GONE
                waveform.visibility = android.view.View.GONE
                waveform.clear()
                tvStatus.text = "آماده برای ضبط (${preferences.quality.label})"
            }
            else -> {
                btnRecord.isEnabled = true
                btnRecord.text = "🔴 شروع ضبط"
                btnRecord.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                btnPauseResume.visibility = android.view.View.GONE
                waveform.visibility = android.view.View.GONE
                waveform.clear()
                tvStatus.text = "آماده برای ضبط (${preferences.quality.label})"
            }
        }
    }

    private fun observeSessionErrors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as AudioRecorderApp).recordingSession.state.collect { state ->
                    if (state is RecordingSessionState.Error) {
                        Toast.makeText(this@MainActivity, state.error.userMessage, Toast.LENGTH_LONG).show()
                        (application as AudioRecorderApp).recordingSession.setIdle()
                    }
                }
            }
        }
    }

    private fun requestRecordAudioThenProject() {
        permissionHelper.requestRecordAudio { granted ->
            if (granted) launchMediaProjection()
            else Toast.makeText(this, "مجوز میکروفون برای ضبط لازم است", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchMediaProjection() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun checkNotificationAndStartService() {
        permissionHelper.requestNotificationPermission { _ ->
            startRecordingService(pendingResultCode, pendingProjectionData!!)
            pendingResultCode = -1
            pendingProjectionData = null
        }
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_START
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_DATA, data)
        }
        startForegroundService(serviceIntent)
        Toast.makeText(this, "ضبط شروع شد (${preferences.quality.label})", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "جستجوی ضبط‌ها..."
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText.orEmpty())
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort_date -> { viewModel.setSortMode(SortMode.DATE_DESC); true }
            R.id.action_sort_name -> { viewModel.setSortMode(SortMode.NAME_ASC); true }
            R.id.action_sort_duration -> { viewModel.setSortMode(SortMode.DURATION_DESC); true }
            R.id.action_sort_size -> { viewModel.setSortMode(SortMode.SIZE_DESC); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            R.id.action_battery -> { requestIgnoreBatteryOptimizations(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun shareRecording(item: RecordingEntity) {
        try {
            val file = File(item.filePath)
            if (!file.exists()) {
                Toast.makeText(this, "فایل یافت نشد", Toast.LENGTH_SHORT).show()
                return
            }
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/aac"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری ضبط"))
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در اشتراک‌گذاری: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRenameDialog(item: RecordingEntity) {
        val input = EditText(this).apply {
            setText(item.displayName)
            setSelection(text.length)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("تغییر نام")
            .setView(container)
            .setPositiveButton("ذخیره") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) viewModel.rename(item, newName)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showBookmarks(item: RecordingEntity) {
        val offsets = item.bookmarks.split(",").mapNotNull { it.trim().toLongOrNull() }
        val message = if (offsets.isEmpty()) {
            "برای این ضبط علامتی ثبت نشده است."
        } else {
            offsets.mapIndexed { index, ms ->
                val time = String.format(
                    "%02d:%02d",
                    TimeUnit.MILLISECONDS.toMinutes(ms),
                    TimeUnit.MILLISECONDS.toSeconds(ms) % 60
                )
                "${index + 1}. $time"
            }.joinToString("\n")
        }
        AlertDialog.Builder(this)
            .setTitle("🔖 علامت‌های ${item.displayName}")
            .setMessage(message)
            .setPositiveButton("باشه", null)
            .show()
    }

    private fun confirmDelete(item: RecordingEntity) {
        AlertDialog.Builder(this)
            .setTitle("حذف ضبط")
            .setMessage("آیا از حذف ${item.displayName} مطمئن هستید؟")
            .setPositiveButton("حذف") { _, _ -> viewModel.delete(item) }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun observeRecordings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recordings.collect { list ->
                    adapter.submitList(list)
                    tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun showLastCrashIfAny() {
        try {
            val crashFile = java.io.File(getExternalFilesDir(null), "last_crash.txt")
            if (crashFile.exists()) {
                val text = crashFile.readText()
                AlertDialog.Builder(this)
                    .setTitle("گزارش خطای اجرای قبلی")
                    .setMessage(text)
                    .setPositiveButton("باشه") { _, _ -> crashFile.delete() }
                    .setNegativeButton("پاک نکن", null)
                    .show()
            }
        } catch (_: Exception) {
        }
    }
}
