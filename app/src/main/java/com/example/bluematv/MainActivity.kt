package com.example.bluematv

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.bluematv.adapter.DownloadAdapter
import com.example.bluematv.databinding.ActivityMainBinding
import com.example.bluematv.model.DownloadItem
import com.example.bluematv.model.VideoMetadata
import com.example.bluematv.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloadAdapter: DownloadAdapter
    private val downloadItems = mutableListOf<DownloadItem>()
    private var currentVideoMetadata: VideoMetadata? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupBottomNav()
        setupClickListeners()
    }

    private fun setupRecyclerView() {
        downloadAdapter = DownloadAdapter(downloadItems) { item ->
            if (item.status == "completed" && item.filePath != null) {
                Toast.makeText(this, "File saved: ${item.filePath}", Toast.LENGTH_SHORT).show()
            }
        }
        binding.rvDownloads.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = downloadAdapter
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.homeContent.visibility = View.VISIBLE
                    binding.downloadsContent.visibility = View.GONE
                    true
                }
                R.id.nav_downloads -> {
                    binding.homeContent.visibility = View.GONE
                    binding.downloadsContent.visibility = View.VISIBLE
                    binding.tvEmptyDownloads.visibility =
                        if (downloadItems.isEmpty()) View.VISIBLE else View.GONE
                    true
                }
                else -> false
            }
        }
    }

    private fun setupClickListeners() {
        // Paste button — reads from clipboard
        binding.btnPaste.setOnClickListener {
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

        // Download button — resolve URL, then start download
        binding.btnDownload.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a video URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            resolveAndDownload(url)
        }
    }

    private fun resolveAndDownload(url: String) {
        binding.btnDownload.isEnabled = false
        binding.btnDownload.text = "Resolving..."

        lifecycleScope.launch {
            try {
                // Step 1: Resolve video metadata
                val resolveResponse = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.resolveVideo(mapOf("url" to url))
                }

                if (!resolveResponse.isSuccessful) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to resolve video",
                        Toast.LENGTH_SHORT
                    ).show()
                    resetDownloadButton()
                    return@launch
                }

                val metadata = resolveResponse.body() ?: run {
                    Toast.makeText(this@MainActivity, "Empty response", Toast.LENGTH_SHORT).show()
                    resetDownloadButton()
                    return@launch
                }
                currentVideoMetadata = metadata

                // Show video info card
                showVideoInfo(metadata)

                // Step 2: Start download on server
                binding.btnDownload.text = "Starting download..."
                val downloadResponse = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.startDownload(mapOf("url" to url))
                }

                if (!downloadResponse.isSuccessful || downloadResponse.body()?.download_id == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to start download",
                        Toast.LENGTH_SHORT
                    ).show()
                    resetDownloadButton()
                    return@launch
                }

                val downloadId = downloadResponse.body()!!.download_id!!

                // Add to downloads list
                val downloadItem = DownloadItem(
                    downloadId = downloadId,
                    title = metadata.title ?: "Unknown Video",
                    thumbnail = metadata.thumbnail,
                    status = "downloading"
                )
                downloadAdapter.addItem(downloadItem)

                // Switch to downloads tab
                binding.bottomNav.selectedItemId = R.id.nav_downloads

                // Step 3: Poll progress
                pollProgress(downloadId, url)

            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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
            Glide.with(this)
                .load(metadata.thumbnail)
                .centerCrop()
                .into(binding.ivThumbnail)
        }
    }

    private fun pollProgress(downloadId: String, originalUrl: String) {
        lifecycleScope.launch {
            var completed = false
            while (!completed) {
                delay(1000) // Poll every second

                try {
                    val progressResponse = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.getProgress(downloadId)
                    }

                    if (progressResponse.isSuccessful) {
                        val progress = progressResponse.body()
                        val percent = progress?.progress?.toInt() ?: 0
                        val status = progress?.status ?: "unknown"

                        downloadAdapter.updateItem(downloadId, percent, status)

                        when (status) {
                            "completed" -> {
                                completed = true
                                // Download file from server to device
                                downloadFileToDevice(downloadId)
                                resetDownloadButton()
                            }
                            "error" -> {
                                completed = true
                                Toast.makeText(
                                    this@MainActivity,
                                    "Download failed: ${progress?.error}",
                                    Toast.LENGTH_LONG
                                ).show()
                                resetDownloadButton()
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Network error during polling — retry silently
                }
            }
        }
    }

    private suspend fun downloadFileToDevice(downloadId: String) {
        try {
            val title = downloadItems.firstOrNull { it.downloadId == downloadId }?.title
                ?: "video"
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_")

            withContext(Dispatchers.IO) {
                val response = RetrofitClient.apiService.getFile(downloadId)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                        val file = File(downloadsDir, "${safeTitle}.mp4")
                        FileOutputStream(file).use { output ->
                            body.byteStream().use { input ->
                                input.copyTo(output)
                            }
                        }

                        // Update the item with the file path
                        val index = downloadItems.indexOfFirst { it.downloadId == downloadId }
                        if (index >= 0) {
                            downloadItems[index].filePath = file.absolutePath
                        }

                        withContext(Dispatchers.Main) {
                            downloadAdapter.updateItem(downloadId, 100, "completed")
                            Toast.makeText(
                                this@MainActivity,
                                "Saved: ${file.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to save file: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resetDownloadButton() {
        binding.btnDownload.isEnabled = true
        binding.btnDownload.text = "Download"
    }
}