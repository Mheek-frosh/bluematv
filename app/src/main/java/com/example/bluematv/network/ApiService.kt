package com.example.bluematv.network

import com.example.bluematv.model.DownloadProgressResponse
import com.example.bluematv.model.DownloadStartResponse
import com.example.bluematv.model.VideoMetadata
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

interface ApiService {

    @POST("resolve")
    suspend fun resolveVideo(@Body body: Map<String, String>): Response<VideoMetadata>

    @POST("download")
    suspend fun startDownload(@Body body: Map<String, String>): Response<DownloadStartResponse>

    @GET("progress/{downloadId}")
    suspend fun getProgress(@Path("downloadId") downloadId: String): Response<DownloadProgressResponse>

    @Streaming
    @GET("file/{downloadId}")
    suspend fun getFile(@Path("downloadId") downloadId: String): Response<ResponseBody>
}
