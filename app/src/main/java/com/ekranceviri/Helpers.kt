package com.ekranceviri

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    // "offline", "deepl" veya "gemini"
    var engine: String
        get() = sp.getString("engine", "offline") ?: "offline"
        set(v) { sp.edit().putString("engine", v).apply() }

    var deeplKey: String
        get() = sp.getString("deeplKey", "") ?: ""
        set(v) { sp.edit().putString("deeplKey", v.trim()).apply() }

    var geminiKey: String
        get() = sp.getString("geminiKey", "") ?: ""
        set(v) { sp.edit().putString("geminiKey", v.trim()).apply() }

    var geminiModel: String
        get() {
            val saved = sp.getString("geminiModel", null)?.trim().orEmpty()
            return if (saved.isBlank() || saved == "gemini-flash-latest") {
                "gemini-3.8-flash"
            } else saved
        }
        set(v) { sp.edit().putString("geminiModel", v.trim()).apply() }

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    // Floating translation bubble appearance and dock position.
    var bubbleShape: String
        get() = sp.getString("bubbleShape", "circle") ?: "circle"
        set(v) { sp.edit().putString("bubbleShape", v).apply() }

    var bubbleColor: Int
        get() = sp.getInt("bubbleColor", 0xFF4F6BED.toInt())
        set(v) { sp.edit().putInt("bubbleColor", v).apply() }

    var bubbleSize: Int
        get() = sp.getInt("bubbleSize", 56).coerceIn(40, 80)
        set(v) { sp.edit().putInt("bubbleSize", v.coerceIn(40, 80)).apply() }

    var bubbleOpacity: Int
        get() = sp.getInt("bubbleOpacity", 90).coerceIn(35, 100)
        set(v) { sp.edit().putInt("bubbleOpacity", v.coerceIn(35, 100)).apply() }

    var bubbleText: String
        get() = sp.getString("bubbleText", "Ç") ?: "Ç"
        set(v) { sp.edit().putString("bubbleText", v.trim().take(3).ifBlank { "Ç" }).apply() }

    var bubbleX: Int
        get() = sp.getInt("bubbleX", -1)
        set(v) { sp.edit().putInt("bubbleX", v.coerceAtLeast(0)).apply() }

    var bubbleY: Int
        get() = sp.getInt("bubbleY", -1)
        set(v) { sp.edit().putInt("bubbleY", v.coerceAtLeast(0)).apply() }

    var autoIntervalMs: Int
        get() = sp.getInt("autoIntervalMs", 400).coerceIn(250, 1200)
        set(v) { sp.edit().putInt("autoIntervalMs", v.coerceIn(250, 1200)).apply() }

    var mode: String
        get() = sp.getString("mode", "full") ?: "full"
        set(v) { sp.edit().putString("mode", v).apply() }

    var auto: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) { sp.edit().putBoolean("auto", v).apply() }

    var tts: Boolean
        get() = sp.getBoolean("tts", false)
        set(v) { sp.edit().putBoolean("tts", v).apply() }

    var protectNames: Boolean
        get() = sp.getBoolean("protect", true)
        set(v) { sp.edit().putBoolean("protect", v).apply() }

    fun ignored(): Set<String> = sp.getStringSet("ignored", emptySet()) ?: emptySet()

    fun addIgnored(s: String) {
        val n = HashSet<String>(ignored())
        n.add(s)
        sp.edit().putStringSet("ignored", n).apply()
    }

    fun clearIgnored() {
        sp.edit().remove("ignored").apply()
    }

    fun translationHistory(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val array = try { JSONArray(sp.getString("translationHistory", "[]")) }
        catch (_: Exception) { JSONArray() }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val original = item.optString("original")
            val translated = item.optString("translated")
            if (original.isNotBlank() && translated.isNotBlank()) out.add(original to translated)
        }
        return out
    }

    fun addHistory(original: String, translated: String) {
        if (original.isBlank() || translated.isBlank()) return
        val recent = translationHistory()
        if (recent.firstOrNull() == (original to translated)) return
        val array = JSONArray()
        array.put(JSONObject().put("original", original.take(1200))
            .put("translated", translated.take(1200)).put("time", System.currentTimeMillis()))
        for (entry in recent.take(49)) {
            array.put(JSONObject().put("original", entry.first).put("translated", entry.second))
        }
        sp.edit().putString("translationHistory", array.toString()).apply()
    }

    fun clearHistory() {
        sp.edit().remove("translationHistory").apply()
    }

    fun areas(): MutableList<Rect> {
        val out = ArrayList<Rect>()
        val s = sp.getString("areas", "") ?: ""
        for (part in s.split(";")) {
            val n = part.split(",").mapNotNull { it.toIntOrNull() }
            if (n.size == 4) out.add(Rect(n[0], n[1], n[2], n[3]))
        }
        return out
    }

    fun setAreas(list: List<Rect>) {
        val s = list.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
        sp.edit().putString("areas", s).apply()
    }
}

