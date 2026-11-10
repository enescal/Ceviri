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
        get() = sp.getString("geminiModel", "gemini-flash-latest") ?: "gemini-flash-latest"
        set(v) { sp.edit().putString("geminiModel", v.trim()).apply() }

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

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
            c.connectTimeout = 8000
            c.readTimeout = 15000
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
    var lastError = ""

    private const val SYSTEM =
        "You translate text captured from a phone screen into natural, fluent Turkish. " +
            "The user message is a JSON array of strings. Return ONLY a JSON array of strings " +
            "with exactly the same length and order, each item being the Turkish translation of " +
            "the item at the same position. Keep proper nouns, character names, item names, " +
            "game terms, brand names, numbers and symbols unchanged. " +
            "If an item is already Turkish or is only a symbol or number, return it unchanged."

    fun translate(key: String, model: String, texts: List<String>): List<String>? {
        val out = ArrayList<String>()
        var i = 0
        while (i < texts.size) {
            val chunk = texts.subList(i, minOf(i + 30, texts.size))
            val r = post(key, model, chunk) ?: return null
            out.addAll(r)
            i += 30
        }
        return out
    }

    private fun post(key: String, model: String, chunk: List<String>): List<String>? {
        return try {
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
            body.put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("responseMimeType", "application/json")
            )
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                URLEncoder.encode(model, "UTF-8") + ":generateContent"
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("x-goog-api-key", key)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                lastError = when (code) {
                    400 -> "istek veya anahtar hatalı"
                    401, 403 -> "anahtar geçersiz"
                    404 -> "model bulunamadı ($model)"
                    429 -> "kota doldu ya da çok sık istek"
                    else -> "hata kodu $code"
                }
                return null
            }
            val txt = c.inputStream.bufferedReader().readText()
            var reply = JSONObject(txt)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text").trim()
            if (reply.startsWith("```")) {
                reply = reply.substringAfter("\n").substringBeforeLast("```").trim()
            }
            val arr = JSONArray(reply)
            if (arr.length() != chunk.size) {
                lastError = "eksik yanıt geldi"
                return null
            }
            val res = ArrayList<String>()
            for (k in 0 until arr.length()) res.add(arr.getString(k))
            res
        } catch (e: Exception) {
            lastError = "bağlantı veya yanıt hatası"
            null
        }
    }
}
