package com.example.bluematv.model

data class VideoMetadata(
    val title: String?,
    val thumbnail: String?,
    val duration: Long?,
    val uploader: String?,
    val formats: List<VideoFormat>?
)

data class VideoFormat(
    val format_id: String?,
    val ext: String?,
    val resolution: String?,
    val filesize: Long?,
    val has_video: Boolean?,
    val has_audio: Boolean?
)
