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
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class PureHttpClient : IHttpClient {
    private val _responseCode = MutableStateFlow(0)
    override val responseCode: StateFlow<Int> = _responseCode.asStateFlow()

    private val protocolLanguages = ThreadLocal<String?>()

    private val protocolClient: OkHttpClient = baseClientBuilder()
        // Authenticated protocol headers must never follow a redirect to another host.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val downloadClient: OkHttpClient = baseClientBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!DeliveryUrlPolicy.isAllowed(request.url)) {
                throw IOException("Download redirect left the approved Google delivery hosts")
            }
            chain.proceed(request)
        }
        .build()

    private fun baseClientBuilder() = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    fun call(url: String, headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .get()
            .build()
        return protocolClient.newCall(request).execute()
    }

    @Throws(IOException::class)
    override fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray
    ): PlayResponse = processProtocol(
        Request.Builder()
            .url(url)
            .headers(protocolHeaders(headers).toHeaders())
            .post(body.toRequestBody())
            .build()
    )

    @Throws(IOException::class)
    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse = processProtocol(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(protocolHeaders(headers).toHeaders())
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
    ): PlayResponse = processProtocol(
        Request.Builder()
            .url(buildUrl(url, params))
            .headers(protocolHeaders(headers).toHeaders())
            .get()
            .build()
    )

    @Throws(IOException::class)
    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse = processProtocol(
        Request.Builder()
            .url("$url$paramString")
            .headers(protocolHeaders(headers).toHeaders())
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

    /**
     * Runs one synchronous GPlayApi request with an exact language header. The override is
     * thread-local so an image/search request on another thread can never inherit authenticated
     * delivery headers intended for locale split discovery.
     */
    fun <T> withProtocolLanguages(languages: String, block: () -> T): T {
        require(languages.isNotBlank()) { "A protocol language override cannot be blank" }
        val previous = protocolLanguages.get()
        protocolLanguages.set(languages)
        return try {
            block()
        } finally {
            if (previous == null) protocolLanguages.remove() else protocolLanguages.set(previous)
        }
    }

    private fun process(request: Request): PlayResponse {
        _responseCode.value = 0
        val response = protocolClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "${request.method} ${request.url.host} returned HTTP ${response.code}")
        }
        return buildResponse(response)
    }

    private fun processProtocol(request: Request): PlayResponse {
        val response = process(request)
        if (!response.isSuccessful) throw ProtocolHttpException(response.code)
        return response
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

    private fun protocolHeaders(headers: Map<String, String>): Map<String, String> {
        val languages = protocolLanguages.get()?.takeIf(String::isNotBlank) ?: return headers
        if ("X-DFE-UserLanguages" !in headers) return headers
        return headers + mapOf(
            "Accept-Language" to languages.replace('_', '-'),
            "X-DFE-UserLanguages" to languages
        )
    }

    class ProtocolHttpException(val status: Int) :
        IOException("Google Play request failed (HTTP $status)")

    companion object {
        private const val TAG = "AuroraPure"
        // The official dispenser validates the client identifier. Keep the identifier of the
        // pinned Aurora Store protocol baseline while Aurora Pure uses its own package/version
        // everywhere else.
        private const val USER_AGENT = "com.aurora.store-4.8.3-75"
    }
}