object Themes {
    val names = listOf("Koyu", "Açık", "AMOLED siyah", "Okyanus", "Orman", "Gün batımı")
    private val bgs = listOf("#12141A", "#F3F4F8", "#000000", "#0B1F33", "#0F2318", "#2A1410")
    private val cards = listOf("#1D2029", "#FFFFFF", "#111111", "#12304D", "#173525", "#3D201A")
    private val accents = listOf("#4F6BED", "#3F51B5", "#009688", "#1E88E5", "#2E9E5B", "#E5612B")

    private fun safe(i: Int) = if (i in names.indices) i else 0

    fun bg(i: Int): Int = Color.parseColor(bgs[safe(i)])
    fun card(i: Int): Int = Color.parseColor(cards[safe(i)])
    fun accent(i: Int): Int = Color.parseColor(accents[safe(i)])
    fun isLight(i: Int): Boolean = safe(i) == 1
    fun text(i: Int): Int = if (isLight(i)) Color.parseColor("#1B1B1F") else Color.parseColor("#EDEDED")
}

object Names {
    private val skip = setOf("I", "I'm", "I'll", "I've", "I'd")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun escape(s: String): String = esc(s)

    fun clean(s: String): String =
        s.replace("<x>", "").replace("</x>", "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")

    fun protect(text: String): String {
        val toks = text.split(" ").filter { it.isNotEmpty() }
        if (toks.size < 5) return esc(text)
        val wrap = BooleanArray(toks.size)
        var caps = 0
        var start = true
        for (i in toks.indices) {
            val t = toks[i]
            val core = t.trim { !it.isLetter() }
            if (!start && core.length >= 2 && core[0].isUpperCase() &&
                !skip.contains(core) && !t.startsWith("I'")
            ) {
                wrap[i] = true
                caps++
            }
            start = t.endsWith(".") || t.endsWith("!") || t.endsWith("?") || t.endsWith(":")
        }
        if (caps == 0 || caps * 4 > toks.size) return esc(text)
        val sb = StringBuilder()
        var open = false
        for (i in toks.indices) {
            if (wrap[i]) {
                if (!open) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append("<x>")
                    open = true
                } else {
                    sb.append(' ')
                }
                sb.append(esc(toks[i]))
            } else {
                if (open) {
                    sb.append("</x>")
                    open = false
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(esc(toks[i]))
            }
        }
        if (open) sb.append("</x>")
        return sb.toString()
    }
}

object DeepL {
    var lastError = ""

    fun translate(key: String, texts: List<String>): List<String>? {
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val out = ArrayList<String>()
        var i = 0
        while (i < texts.size) {
            val chunk = texts.subList(i, minOf(i + 40, texts.size))
            val r = post(host, key, chunk) ?: return null
            out.addAll(r)
            i += 40
        }
        return out
    }

    private fun post(host: String, key: String, chunk: List<String>): List<String>? {
        return try {
            val body = StringBuilder("target_lang=TR&tag_handling=xml&ignore_tags=x")
            for (t in chunk) body.append("&text=").append(URLEncoder.encode(t, "UTF-8"))
            val c = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Authorization", "DeepL-Auth-Key $key")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                lastError = when (code) {
                    403 -> "anahtar geçersiz"
                    456 -> "ücretsiz kota doldu"
                    429 -> "çok sık istek"
                    else -> "hata kodu $code"
                }
                return null
            }
            val txt = c.inputStream.bufferedReader().readText()
            val arr = JSONObject(txt).getJSONArray("translations")
            val res = ArrayList<String>()
            for (k in 0 until arr.length()) res.add(arr.getJSONObject(k).getString("text"))
            res
        } catch (e: Exception) {
            lastError = "bağlantı kurulamadı"
            null
        }
    }
}

object Gemini {
    @Volatile var lastError = ""
    @Volatile var lastModelVersion = ""

