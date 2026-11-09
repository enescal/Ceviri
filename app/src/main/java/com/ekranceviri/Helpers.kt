package com.ekranceviri

import android.content.Context
import android.graphics.Rect
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var engine: String
        get() = sp.getString("engine", "offline") ?: "offline"
        set(v) { sp.edit().putString("engine", v).apply() }

    var deeplKey: String
        get() = sp.getString("deeplKey", "") ?: ""
        set(v) { sp.edit().putString("deeplKey", v.trim()).apply() }

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
