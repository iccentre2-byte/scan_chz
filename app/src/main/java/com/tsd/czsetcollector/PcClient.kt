package com.tsd.czsetcollector

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Trust ONLY the exact certificate paired on the PC screen; not a trust-all manager. */
class PcClient(private val config:PcConfig) {
    companion object {
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
    }
    private val gson=Gson()
    private val client:OkHttpClient
    init {
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
        client=OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,trust)
            .hostnameVerifier{host,session -> host==URI(config.url).host &&
                try {fingerprint(session.peerCertificates[0] as X509Certificate).equals(config.pin,true)}catch(e:Exception){false}}
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS).build()
    }
    private fun call(path:String,body:String?=null):String {
        val b=Request.Builder().url(config.url.trimEnd('/')+path).header("Authorization","Bearer "+config.key).header("Accept","application/json")
        if(body==null)b.get() else b.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(b.build()).execute().use {r ->
            val bytes=r.body?.byteStream()?.use{stream ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var n=stream.read(buffer)
                while(n>=0){out.write(buffer,0,n);require(out.size()<=2_000_000){"Слишком большой ответ ПК"};n=stream.read(buffer)};out.toByteArray()
            }?:ByteArray(0)
            val text=String(bytes,Charsets.UTF_8)
            if(!r.isSuccessful){
                val detail=try{gson.fromJson(text,com.google.gson.JsonObject::class.java).get("error")?.asString?:text}catch(e:Exception){text}
                throw IllegalStateException("ПК HTTP ${r.code}: ${detail.take(600)}")
            }
            return text
        }
    }
    fun ping(){call("/v1/ping")}
    fun upload(job:Job):Job=gson.fromJson(call("/v1/jobs",gson.toJson(job)),Job::class.java)
    fun status(id:String):Job {java.util.UUID.fromString(id);return gson.fromJson(call("/v1/jobs/$id"),Job::class.java)}
}
