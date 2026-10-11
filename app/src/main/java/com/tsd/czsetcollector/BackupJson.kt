package com.tsd.czsetcollector

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.security.MessageDigest

/** Strict JSON and an integrity envelope shared with the read-only Windows backup tool. */
object BackupJson {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val gson = Gson()

    fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun wrap(format: String, payload: String): String = gson.toJson(JsonObject().apply {
        addProperty("format", format); addProperty("version", 1)
        addProperty("payloadJson", payload); addProperty("sha256", sha256(payload))
    })

    fun unwrap(raw: String, format: String): String {
        val root = objectValue(parse(raw))
        keys(root, setOf("format", "version", "payloadJson", "sha256"))
        require(string(root, "format", 64) == format && integer(root["version"]) == 1) { "Неизвестный формат резервной копии" }
        val payload = string(root, "payloadJson", MAX_BYTES)
        val hash = string(root, "sha256", 64)
        require(hash.matches(Regex("[a-fA-F0-9]{64}")) && sha256(payload).equals(hash, true)) { "Контрольная сумма резервной копии не совпадает" }
        return payload
    }

    fun parse(raw: String): JsonElement {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Резервная копия слишком велика" }
        try {
            return JsonReader(StringReader(raw.removePrefix("\uFEFF"))).use { reader ->
                reader.isLenient = false
                val result = read(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Лишние данные после JSON" }
                result
            }
        } catch(e:java.io.IOException) { throw IllegalArgumentException("Повреждён JSON резервной копии",e) }
          catch(e:IllegalStateException) { throw IllegalArgumentException("Неверная структура JSON резервной копии",e) }
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 12) { "Слишком сложная резервная копия" }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JsonObject(); reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!result.has(name)) { "Повтор поля в JSON: $name" }
                    result.add(name, read(reader, depth + 1))
                }
                reader.endObject(); result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JsonArray(); reader.beginArray()
                while (reader.hasNext()) result.add(read(reader, depth + 1))
                reader.endArray(); result
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("Повреждён JSON резервной копии")
        }
    }

    fun objectValue(value: JsonElement?): JsonObject {
        require(value != null && value.isJsonObject) { "Ожидался JSON-объект" }; return value.asJsonObject
    }
    fun keys(value: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = value.keySet()
        require(actual.containsAll(required) && (actual - required - optional).isEmpty()) { "Неполные или неизвестные поля резервной копии" }
    }
    fun string(value: JsonObject, key: String, max: Int = 512): String {
        val field = value[key]
        require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString) { "Неверное текстовое поле: $key" }
        val result = field.asString
        require(result.length <= max && !result.contains('\u0000')) { "Недопустимое текстовое поле: $key" }
        return result
    }
    fun integer(value: JsonElement?): Int {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Ожидалось целое число" }
        return try { value.asBigDecimal.intValueExact() } catch (e: ArithmeticException) { throw IllegalArgumentException("Ожидалось целое число", e) }
    }
    fun boolean(value: JsonElement?): Boolean {
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Ожидалось логическое значение" }
        return value.asBoolean
    }
}