    private const val SYSTEM =
        "Translate every item from its original language into natural, fluent Turkish. " +
            "The user message is a JSON array of strings. Return ONLY a JSON array of strings " +
            "with exactly the same length and order. Keep proper nouns, character names, item names, " +
            "game terms, brand names, numbers and symbols unchanged. " +
            "If an item is already Turkish or is only a symbol or number, return it unchanged."

    fun translate(key: String, model: String, texts: List<String>): List<String>? {
        if (key.isBlank()) {
            lastError = "Gemini API anahtarı boş"
            return null
        }
        val safeModel = model.trim().ifBlank { "gemini-3.8-flash" }
        lastModelVersion = ""
        val out = ArrayList<String>()
        var i = 0
        while (i < texts.size) {
            val chunk = texts.subList(i, minOf(i + 20, texts.size))
            val r = post(key.trim(), safeModel, chunk) ?: return null
            if (r.size != chunk.size) {
                lastError = "Gemini eksik çeviri döndürdü"
                return null
            }
            out.addAll(r)
            i += chunk.size
        }
        return out
    }

    private fun post(key: String, model: String, chunk: List<String>): List<String>? {
        for (attempt in 0..1) {
            var connection: HttpURLConnection? = null
            var stage = "istek hazırlanıyor"
            val startedAt = System.currentTimeMillis()
            try {
                val input = JSONArray()
                for (t in chunk) input.put(t)
                val body = JSONObject()
                body.put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
                )
                body.put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts", JSONArray().put(JSONObject().put("text", input.toString()))
                        )
                    )
                )
                val generation = JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("maxOutputTokens", 4096)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                body.put("generationConfig", generation)

                val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                    URLEncoder.encode(model, "UTF-8") + ":generateContent"
                stage = "Google sunucusuna bağlanılıyor"
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                // Gemini 3.x yanıtları yoğun sunucu yükünde 25 saniyeyi aşabiliyor.
                connection.readTimeout = 30000
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("x-goog-api-key", key)
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                stage = "istek Google'a gönderiliyor"
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }

                stage = "Google yanıtı bekleniyor"
                val code = connection.responseCode
                if (code != 200) {
                    val err = try {
                        connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    } catch (_: Exception) { "" }
                    val detail = try {
                        JSONObject(err).getJSONObject("error").optString("message")
                    } catch (_: Exception) { err.take(160) }
                    lastError = "Gemini HTTP $code: " + detail.take(180)
                    if (code in listOf(429, 500, 502, 503, 504) && attempt < 1) {
                        Thread.sleep(700L * (attempt + 1))
                        continue
                    }
                    return null
                }

                stage = "Google yanıtı okunuyor"
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                lastModelVersion = root.optString("modelVersion", model)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    lastError = "Gemini boş yanıt verdi: " +
                        root.optJSONObject("promptFeedback")?.optString("blockReason", "aday yok")
                    return null
                }
                val candidate = candidates.getJSONObject(0)
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                if (parts == null) {
                    lastError = "Gemini metin yanıtı döndürmedi (" +
                        candidate.optString("finishReason", "bilinmeyen neden") + ")"
                    return null
                }
                val reply = StringBuilder()
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (!part.optBoolean("thought", false)) {
                        reply.append(part.optString("text"))
                    }
                }
                val raw = reply.toString().trim()
                val first = raw.indexOf('[')
                val last = raw.lastIndexOf(']')
                if (first < 0 || last < first) {
                    lastError = "Gemini geçerli JSON listesi döndürmedi"
                    return null
                }
                val arr = JSONArray(raw.substring(first, last + 1))
                if (arr.length() != chunk.size) {
                    lastError = "Gemini eksik sayıda öğe döndürdü"
                    return null
                }
                val result = ArrayList<String>()
                for (index in 0 until arr.length()) {
                    val value = arr.opt(index)
                    if (value !is String) {
                        lastError = "Gemini yanıtında metin olmayan öğe var"
                        return null
                    }
                    result.add(value)
                }
                lastError = ""
                return result
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startedAt
                lastError = when {
                    e.message?.contains("timeout", true) == true ->
                        stage + " aşamasında zaman aşımı (" + elapsed + " ms). Google API'ye erişim veya ağ yanıtı gecikiyor."
                    else -> stage + " aşamasında hata: " +
                        (e.message ?: e.javaClass.simpleName).take(100)
                }
                // A socket timeout is already a long wait; retrying it doubles the user's delay.
                // Retry only short-lived connection failures, never a timed-out generation.
                if (attempt < 1 && e is java.io.IOException && e !is java.net.SocketTimeoutException) {
                    try { Thread.sleep(350L) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    continue
                }
                return null
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }
}
