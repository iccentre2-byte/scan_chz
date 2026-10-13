package com.tsd.czsetcollector

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** Trust ONLY the exact certificate paired on the PC screen; not a trust-all manager. */
class PcClient internal constructor(private val config: PcConfig, private val transport: PcHttpTransport) {
    constructor(config: PcConfig) : this(config, createTransport(config))

    companion object {
        private val pcStates = setOf("WAITING_SIGNATURE", "SUBMITTING", "SUBMITTED", "PROCESSING", "VERIFIED",
            "REJECTED", "UNKNOWN", "BLOCKED", "CHECK_REQUIRED", "CANCELLED_LOCAL")
        fun validate(c:PcConfig) {
            val u=URI(c.url)
            require(u.scheme=="https" && u.userInfo==null && u.query==null && u.fragment==null &&
                (u.path.isNullOrEmpty()||u.path=="/") && u.port in 1..65535){"Нужен адрес вида https://192.168.1.10:18765"}
            val octets=(u.host?:"").split('.').map{it.toIntOrNull()?:-1}
            require(octets.size==4 && octets.all{it in 0..255} && (octets[0]==10 ||
                octets[0]==192&&octets[1]==168 || octets[0]==172&&octets[1] in 16..31)) {"Используйте локальный IPv4 ПК, не публичный сервер"}
            require(c.pin.matches(Regex("[a-fA-F0-9]{64}"))){"Проверьте SHA-256 сертификата ПК: 64 символа"}
            require(c.key.matches(Regex("[A-Za-z0-9_-]{32,128}"))){"Проверьте ключ подключения к ПК"}
        }
        private fun fingerprint(cert:X509Certificate)=MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(""){"%02x".format(it.toInt() and 255)}

        private fun createTransport(config: PcConfig): PcHttpTransport {
            validate(config)
            val trust=object:X509TrustManager {
                override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String) {throw CertificateException("Not a server")}
                override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
                    if(chain.isEmpty())throw CertificateException("No server certificate")
                    chain[0].checkValidity()
                    if(!fingerprint(chain[0]).equals(config.pin,true))throw CertificateException("Сертификат ПК изменился. Сверьте QR на ПК")
                }
            }
            val ssl=SSLContext.getInstance("TLS");ssl.init(null,arrayOf(trust),SecureRandom())
            val client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust)
                .hostnameVerifier{host,session -> host==URI(config.url).host &&
                    try {fingerprint(session.peerCertificates[0] as X509Certificate).equals(config.pin,true)}catch(e:Exception){false}}
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS).build()
            return PcHttpTransport(config.url, config.key, client)
        }
    }

    private val gson = Gson()
    init { validate(config) }

    fun ping() {
        val raw = read { transport.get("/v1/ping") }
        try {
            val root = BackupJson.objectValue(BackupJson.parse(raw))
            require(BackupJson.string(root, "version", 64).isNotBlank())
            require(BackupJson.string(root, "signing", 200).isNotBlank())
        } catch (error: Exception) { throw malformed(error) }
    }

    fun status(id: String): Job {
        java.util.UUID.fromString(id)
        return parseReply(read { transport.get("/v1/jobs/$id") }, id)
    }

    fun upload(job: Job): Job {
        val raw = try { transport.post("/v1/jobs", gson.toJson(job)) }
        catch (error: IOException) {
            if (!PcHttpTransport.recoverable(error)) throw diagnostic(error)
            // A lost POST response may follow a durable enqueue. Never resend; look up this UUID.
            try { return matching(job, parseReply(transport.get("/v1/jobs/${job.id}"), job.id)) }
            catch (lookup: Exception) {
                throw IllegalStateException("Передача на ПК не подтверждена. Повторная передача не выполнялась. " +
                    "${diagnostic(lookup).message} Очередь сохранена на ТСД.", lookup)
            }
        } catch (error: Exception) { throw diagnostic(error) }
        return matching(job, parseReply(raw, job.id))
    }

    private fun matching(original: Job, reply: Job): Job {
        require(reply.schemaVersion == original.schemaVersion && reply.id == original.id && reply.inn == original.inn &&
            reply.environment == original.environment && reply.pg == original.pg && reply.template == original.template &&
            reply.setCode == original.setCode && reply.children.size == original.children.size &&
            reply.children.toSet() == original.children.toSet()) { "ПК вернул другой состав. Ответ не принят; очередь сохранена" }
        return reply
    }

    private fun parseReply(raw: String, id: String): Job {
        try {
            val root = BackupJson.objectValue(BackupJson.parse(raw))
            BackupJson.keys(root, setOf("schemaVersion", "id", "environment", "inn", "pg", "template", "setCode", "children", "status", "detail", "documentId"),
                setOf("payloadHash", "updated", "uploaded"))
            require(BackupJson.string(root, "id", 36) == id) { "Ответ для другого задания" }
            require(BackupJson.string(root, "status", 64) in pcStates) { "Неизвестный статус ПК" }
            root["payloadHash"]?.let { require(BackupJson.string(root, "payloadHash", 64).matches(Regex("[a-fA-F0-9]{64}"))) }
            root["updated"]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asDouble.isFinite() && it.asDouble >= 0) }
            root["uploaded"]?.let { BackupJson.boolean(it) }
            root["documentId"].takeUnless { it.isJsonNull }?.let { require(BackupJson.string(root, "documentId", 512).isNotBlank()) }
            if (BackupJson.string(root, "status") in setOf("SUBMITTED", "PROCESSING")) require(!root["documentId"].isJsonNull) { "Нет номера документа ЧЗ" }
            root.remove("payloadHash"); root.remove("updated"); root.addProperty("uploaded", true)
            val snapshot = JsonObject().apply { add("jobs", JsonArray().apply { add(root) }) }
            return QueueBackup.parsePayload(gson.toJson(snapshot)).jobs.single()
        } catch (error: Exception) { throw malformed(error) }
    }

    private fun malformed(error: Exception) = PcResponseException("Помощник ПК вернул неполный или некорректный ответ. " +
        "Ответ не принят; обновите помощник ПК и проверьте подключение.", error)

    private fun read(block: () -> String): String = try { block() } catch (error: Exception) { throw diagnostic(error) }

    private fun diagnostic(error: Exception): Exception {
        if (error is PcResponseException) return error
        if (error is PcHttpException) return IllegalStateException(when (error.code) {
            401, 403 -> "Помощник ПК отклонил ключ подключения. Покажите его QR на ПК и подключите ТСД заново."
            404 -> "Помощник ПК не нашёл этот набор. Проверьте, что открыт прежний помощник и его очередь."
            else -> "Помощник ПК: HTTP ${error.code}. ${error.detail}"
        }, error)
        if (error is SSLException) return IllegalStateException("Сертификат помощника ПК не подтверждён. Сверьте QR подключения на ПК; защита соединения сохранена.", error)
        if (error is IOException) return IllegalStateException((if (error is SocketTimeoutException) "Помощник ПК не ответил вовремя. "
            else "Соединение с помощником ПК прервалось. ") + "Оставьте помощник открытым, проверьте общую Wi-Fi сеть и адрес ПК. Очередь сохранена.", error)
        return error
    }
}
