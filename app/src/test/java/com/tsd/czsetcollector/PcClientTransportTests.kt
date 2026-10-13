package com.tsd.czsetcollector

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

class PcClientTransportTests {
    private val server = MockWebServer()
    private val config = PcConfig("https://192.168.1.2:18765", "k".repeat(43), "a".repeat(64))
    private val http = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS).writeTimeout(1, TimeUnit.SECONDS).build()
    private val gson = Gson()
    private val gtin = "04607010740016"
    private fun job() = Job(inn = "7707083893", template = Template(gtin, mapOf(gtin to 1)),
        setCode = "01${gtin}21SETCODE000001", children = mutableListOf("01${gtin}21CHILD00000001"), status = "LOCAL_READY")
    private fun reply(job: Job, status: String = "WAITING_SIGNATURE") = gson.toJsonTree(job).asJsonObject.apply {
        addProperty("status", status); addProperty("detail", "На ПК; ещё не подписан")
        add("documentId", com.google.gson.JsonNull.INSTANCE)
        remove("uploaded"); addProperty("payloadHash", "b".repeat(64)); addProperty("updated", 1234.0)
    }.toString()
    // MockWebServer's canonical localhost URL may resolve to both families. Once an interrupted
    // exchange marks one route failed, a retry can select ::1 although the mock only bound IPv4.
    // Production PC addresses are literal IPv4 too. Pin both halves of this fixture accordingly.
    private fun fixtureUrl() = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    private fun client(httpClient: OkHttpClient = http) = PcClient(config, PcHttpTransport(fixtureUrl(), config.key, httpClient))
    private fun failure(block: () -> Unit): Exception {
        try { block(); fail("Expected refusal") } catch (error: Exception) { return error }
        error("unreachable")
    }
    @Before fun start() { server.start(InetAddress.getByName("127.0.0.1"), 0) }
    @After fun stop() { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow() }

    @Test fun interruptedGetBodyIsReadAgainOnFreshHttp11Connection() {
        val job = job(); val body = reply(job)
        server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setStatus("HTTP/1.0 200 OK").setBody(body))
        assertEquals("WAITING_SIGNATURE", client().status(job.id).status)
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method); assertEquals("/v1/jobs/${job.id}", request.path)
            assertEquals("close", request.getHeader("Connection")); assertTrue(request.requestLine.endsWith("HTTP/1.1"))
            assertEquals(0, request.sequenceNumber)
        }
    }

    @Test fun recoveryUsesLiteralBoundAddressWithoutLocalhostDnsFallback() {
        val noAliases = http.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw AssertionError("Literal IPv4 fixture must not resolve a localhost alias: $hostname")
        }).build()
        val job = job()
        server.enqueue(MockResponse().setBody(reply(job)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody(reply(job)))
        assertEquals(job.id, client(noAliases).status(job.id).id)
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method); assertEquals(0, request.sequenceNumber)
        }
    }

    @Test fun lostPostReplyUsesUuidLookupWithoutRepeatingPost() {
        val job = job(); val body = reply(job)
        server.enqueue(MockResponse().setBody(body).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setBody(body))
        val result = client().upload(job)
        assertTrue(result.uploaded); assertEquals(job.id, result.id); assertEquals(job.children, result.children)
        assertEquals(2, server.requestCount)
        val post = server.takeRequest(1, TimeUnit.SECONDS)!!; val get = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", post.method); assertEquals("/v1/jobs", post.path)
        assertEquals(job.id, gson.fromJson(post.body.readUtf8(), JsonObject::class.java)["id"].asString)
        assertEquals("GET", get.method); assertEquals("/v1/jobs/${job.id}", get.path)
        assertFalse(job.uploaded); assertEquals("LOCAL_READY", job.status)
    }

    @Test fun unresolvedPostReplyNeverRepeatsOrChangesLocalJob() {
        val job = job()
        server.enqueue(MockResponse().setBody(reply(job)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"not found\"}"))
        val error = failure { client().upload(job) }
        assertTrue(error.message.orEmpty().contains("Повторная передача не выполнялась"))
        assertFalse(job.uploaded); assertEquals("LOCAL_READY", job.status)
        assertEquals(2, server.requestCount)
        assertEquals("POST", server.takeRequest().method); assertEquals("GET", server.takeRequest().method)
    }

    @Test fun aSecondInterruptedGetStopsWithClearDiagnostic() {
        val job = job()
        repeat(2) { server.enqueue(MockResponse().setBody(reply(job)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)) }
        val error = failure { client().status(job.id) }
        assertEquals(2, server.requestCount)
        assertTrue(error.message.orEmpty().contains("Оставьте помощник открытым"))
        assertFalse(error.message.orEmpty().contains("unexpected end of stream"))
    }

    @Test fun authorizationErrorsAreNeverRetriedOrReconciled() {
        val job = job()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"wrong key\"}"))
        assertTrue(failure { client().upload(job) }.message.orEmpty().contains("QR"))
        assertEquals(1, server.requestCount); assertEquals("POST", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(403).setBody("{\"error\":\"wrong key\"}").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertTrue(failure { client().status(job.id) }.message.orEmpty().contains("QR"))
        assertEquals(2, server.requestCount); assertEquals("GET", server.takeRequest().method)
    }

    @Test fun sslFailureAndCancellationAreNeverRetried() {
        for (error in listOf(SSLHandshakeException("certificate mismatch"), InterruptedIOException("cancelled"))) {
            val attempts = AtomicInteger()
            val failing = http.newBuilder().addInterceptor { attempts.incrementAndGet(); throw error }.build()
            failure { client(failing).status(job().id) }; assertEquals(1, attempts.get())
            attempts.set(0); failure { client(failing).upload(job()) }; assertEquals(1, attempts.get())
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun oneReadTimeoutAllowsOnlyOneGetRetry() {
        val attempts = AtomicInteger()
        val delayed = http.newBuilder().addInterceptor { chain ->
            if (attempts.incrementAndGet() == 1) throw SocketTimeoutException("read timeout")
            chain.proceed(chain.request())
        }.build()
        val job = job(); server.enqueue(MockResponse().setBody(reply(job)))
        assertEquals(job.id, client(delayed).status(job.id).id)
        assertEquals(2, attempts.get()); assertEquals(1, server.requestCount)
    }

    @Test fun oversizedAndInvalidUtf8RepliesCannotReportSuccess() {
        server.enqueue(MockResponse().setBody("x".repeat(PcHttpTransport.MAX_RESPONSE_BYTES + 1)))
        assertTrue(failure { client().status(job().id) }.message.orEmpty().contains("2 МБ"))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))))
        assertTrue(failure { client().status(job().id) }.message.orEmpty().contains("повреждённый текст"))
        assertEquals(2, server.requestCount)
    }

    @Test fun incompleteOrDifferentJobIsRejectedBeforeLocalMutation() {
        val job = job()
        server.enqueue(MockResponse().setBody("{\"id\":\"${job.id}\",\"status\":\"VERIFIED\"}"))
        assertTrue(failure { client().upload(job) }.message.orEmpty().contains("некорректный ответ"))
        val different = job.copy(setCode = "01${gtin}21SETCODE000002")
        server.enqueue(MockResponse().setBody(reply(different)))
        assertTrue(failure { client().upload(job) }.message.orEmpty().contains("другой состав"))
        assertFalse(job.uploaded); assertEquals("LOCAL_READY", job.status)
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("POST", server.takeRequest().method) }
    }

    @Test fun helperErrorAndDuplicateJsonFieldsAreNotSuccess() {
        val job = job()
        server.enqueue(MockResponse().setResponseCode(500).setBody(reply(job)))
        assertTrue(failure { client().status(job.id) }.message.orEmpty().contains("HTTP 500"))
        server.enqueue(MockResponse().setBody(reply(job).dropLast(1) + ",\"status\":\"VERIFIED\"}"))
        assertTrue(failure { client().status(job.id) }.message.orEmpty().contains("некорректный ответ"))
        assertEquals(2, server.requestCount)
    }

    @Test fun verifiedCompositionAfterUnknownSubmissionMayHaveNoDocumentUuid() {
        val job = job()
        val response = gson.fromJson(reply(job, "VERIFIED"), JsonObject::class.java).apply {
            addProperty("detail", "Владелец и полный состав проверены; UUID документа неизвестен")
        }
        server.enqueue(MockResponse().setBody(response.toString()))
        val result = client().status(job.id)
        assertEquals("VERIFIED", result.status); assertNull(result.documentId)
        assertEquals(job.children, result.children); assertTrue(result.uploaded)
        assertEquals(1, server.requestCount)
    }
}
