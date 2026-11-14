package com.dakyub.crunchymal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}

class HttpException(val code: Int, val body: String) : IOException("HTTP $code: ${body.take(300)}")

/** Exécute la requête et renvoie le corps, ou lève [HttpException] si le statut n'est pas 2xx. */
suspend fun OkHttpClient.fetch(request: Request): String = withContext(Dispatchers.IO) {
    val t0 = Perf.now()
    try {
        newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Perf.http(request.url, response.code, body.length, Perf.now() - t0)
            if (!response.isSuccessful) throw HttpException(response.code, body)
            body
        }
    } catch (e: IOException) {
        if (e !is HttpException) Perf.http(request.url, -1, 0, Perf.now() - t0)
        throw e
    }
}
