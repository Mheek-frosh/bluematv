package com.example.bluematv

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.example.bluematv.adapter.ActivityGridAdapter
import com.example.bluematv.adapter.HistoryAdapter
import com.example.bluematv.adapter.RecentDownloadAdapter
import com.example.bluematv.databinding.ActivityMainBinding
import com.example.bluematv.model.DownloadItem
import com.example.bluematv.model.HistoryItem
import com.example.bluematv.model.VideoMetadata
import com.example.bluematv.network.RetrofitClient
import com.example.bluematv.util.VideoStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var activityAdapter: ActivityGridAdapter
    private lateinit var recentDownloadAdapter: RecentDownloadAdapter
    private lateinit var historyAdapter: HistoryAdapter
    private val downloadItems = mutableListOf<DownloadItem>()
    private val historyItems = mutableListOf<HistoryItem>()
    private var currentVideoMetadata: VideoMetadata? = null
    private var selectedVideoUri: Uri? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestStoragePermission()
        setupRecyclerViews()
        setupBottomNav()
        setupClickListeners()
        applyWindowInsets()
        binding.root.findViewById<android.widget.TextView>(R.id.toolbarTitle)?.text = "BlueMatrix"
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavContainer) { view, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, 0, 0, navBar.bottom)
            insets
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO))
            }
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }
    }

    private fun setupRecyclerViews() {
        activityAdapter = ActivityGridAdapter(downloadItems) { item ->
            if (item.status == "completed" && item.filePath != null) {
                playVideo(item.filePath!!)
            }
        }
        binding.rvActivities.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = activityAdapter
        }

        recentDownloadAdapter = RecentDownloadAdapter(historyItems) { item ->
            item.filePath?.let { path -> playVideo(path) }
        }
        binding.rvRecentDownloads.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = recentDownloadAdapter
        }

        historyAdapter = HistoryAdapter(historyItems) { item ->
            item.filePath?.let { path -> playVideo(path) }
        }
        binding.rvHistory.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = historyAdapter
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showContent(binding.homeContent)
                R.id.nav_activities -> {
                    showContent(binding.activitiesContent)
                    updateActivitiesView()
                    true
                }
                R.id.nav_upload -> showContent(binding.uploadContent)
                R.id.nav_history -> {
                    showContent(binding.historyContent)
                    updateHistoryView()
                    true
                }
                R.id.nav_profile -> showContent(binding.profileContent)
                else -> false
            }
        }
    }

    private fun showContent(visibleView: View): Boolean {
        binding.homeContent.visibility = if (visibleView == binding.homeContent) View.VISIBLE else View.GONE
        binding.activitiesContent.visibility = if (visibleView == binding.activitiesContent) View.VISIBLE else View.GONE
        binding.uploadContent.visibility = if (visibleView == binding.uploadContent) View.VISIBLE else View.GONE
        binding.historyContent.visibility = if (visibleView == binding.historyContent) View.VISIBLE else View.GONE
        binding.profileContent.visibility = if (visibleView == binding.profileContent) View.VISIBLE else View.GONE

        // Update toolbar title per screen
        binding.root.findViewById<android.widget.TextView>(R.id.toolbarTitle)?.text = when (visibleView) {
            binding.homeContent -> "BlueMatrix"
            binding.activitiesContent -> "Activities"
            binding.uploadContent -> "Upload Video"
            binding.historyContent -> "Download History"
            binding.profileContent -> "Profile"
            else -> "BlueMatrix"
        }
        return true
    }

    private fun updateActivitiesView() {
        val hasItems = downloadItems.isNotEmpty()
        binding.activitiesEmptyState.visibility = if (hasItems) View.GONE else View.VISIBLE
        binding.rvActivities.visibility = if (hasItems) View.VISIBLE else View.GONE
    }

    private fun updateHistoryView() {
        binding.tvEmptyHistory.visibility = if (historyItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun playVideo(filePath: String) {
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_VIDEO_PATH, filePath)
        })
    }

    private fun setupClickListeners() {
        // Paste from clipboard (end icon)
        binding.tilUrl.setEndIconOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrEmpty()) {
                    binding.etUrl.setText(text)
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Nothing in clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a video URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            resolveAndDownload(url)
        }

        binding.tvSeeMore.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_history
        }

        // Upload
        binding.cardVideoSelect.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Select Video"), REQUEST_VIDEO)
        }

        binding.btnChangeVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(intent, "Select Video"), REQUEST_VIDEO)
        }

        binding.btnUpload.setOnClickListener {
            if (selectedVideoUri == null) {
                Toast.makeText(this, "Please select a video first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Upload feature coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VIDEO && resultCode == RESULT_OK && data?.data != null) {
            selectedVideoUri = data.data
            binding.ivVideoPreview.visibility = View.VISIBLE
            binding.ivVideoPreview.setImageURI(selectedVideoUri)
            binding.ivCloudUpload.visibility = View.GONE
            binding.btnChangeVideo.visibility = View.VISIBLE
            binding.btnUpload.visibility = View.VISIBLE
        }
    }

    private fun resolveAndDownload(url: String) {
        binding.btnDownload.isEnabled = false
        binding.btnDownload.text = "Resolving..."

        lifecycleScope.launch {
            try {
                val resolveResponse = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.resolveVideo(mapOf("url" to url))
                }

                if (!resolveResponse.isSuccessful) {
                    val errorBody = resolveResponse.errorBody()?.string()
                    val errorMsg = if (!errorBody.isNullOrEmpty() && errorBody.contains("error")) {
                        try {
                            org.json.JSONObject(errorBody).optString("error", "Failed to resolve video")
                        } catch (_: Exception) {
                            "Failed to resolve video"
                        }
                    } else {
                        "Failed to resolve video. Check URL and try again."
                    }
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                    resetDownloadButton()
                    return@launch
                }

                val metadata = resolveResponse.body() ?: run {
                    Toast.makeText(this@MainActivity, "Empty response", Toast.LENGTH_SHORT).show()
                    resetDownloadButton()
                    return@launch
                }
                currentVideoMetadata = metadata

                showVideoInfo(metadata)

                binding.btnDownload.text = "Starting download..."
                val downloadResponse = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.startDownload(mapOf("url" to url))
                }

                if (!downloadResponse.isSuccessful || downloadResponse.body()?.download_id == null) {
                    Toast.makeText(this@MainActivity, "Failed to start download", Toast.LENGTH_SHORT).show()
                    resetDownloadButton()
                    return@launch
                }

                val downloadId = downloadResponse.body()!!.download_id!!

                val platform = detectPlatform(url)
                val duration = metadata.duration?.let { formatDuration(it) } ?: "0:00"
                val downloadItem = DownloadItem(
                    downloadId = downloadId,
                    title = metadata.title ?: "Unknown Video",
                    thumbnail = metadata.thumbnail,
                    status = "downloading",
                    platform = platform,
                    duration = duration
                )
                activityAdapter.addItem(downloadItem)

                binding.bottomNav.selectedItemId = R.id.nav_activities

                pollProgress(downloadId, url)

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                resetDownloadButton()
            }
        }
    }

    private fun showVideoInfo(metadata: VideoMetadata) {
        binding.videoInfoCard.visibility = View.VISIBLE
        binding.tvVideoTitle.text = metadata.title ?: "Unknown"
        binding.tvVideoUploader.text = metadata.uploader ?: ""
        val durationMin = (metadata.duration ?: 0) / 60
        val durationSec = (metadata.duration ?: 0) % 60
        binding.tvVideoDuration.text = String.format("%d:%02d", durationMin, durationSec)
        if (!metadata.thumbnail.isNullOrEmpty()) {
            Glide.with(this).load(metadata.thumbnail).centerCrop().into(binding.ivThumbnail)
        }
    }

    private fun pollProgress(downloadId: String, originalUrl: String) {
        lifecycleScope.launch {
            var completed = false
            while (!completed) {
                delay(1000)
                try {
                    val progressResponse = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.getProgress(downloadId)
                    }
                    if (progressResponse.isSuccessful) {
                        val progress = progressResponse.body()
                        val percent = progress?.progress?.toInt() ?: 0
                        val status = progress?.status ?: "unknown"
                        activityAdapter.updateItem(downloadId, percent, status)

                        when (status) {
                            "completed" -> {
                                completed = true
                                downloadFileToDevice(downloadId, originalUrl)
                                resetDownloadButton()
                            }
                            "error" -> {
                                completed = true
                                Toast.makeText(this@MainActivity, "Download failed: ${progress?.error}", Toast.LENGTH_LONG).show()
                                resetDownloadButton()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun downloadFileToDevice(downloadId: String, originalUrl: String) {
        try {
            val item = downloadItems.firstOrNull { it.downloadId == downloadId }
            val title = item?.title ?: "video"
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
                .ifEmpty { "video" }

            withContext(Dispatchers.IO) {
                val response = RetrofitClient.apiService.getFile(downloadId)
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Download failed: Server returned ${response.code()}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }
                val body = response.body()
                if (body == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Download failed: Empty response", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }
                val rawFilename = parseFilenameFromHeaders(response)
                val filename = when {
                    !rawFilename.isNullOrBlank() && rawFilename.contains(".") -> rawFilename
                    else -> "${safeTitle}_${downloadId.take(8)}.mp4".replace(" ", "_")
                }
                val safeFilename = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "video.mp4" }
                val finalFilename = if (safeFilename.endsWith(".mp4", true)) safeFilename else "$safeFilename.mp4"

                val file = VideoStorageHelper.saveVideo(this@MainActivity, finalFilename) { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
                if (file == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to save file to storage", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }

                val index = downloadItems.indexOfFirst { it.downloadId == downloadId }
                if (index >= 0) {
                    downloadItems[index].filePath = file.absolutePath
                }

                val platform = detectPlatform(originalUrl)
                val duration = currentVideoMetadata?.duration?.let { formatDuration(it) } ?: "0:00"
                val historyItem = HistoryItem(
                    downloadId = downloadId,
                    title = title,
                    thumbnail = item?.thumbnail,
                    platform = platform,
                    duration = duration,
                    format = "mp4",
                    fileSize = formatFileSize(file.length()),
                    timeAgo = "Just now",
                    filePath = file.absolutePath
                )
                historyItems.add(0, historyItem)
                historyAdapter.notifyItemInserted(0)
                recentDownloadAdapter.notifyRecentChanged()

                withContext(Dispatchers.Main) {
                    activityAdapter.updateItem(downloadId, 100, "completed")
                    Toast.makeText(this@MainActivity, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun detectPlatform(url: String): String {
        val lower = url.lowercase()
        return when {
            "youtube" in lower || "youtu.be" in lower -> "youtube"
            "tiktok" in lower -> "tiktok"
            "instagram" in lower -> "instagram"
            "facebook" in lower || "fb." in lower -> "facebook"
            else -> "video"
        }
    }

    private fun formatDuration(seconds: Long): String {
        val s = (seconds % 60).toInt()
        val m = (seconds / 60 % 60).toInt()
        val h = (seconds / 3600).toInt()
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.1f MB", bytes / (1024.0 * 1024))
    }

    private fun resetDownloadButton() {
        binding.btnDownload.isEnabled = true
        binding.btnDownload.text = "Download Video"
    }

    private fun parseFilenameFromHeaders(response: Response<ResponseBody>): String? {
        val contentDisposition = response.headers()["Content-Disposition"] ?: return null
        Regex("""filename[*]?=(?:UTF-8'')?"([^"]+)""").find(contentDisposition)?.let { return it.groupValues[1] }
        Regex("""filename[*]?=(?:UTF-8'')?([^;\s]+)""").find(contentDisposition)?.let { return it.groupValues[1].takeIf { s -> s.isNotBlank() } }
        return null
    }

    companion object {
        private const val REQUEST_VIDEO = 1001
    }
}
