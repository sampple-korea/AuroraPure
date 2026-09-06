/*
 * This HTTP boundary is derived from Aurora Store's HttpClient.
 * SPDX-FileCopyrightText: 2021 Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-FileCopyrightText: 2026 Aurora Pure contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.pure.cli

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

class CliHttpClient : IHttpClient {
    private val mutableResponseCode = MutableStateFlow(0)
    override val responseCode: StateFlow<Int> = mutableResponseCode.asStateFlow()
    private val protocolLanguages = ThreadLocal<String?>()

    private val protocolClient = baseBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val downloadClient: OkHttpClient = baseBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!DeliveryUrlPolicy.isAllowed(request.url)) {
                throw IOException("Download redirect left approved Google delivery hosts")
            }
            chain.proceed(request)
        }
        .build()

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): PlayResponse =
        processProtocol(
            Request.Builder()
                .url(url)
                .headers(protocolHeaders(headers).toHeaders())
                .post(body.toRequestBody())
                .build()
        )

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

    override fun get(url: String, headers: Map<String, String>): PlayResponse =
        get(url, headers, emptyMap())

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
        Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
    )

    override fun postAuth(url: String, body: ByteArray): PlayResponse = process(
        Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    )

    fun <T> withProtocolLanguages(languages: String, block: () -> T): T {
        require(languages.isNotBlank())
        val previous = protocolLanguages.get()
        protocolLanguages.set(languages)
        return try {
            block()
        } finally {
            if (previous == null) protocolLanguages.remove() else protocolLanguages.set(previous)
        }
    }

    private fun processProtocol(request: Request): PlayResponse {
        val response = process(request)
        if (!response.isSuccessful) throw ProtocolHttpException(response.code)
        return response
    }

    private fun process(request: Request): PlayResponse {
        mutableResponseCode.value = 0
        return buildResponse(protocolClient.newCall(request).execute())
    }

    private fun buildResponse(response: Response): PlayResponse = response.use {
        mutableResponseCode.value = it.code
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

    private fun baseBuilder() = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    class ProtocolHttpException(val status: Int) :
        IOException("Google Play request failed (HTTP $status)")

    companion object {
        private const val USER_AGENT = "com.aurora.store-4.8.3-75"
    }
}

object DeliveryUrlPolicy {
    fun isAllowed(value: String): Boolean = runCatching { isAllowed(value.toHttpUrl()) }
        .getOrDefault(false)

    fun isAllowed(url: HttpUrl): Boolean = url.isHttps && ALLOWED_SUFFIXES.any { suffix ->
        url.host == suffix || url.host.endsWith(".$suffix")
    }

    private val ALLOWED_SUFFIXES = setOf(
        "google.com",
        "googleapis.com",
        "googleusercontent.com",
        "gvt1.com",
        "ggpht.com",
        "googlevideo.com"
    )
}
