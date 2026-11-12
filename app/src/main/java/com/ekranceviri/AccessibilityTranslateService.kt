package com.ekranceviri

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Optional text-tree translation path. It complements MediaProjection OCR:
 * it can read ordinary Android text nodes, but cannot read game canvases or
 * apps that expose no accessibility text. Password and editable fields are skipped.
 */
class AccessibilityTranslateService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val translators = HashMap<String, Translator>()
    private val languageIdentifier = LanguageIdentification.getClient()
    private val cache = LinkedHashMap<String, String>(160, 0.75f, true)
    private var overlay: FrameLayout? = null
    private var lastFingerprint = ""
    @Volatile private var scanGeneration = 0

    private val scanRunnable = Runnable { scanVisibleText() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 350
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // This optional reader must never translate in the background unless explicitly enabled.
        if (!prefs.accessibilityTranslationEnabled) {
            handler.removeCallbacks(scanRunnable)
            scanGeneration++
            lastFingerprint = ""
            clearOverlay()
            return
        }
        if (event.packageName?.toString() == packageName) {
            handler.removeCallbacks(scanRunnable)
            scanGeneration++
            lastFingerprint = ""
            clearOverlay()
            return
        }
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 450)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(scanRunnable)
        scanGeneration++
        lastFingerprint = ""
        clearOverlay()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        clearOverlay()
        scanGeneration++
        translators.values.forEach { try { it.close() } catch (_: Exception) {} }
        translators.clear()
        try { languageIdentifier.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private data class TextNode(val text: String, val bounds: Rect)

    private fun scanVisibleText() {
        if (!prefs.accessibilityTranslationEnabled) {
            scanGeneration++
            lastFingerprint = ""
            clearOverlay()
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            scanGeneration++
            lastFingerprint = ""
            clearOverlay()
            return
        }
        if (root.packageName?.toString() == packageName) {
            root.recycle()
            scanGeneration++
            lastFingerprint = ""
            clearOverlay()
            return
        }
        val found = ArrayList<TextNode>()
        collect(root, found, 0)
        root.recycle()
        val unique = found.distinctBy { it.text.lowercase() + "|" + it.bounds.toShortString() }
            .take(24)
        if (unique.isEmpty()) {
            scanGeneration++
            lastFingerprint = ""
            clearOverlay()
            return
        }
        val fingerprint = unique.joinToString("|") { it.text + it.bounds.toShortString() }
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint
        val generation = ++scanGeneration
        val translated = java.util.Collections.synchronizedList(ArrayList<Pair<TextNode, String>>())
        val remaining = java.util.concurrent.atomic.AtomicInteger(unique.size)
        for (node in unique) {
            val cached = synchronized(cache) { cache[node.text] }
            if (cached != null) {
                translated.add(node to cached)
                if (remaining.decrementAndGet() == 0) handler.post {
                    if (generation == scanGeneration) {
                        val completed = translated.toList()
                        if (completed.isEmpty()) {
                            lastFingerprint = ""
                            clearOverlay()
                        } else render(completed)
                    }
                }
            } else {
                translateNode(node) { result ->
                    if (!result.isNullOrBlank()) {
                        synchronized(cache) {
                            cache[node.text] = result
                            if (cache.size > 250) cache.remove(cache.keys.first())
                        }
                        translated.add(node to result)
                    }
                    if (remaining.decrementAndGet() == 0) handler.post {
                        if (generation == scanGeneration) {
                            val completed = translated.toList()
                            if (completed.isEmpty()) {
                                lastFingerprint = ""
                                clearOverlay()
                            } else render(completed)
                        }
                    }
                }
            }
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<TextNode>, depth: Int) {
        if (depth > 18 || out.size >= 24 || !node.isVisibleToUser) return
        val className = node.className?.toString().orEmpty()
        val isEditable = node.isEditable || className.contains("EditText", true)
        val text = if (isEditable || node.isPassword) "" else
            (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        if (text.length in 2..180 && text.any { it.isLetter() } &&
            !text.contains("\n\n") && !text.equals("Türkçe", true)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 4 && bounds.height() > 4) out.add(TextNode(text, bounds))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1)
            child.recycle()
        }
    }

    private fun translateNode(node: TextNode, callback: (String?) -> Unit) {
        languageIdentifier.identifyLanguage(node.text)
            .addOnSuccessListener { language ->
                if (language == "und" || language == "tr") {
                    callback(null)
                    return@addOnSuccessListener
                }
                val source = TranslateLanguage.fromLanguageTag(language)
                if (source == null || source == TranslateLanguage.TURKISH) {
                    callback(null)
                    return@addOnSuccessListener
                }
                val translator = synchronized(translators) {
                    translators.getOrPut(source) {
                        Translation.getClient(
                            TranslatorOptions.Builder()
                                .setSourceLanguage(source)
                                .setTargetLanguage(TranslateLanguage.TURKISH)
                                .build()
                        )
                    }
                }
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        translator.translate(node.text)
                            .addOnSuccessListener { callback(it) }
                            .addOnFailureListener { callback(null) }
                    }
                    .addOnFailureListener { callback(null) }
            }
            .addOnFailureListener { callback(null) }
    }

    private fun render(items: List<Pair<TextNode, String>>) {
        clearOverlay()
        if (items.isEmpty()) return
        val root = FrameLayout(this)
        val metrics = resources.displayMetrics
        for ((node, translation) in items) {
            val rect = node.bounds
            if (rect.left < 0 || rect.top < 0 || rect.left >= metrics.widthPixels ||
                rect.top >= metrics.heightPixels) continue
            val view = TextView(this).apply {
                text = translation
                setTextColor(Color.WHITE)
                textSize = (rect.height() / metrics.density * 0.62f).coerceIn(10f, 20f)
                typeface = Typeface.DEFAULT
                setPadding(4, 1, 4, 1)
                setBackgroundColor(0xCC20242B.toInt())
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 3
            }
            val params = FrameLayout.LayoutParams(
                (rect.width()).coerceAtLeast(72).coerceAtMost(metrics.widthPixels - rect.left),
                rect.height().coerceAtLeast(28)
            )
            params.leftMargin = rect.left
            params.topMargin = rect.top
            root.addView(view, params)
        }
        if (root.childCount == 0) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(root, params)
            overlay = root
        } catch (_: Exception) {
            overlay = null
        }
    }

    private fun clearOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }
}
