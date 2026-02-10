package com.example.bluematv.model

data class DownloadItem(
    val downloadId: String,
    val title: String,
    val thumbnail: String?,
    var status: String = "queued",   // queued, downloading, completed, error
    var progress: Int = 0,
    var filePath: String? = null,
    var error: String? = null
)
