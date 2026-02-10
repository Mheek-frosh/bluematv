package com.example.bluematv.model

data class DownloadStartResponse(
    val download_id: String?,
    val status: String?
)

data class DownloadProgressResponse(
    val status: String?,
    val progress: Double?,
    val downloaded_bytes: Long?,
    val total_bytes: Long?,
    val speed: Double?,
    val eta: Double?,
    val filepath: String?,
    val error: String?
)
