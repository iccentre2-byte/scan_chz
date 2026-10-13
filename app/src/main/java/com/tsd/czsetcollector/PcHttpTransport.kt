package com.tsd.czsetcollector

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.net.ssl.SSLException

/** One request per connection fits the Python helper. Only read-only GET may be retried. */
internal class PcHttpTransport(baseUrl: String, private val key: String, client: OkHttpClient) {
    companion object {
        const val MAX_RESPONSE_BYTES = 2_000_000

        fun recoverable(error: IOException): Boolean {
            if (Thread.currentThread().isInterrupted || error.message.equals("Canceled", true)) return false
            val causes = generateSequence<Throwable>(error) { it.cause }.take(10).toList()
            if (causes.any { it is SSLException || it is InterruptedIOException && it !is SocketTimeoutException }) return false
            return causes.any { it is EOFException || it is SocketException || it is SocketTimeoutException ||
                it is ProtocolException && it.message.orEmpty().contains("unexpected end", true) ||
                it.message.orEmpty().contains("unexpected end of stream", true) ||
                it.message.orEmpty().contains("stream was reset", true) }
        }
    }

    private val baseUrl = baseUrl.trimEnd('/')
    private val client = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun get(path: String): String {
        try { return request(path, null) }
        catch (error: IOException) {
            if (!recoverable(error)) throw error
            client.connectionPool.evictAll()
            return request(path, null) // Exactly one fresh, read-only retry; never recursive.
        }
    }

    fun post(path: String, body: String): String = request(path, body)

    private fun request(path: String, body: String?): String {
        val builder = Request.Builder().url(baseUrl + path).header("Authorization", "Bearer $key")
            .header("Accept", "application/json").header("Connection", "close")
        if (body == null) builder.get() else builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            // An authorization failure is definitive even if its body is truncated.
            if (response.code == 401 || response.code == 403) throw PcHttpException(response.code, "Ключ подключения не принят")
            val source = response.body ?: throw PcResponseException("Помощник ПК вернул пустой ответ")
            val length = source.contentLength()
            if (length > MAX_RESPONSE_BYTES) throw PcResponseException("Ответ помощника ПК превышает 2 МБ")
            val bytes = try { source.byteStream().use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_RESPONSE_BYTES) throw PcResponseException("Ответ помощника ПК превышает 2 МБ")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            } } catch (error: IOException) {
                if (!response.isSuccessful) throw PcHttpException(response.code, "Ответ об ошибке получен не полностью")
                throw error
            }
            if (length >= 0 && bytes.size.toLong() != length) {
                if (!response.isSuccessful) throw PcHttpException(response.code, "Ответ об ошибке получен не полностью")
                throw EOFException("Incomplete PC response")
            }
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw PcResponseException("Помощник ПК вернул повреждённый текст ответа", error)
            }
            if (!response.isSuccessful) {
                val detail = try {
                    BackupJson.string(BackupJson.objectValue(BackupJson.parse(text)), "error", 2000)
                } catch (_: Exception) { "Неполный или неверный ответ об ошибке" }
                throw PcHttpException(response.code, detail.replace(key, "[скрыто]").take(600))
            }
            return text
        }
    }
}

internal class PcHttpException(val code: Int, val detail: String) : IllegalStateException("ПК HTTP $code: $detail")
internal class PcResponseException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
