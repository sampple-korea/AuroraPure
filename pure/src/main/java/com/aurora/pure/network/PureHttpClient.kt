/*
 * This file is based on Aurora Store's HttpClient.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.network

import android.util.Log
import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.network.IHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class PureHttpClient(cacheDirectory: File) : IHttpClient {
    private val _responseCode = MutableStateFlow(0)
    override val responseCode: StateFlow<Int> = _responseCode.asStateFlow()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(cacheDirectory, 32L * 1024L * 1024L))
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun call(url: String, headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .get()
            .build()
        return client.newCall(request).execute()
    }

    @Throws(IOException::class)
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray
    ): PlayResponse = process(
        Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .post(body.toRequestBody())
            .build()
    )

    @Throws(IOException::class)
    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse = process(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .post(ByteArray(0).toRequestBody())
            .build()
    )

    @Throws(IOException::class)
    override fun get(url: String, headers: Map<String, String>): PlayResponse =
        get(url, headers, emptyMap())

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse = process(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build()
    )

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse = process(
        Request.Builder()
            .url("$url$paramString")
            .headers(headers.toHeaders())
            .get()
            .build()
    )

    override fun getAuth(url: String): PlayResponse = process(
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
    )

    override fun postAuth(url: String, body: ByteArray): PlayResponse = process(
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    )

    private fun process(request: Request): PlayResponse {
        _responseCode.value = 0
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "${request.method} ${request.url.host} returned HTTP ${response.code}")
        }
        return buildResponse(response)
    }

    private fun buildResponse(response: Response): PlayResponse = response.use {
        _responseCode.value = it.code
        PlayResponse(
            isSuccessful = it.isSuccessful,
            code = it.code,
            responseBytes = it.body.bytes(),
            errorString = if (it.isSuccessful) "" else it.message
        )
    }

    private fun buildUrl(url: String, params: Map<String, String>): HttpUrl =
        url.toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

    companion object {
        private const val TAG = "AuroraPure"
        // The official dispenser validates the client identifier. Keep the identifier of the
        // pinned Aurora Store protocol baseline while Aurora Pure uses its own package/version
        // everywhere else.
        private const val USER_AGENT = "com.aurora.store-4.8.3-75"
    }
}
