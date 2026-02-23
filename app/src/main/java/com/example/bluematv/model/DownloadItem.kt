package com.example.bluematv.model

data class DownloadItem(
    val downloadId: String,
    val title: String,
    val thumbnail: String?,
    var status: String = "queued",   // queued, downloading, completed, error
    var progress: Int = 0,
    var filePath: String? = null,
    var error: String? = null,
    var platform: String = "video",
    var duration: String = "0:00"
)
