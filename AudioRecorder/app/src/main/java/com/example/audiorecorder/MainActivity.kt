package com.example.audiorecorder

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

    private val viewModel: RecordingListViewModel by viewModels()

    private var mediaPlayer: MediaPlayer? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startRecordingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "مجوز ضبط صفحه لازم است", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = (application as AudioRecorderApp).repository
        preferences = (application as AudioRecorderApp).preferences

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
        checkPermissions()
        observeRecordings()
        observeServiceState()
        maybePromptBatteryOptimization()
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
            if (AudioCaptureService.isRecording.value) {
                stopRecording()
            } else {
                requestProjection()
            }
        }
        btnPauseResume.setOnClickListener {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_PAUSE_RESUME
            }
            startService(intent)
        }
    }

    private fun checkPermissions() {
        if (!PermissionHelper.hasRecordAudio(this)) {
            PermissionHelper.requestRecordAudio(this)
        }
        if (!PermissionHelper.hasNotificationPermission(this)) {
            PermissionHelper.requestNotificationPermission(this)
        }
    }

    private fun requestProjection() {
        if (!PermissionHelper.hasRecordAudio(this)) {
            Toast.makeText(this, "ابتدا مجوز میکروفون را بدهید", Toast.LENGTH_SHORT).show()
            PermissionHelper.requestRecordAudio(this)
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = manager.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
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

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AudioCaptureService.isRecording.collect { updateRecordingUI() } }
                launch { AudioCaptureService.isPaused.collect { updateRecordingUI() } }
                launch {
                    AudioCaptureService.elapsedSecondsFlow.collect { seconds ->
                        if (AudioCaptureService.isRecording.value) {
                            val time = String.format("%02d:%02d", seconds / 60, seconds % 60)
                            val pausedTag = if (AudioCaptureService.isPaused.value) " (متوقف موقت)" else ""
                            tvStatus.text = "🔴 در حال ضبط... $time$pausedTag"
                        }
                    }
                }
                launch {
                    AudioCaptureService.amplitudeFlow.collect { level ->
                        if (AudioCaptureService.isRecording.value && !AudioCaptureService.isPaused.value) {
                            waveform.pushAmplitude(level)
                        }
                    }
                }
            }
        }
    }

    private fun updateRecordingUI() {
        val recording = AudioCaptureService.isRecording.value
        val paused = AudioCaptureService.isPaused.value

        if (recording) {
            btnRecord.text = "⏹ توقف ضبط"
            btnRecord.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            btnPauseResume.visibility = android.view.View.VISIBLE
            btnPauseResume.text = if (paused) "▶ ادامه" else "⏸ توقف موقت"
            waveform.visibility = android.view.View.VISIBLE
            if (!paused) {
                tvStatus.text = "🔴 در حال ضبط صدای داخلی..."
            }
        } else {
            btnRecord.text = "🔴 شروع ضبط"
            btnRecord.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            btnPauseResume.visibility = android.view.View.GONE
            waveform.visibility = android.view.View.GONE
            waveform.clear()
            tvStatus.text = "آماده برای ضبط (${preferences.quality.label})"
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

    // ---------- Menu: search + sort + settings ----------

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

    // ---------- Playback / file actions ----------

    private fun playRecording(item: RecordingEntity) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(item.filePath)
                prepare()
                start()
            }
            Toast.makeText(this, "در حال پخش: ${item.displayName}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطا در پخش: ${e.message}", Toast.LENGTH_SHORT).show()
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

    // ---------- Battery optimization (helps the foreground service survive Doze on some OEMs) ----------

    private fun maybePromptBatteryOptimization() {
        if (preferences.hasRequestedBatteryOptIgnore) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("بهینه‌سازی باتری")
                .setMessage("برای جلوگیری از قطع‌شدن ضبط توسط سیستم، بهتر است بهینه‌سازی باتری برای این اپ غیرفعال شود.")
                .setPositiveButton("تنظیمات") { _, _ -> requestIgnoreBatteryOptimizations() }
                .setNegativeButton("بعداً", null)
                .setOnDismissListener { preferences.hasRequestedBatteryOptIgnore = true }
                .show()
        }
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "مجوز میکروفون لازم است", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
