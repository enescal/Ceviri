package com.ekranceviri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Item(val rect: Rect, val text: String, val lines: Int)

class AreaView(ctx: Context, private val areas: MutableList<Rect>) : View(ctx) {
    private val line = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val fill = Paint().apply {
        color = Color.argb(60, 0, 255, 0)
        style = Paint.Style.FILL
    }
    private var sx = 0f
    private var sy = 0f
    private var cur: Rect? = null

    override fun onDraw(c: Canvas) {
        for (r in areas) {
            c.drawRect(r, fill)
            c.drawRect(r, line)
        }
        cur?.let {
            c.drawRect(it, fill)
            c.drawRect(it, line)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x
                sy = e.y
                cur = Rect(e.x.toInt(), e.y.toInt(), e.x.toInt(), e.y.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                cur = Rect(
                    min(sx, e.x).toInt(), min(sy, e.y).toInt(),
                    max(sx, e.x).toInt(), max(sy, e.y).toInt()
                )
            }
            MotionEvent.ACTION_UP -> {
                val r = cur
                if (r != null && r.width() > 40 && r.height() > 40) areas.add(r)
                cur = null
            }
        }
        invalidate()
        return true
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var button: TextView? = null
    private var overlay: View? = null
    private var editor: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var tts: TextToSpeech? = null
    private var wantShot = false
    private var shotCb: ((Bitmap?) -> Unit)? = null
    private var capGen = 0
    private var nudge = false
    private var busy = false
    private var processingBitmap: Bitmap? = null
    private var autoOn = false

    // Otomatik mod durumu
    private var prevFp: IntArray? = null      // bir önceki kontrolde ekranın parmak izi
    private var handledFp: IntArray? = null   // en son işlenen ekranın parmak izi

    // Kaydırmayı izleme
    private var overlayRects: List<Rect> = emptyList()
    private var overlayShift = 0
    private var lastGray: IntArray? = null
    private var lastGrayShift = 0
    private var scrolled = false
    private var gridW = 1
    private var gridH = 1
    private var gridK = 6
    private var lastErrToast = 0L

    private var lastTap = 0L
    private var pendingTap: Runnable? = null
    private var lastSpoken = ""
    private var width = 0
    private var height = 0
    private var statusBarH = 0
    private val translators = HashMap<String, Translator>()
    private val cache = HashMap<String, String>()
    // CeBo benzeri çok dilli OCR: Latin ile birlikte Asya ve Devanagari yazıları.
    private val recognizers: List<TextRecognizer> by lazy {
        listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
    }
    private val langId by lazy { LanguageIdentification.getClient() }
    private val dismissRunnable = Runnable { dismissOverlay() }
    private val autoRunnable = Runnable { autoTick() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { s ->
            if (s == TextToSpeech.SUCCESS) tts?.language = Locale("tr", "TR")
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null || projection != null) return START_NOT_STICKY
        val code = intent.getIntExtra("code", 0)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarH = if (id > 0) resources.getDimensionPixelSize(id) else 0
        setupCapture()
        addButton()
        return START_NOT_STICKY
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ceviri", "Ekran Çeviri", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "ceviri")
            .setContentTitle("Ekran Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- Ekran yakalama ----------

    private fun setupCapture() {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        width = dm.widthPixels
        height = dm.heightPixels
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener(object : ImageReader.OnImageAvailableListener {
            override fun onImageAvailable(rd: ImageReader) {
                val img = rd.acquireLatestImage() ?: return
                var bmp: Bitmap? = null
                try {
                    if (wantShot) {
                        wantShot = false
                        bmp = toBitmap(img)
                    }
                } finally {
                    img.close()
                }
                val b = bmp
                if (b != null) {
                    val cb = shotCb
                    shotCb = null
                    cb?.invoke(b)
                }
            }
        }, handler)
        reader = r
        display = projection?.createVirtualDisplay(
            "ekranceviri", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val full = Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
        if (cropped !== full) full.recycle()
        return cropped
    }

    private fun nudgeWindow() {
        nudge = !nudge
        lp.x += if (nudge) 1 else -1
        button?.let {
            try {
                wm.updateViewLayout(it, lp)
            } catch (e: Exception) {
            }
        }
    }

    private fun restoreViews() {
        button?.visibility = View.VISIBLE
        overlay?.visibility = View.VISIBLE
    }

    // hide = true: buton ve çeviri gizlenip temiz görüntü alınır
    // hide = false: ekran olduğu gibi (çeviri açıkken) izlenir, titreme olmaz
    private fun capture(hide: Boolean, cb: (Bitmap?) -> Unit) {
        capGen++
        val gen = capGen
        if (hide) {
            button?.visibility = View.INVISIBLE
            overlay?.visibility = View.INVISIBLE
        }
        handler.postDelayed({
            if (gen == capGen) {
                shotCb = cb
                wantShot = true
                nudgeWindow()
            }
        }, if (hide) 120L else 40L)
        handler.postDelayed({
            if (gen == capGen && wantShot) {
                wantShot = false
                shotCb = null
                cb(null)
            }
        }, 2200)
    }

    private fun done() {
        processingBitmap?.let { if (!it.isRecycled) it.recycle() }
        processingBitmap = null
        busy = false
        restoreViews()
        handler.removeCallbacks(autoRunnable)
        if (autoOn) handler.postDelayed(autoRunnable, if (overlay != null) 350L else 500L)
    }

    private fun resetAutoState() {
        prevFp = null
        handledFp = null
        lastGray = null
        lastGrayShift = 0
        overlayShift = 0
        scrolled = false
        lastSpoken = ""
    }

    private fun errToast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrToast > 15000) {
            lastErrToast = now
            toast(msg)
        }
    }

    private fun fail(msg: String, auto: Boolean) {
        if (auto) errToast(msg) else toast(msg)
        done()
    }

    // ---------- Akış ----------

    private fun takeShot() {
        if (busy || editor != null) return
        busy = true
        dismissOverlay()
        capture(true) { bmp ->
            restoreViews()
            if (bmp == null) {
                toast("Görüntü alınamadı, tekrar dene")
                done()
            } else {
                process(bmp, false)
            }
        }
    }

    // Otomatik mod: ekranı izler. Ekran değişirse çeviriyi kaldırır,
    // ekran durulunca (iki kontrol üst üste aynı) çevirir.
    private fun autoTick() {
        if (!autoOn) return
        if (busy || editor != null) {
            handler.postDelayed(autoRunnable, 600)
            return
        }
        busy = true
        capture(false) { bmp -> monitor(bmp) }
    }

    private fun monitor(bmp: Bitmap?) {
        if (!autoOn || bmp == null) {
            bmp?.let { if (!it.isRecycled) it.recycle() }
            done()
            return
        }
        val regs = regions()
        if (regs.isEmpty()) {
            if (!bmp.isRecycled) bmp.recycle()
            toast("Önce alan çiz: Ç butonuna uzun bas")
            autoOn = false
            resetAutoState()
            updateButtonLook()
            done()
            return
        }
        val fp = fingerprint(bmp, regs)

        if (overlay != null) {
            trackOverlay(bmp)
            if (!bmp.isRecycled) bmp.recycle()
            return
        }
        if (!bmp.isRecycled) bmp.recycle()

        val prev = prevFp
        prevFp = fp
        if (prev == null || changed(prev, fp)) {
            // ekran hâlâ hareket ediyor, durulmasını bekle
            done()
            return
        }
        val handled = handledFp
        if (handled != null && !changed(handled, fp)) {
            // bu ekran zaten işlendi (Türkçe ya da yazı yok)
            done()
            return
        }
        handledFp = fp
        capture(true) { clean ->
            restoreViews()
            if (clean == null || !autoOn) {
                done()
            } else {
                process(clean, true)
            }
        }
    }

    // ---------- Kaydırmayı izleme ----------

    private fun grayGrid(bmp: Bitmap): IntArray {
        val k = 6
        val gw = max(1, bmp.width / k)
        val gh = max(1, bmp.height / k)
        val small = Bitmap.createScaledBitmap(bmp, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (small !== bmp) small.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        }
        gridW = gw
        gridH = gh
        gridK = k
        return px
    }

    // Çevirinin kapladığı yerleri (eski ve yeni konumda) hesaba katmadan karşılaştırmak için
    private fun buildMask(sa: Int, sb: Int): BooleanArray {
        val m = BooleanArray(gridW * gridH)
        for (r in overlayRects) {
            for (s in intArrayOf(sa, sb)) {
                val x0 = max(0, r.left / gridK - 1)
                val x1 = min(gridW, r.right / gridK + 2)
                val y0 = max(0, (r.top + s) / gridK - 1)
                val y1 = min(gridH, (r.bottom + s) / gridK + 2)
                for (y in y0 until y1) {
                    for (x in x0 until x1) m[y * gridW + x] = true
                }
            }
        }
        return m
    }

    private fun grayChanged(a: IntArray, b: IntArray, mask: BooleanArray): Boolean {
        var diff = 0
        for (i in a.indices) {
            if (!mask[i] && abs(a[i] - b[i]) > 30) diff++
        }
        return diff > max(3, a.size / 500)
    }

    // Sayfa kaç hücre kaydı? Güvenilir değilse null.
    private fun estimateShift(a: IntArray, b: IntArray, mask: BooleanArray): Int? {
        val w = gridW
        val h = gridH
        val maxD = h / 2
        var bestDy = 0
        var bestAvg = Double.MAX_VALUE
        var zeroAvg = Double.MAX_VALUE
        val minCount = (w / 2) * (h / 6)
        for (i in 0..(2 * maxD)) {
            val dy = if (i % 2 == 1) -((i + 1) / 2) else i / 2
            val y0 = max(0, -dy)
            val y1 = min(h, h - dy)
            var sum = 0L
            var cnt = 0
            var y = y0
            while (y < y1) {
                val ra = y * w
                val rb = (y + dy) * w
                var x = 0
                while (x < w) {
                    if (!mask[ra + x] && !mask[rb + x]) {
                        sum += abs(a[ra + x] - b[rb + x])
                        cnt++
                    }
                    x += 2
                }
                y++
            }
            if (cnt < minCount) continue
            val avg = sum.toDouble() / cnt
            if (dy == 0) zeroAvg = avg
            if (avg < bestAvg - 0.05) {
                bestAvg = avg
                bestDy = dy
            }
        }
        if (bestDy == 0) return 0
        if (bestAvg > 12.0) return null
        if (zeroAvg - bestAvg < 3.0) return null
        return bestDy
    }

    // Çeviri ekrandayken sayfa kayarsa çeviriyi de kaydırır, kayma durunca yeniler.
    private fun trackOverlay(bmp: Bitmap) {
        val g = grayGrid(bmp)
        val before = lastGray
        val shiftNow = overlayShift
        val shiftBefore = lastGrayShift
        lastGray = g
        lastGrayShift = shiftNow
        if (before == null || before.size != g.size) {
            done()
            return
        }
        val mask = buildMask(shiftBefore, shiftNow)
        if (!grayChanged(before, g, mask)) {
            if (scrolled) {
                scrolled = false
                refreshAuto()
            } else {
                done()
            }
            return
        }
        val dy = estimateShift(before, g, mask)
        if (dy == null || dy == 0) {
            // takip edilemedi: eski çeviriyi kaldır, ekran durunca yeniden çevrilir
            dismissOverlay()
            resetAutoState()
            done()
            return
        }
        overlayShift += dy * gridK
        overlay?.translationY = overlayShift.toFloat()
        scrolled = true
        done()
    }

    private fun refreshAuto() {
        capture(true) { clean ->
            restoreViews()
            if (clean == null || !autoOn) {
                clean?.let { if (!it.isRecycled) it.recycle() }
                done()
            } else {
                handledFp = fingerprint(clean, regions())
                process(clean, true)
            }
        }
    }

    private fun regions(): List<Rect> {
        if (prefs.mode == "area") {
            val out = ArrayList<Rect>()
            for (a in prefs.areas()) {
                val r = Rect(max(0, a.left), max(0, a.top), min(width, a.right), min(height, a.bottom))
                if (r.width() > 30 && r.height() > 30) out.add(r)
            }
            return out
        }
        return listOf(Rect(0, statusBarH, width, height))
    }

    private fun fingerprint(bmp: Bitmap, regions: List<Rect>): IntArray {
        val k = 12
        val sw = max(1, bmp.width / k)
        val sh = max(1, bmp.height / k)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bmp) small.recycle()
        val out = ArrayList<Int>()
        for (r in regions) {
            val x0 = r.left / k
            val x1 = min(sw, r.right / k)
            val y0 = r.top / k
            val y1 = min(sh, r.bottom / k)
            for (y in y0 until y1) {
                for (x in x0 until x1) out.add(px[y * sw + x])
            }
        }
        return out.toIntArray()
    }

    private fun changed(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return true
        var diff = 0
        for (i in a.indices) {
            val d = abs(Color.red(a[i]) - Color.red(b[i])) +
                abs(Color.green(a[i]) - Color.green(b[i])) +
                abs(Color.blue(a[i]) - Color.blue(b[i]))
            if (d > 60) diff++
        }
        return diff > max(3, a.size / 400)
    }

    private fun process(bmp: Bitmap, auto: Boolean) {
        processingBitmap?.let { if (!it.isRecycled) it.recycle() }
        processingBitmap = bmp
        val regs = regions()
        if (regs.isEmpty()) {
            toast("Önce alan çiz: Ç butonuna uzun bas")
            autoOn = false
            resetAutoState()
            updateButtonLook()
            done()
            return
        }
        val all = ArrayList<Item>()
        var left = regs.size
        for (r in regs) {
            val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            ocr(crop, r.left, r.top) { items ->
                all.addAll(items)
                left--
                if (left == 0) afterOcr(bmp, all, auto)
            }
        }
    }

    private fun mergeOcrItems(items: List<Item>): List<Item> {
        val merged = ArrayList<Item>()
        // Aynı metni birden fazla OCR motoru bulursa kutuları tekilleştir.
        for (candidate in items.sortedByDescending { it.text.length }) {
            val candidateArea = max(1, candidate.rect.width() * candidate.rect.height())
            val duplicate = merged.indexOfFirst { current ->
                if (norm(current.text) != norm(candidate.text)) {
                    false
                } else {
                    val intersection = Rect(current.rect)
                    if (!intersection.intersect(candidate.rect)) {
                        false
                    } else {
                        val area = intersection.width() * intersection.height()
                        val smallerArea = min(
                            candidateArea,
                            max(1, current.rect.width() * current.rect.height())
                        )
                        area.toFloat() / smallerArea.toFloat() > 0.55f
                    }
                }
            }
            if (duplicate < 0) merged.add(candidate)
        }
        return merged.sortedWith(compareBy<Item> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun ocr(bmp: Bitmap, ox: Int, oy: Int, cb: (List<Item>) -> Unit) {
        val input = InputImage.fromBitmap(bmp, 0)
        val collected = ArrayList<Item>()
        val lock = Any()
        var pending = recognizers.size
        var finished = false

        fun complete(items: List<Item>) {
            val result: List<Item>?
            synchronized(lock) {
                collected.addAll(items)
                pending--
                result = if (pending == 0 && !finished) {
                    finished = true
                    mergeOcrItems(collected)
                } else null
            }
            if (result != null) {
                if (!bmp.isRecycled) bmp.recycle()
                cb(result)
            }
        }

        for (recognizer in recognizers) {
            try {
                recognizer.process(input)
                    .addOnSuccessListener { res ->
                        val list = ArrayList<Item>()
                        for (block in res.textBlocks) {
                            val rect = block.boundingBox ?: continue
                            val text = block.text.replace("\n", " ").trim()
                            if (text.length < 2) continue
                            list.add(
                                Item(
                                    Rect(rect.left + ox, rect.top + oy, rect.right + ox, rect.bottom + oy),
                                    text,
                                    max(1, block.lines.size)
                                )
                            )
                        }
                        complete(list)
                    }
                    .addOnFailureListener { complete(emptyList()) }
            } catch (_: Exception) {
                complete(emptyList())
            }
        }
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun afterOcr(bmp: Bitmap, all: List<Item>, auto: Boolean) {
        val ign = prefs.ignored()
        val items = all.filter { !ign.contains(norm(it.text)) }
        if (items.isEmpty()) {
            if (!auto) toast("Yazı bulunamadı") else dismissOverlay()
            done()
            return
        }
        val sample = items.joinToString(" ") { it.text }.take(500)
        langId.identifyLanguage(sample)
            .addOnSuccessListener { tag ->
                if (tag == "tr" && prefs.engine == "offline") {
                    if (!auto) toast("Çevrilecek yabancı yazı bulunamadı") else dismissOverlay()
                    done()
                } else {
                    val src = if (tag == "und") TranslateLanguage.ENGLISH
                    else TranslateLanguage.fromLanguageTag(tag)
                    if (src == null) {
                        fail("Bu dil desteklenmiyor ($tag)", auto)
                    } else {
                        translateItems(bmp, items, src, auto)
                    }
                }
            }
            .addOnFailureListener { fail("Dil algılanamadı", auto) }
    }

    private fun translateItems(bmp: Bitmap, items: List<Item>, src: String, auto: Boolean) {
        val result = arrayOfNulls<String>(items.size)
        val missing = ArrayList<Int>()
        val eng = prefs.engine
        for (i in items.indices) {
            val c = cache["$eng|$src|${items[i].text}"]
            if (c != null) {
                result[i] = c
            } else {
                missing.add(i)
            }
        }
        if (missing.isEmpty()) {
            show(bmp, items, result, auto)
            return
        }
        if (cache.size > 600) cache.clear()
        when (eng) {
            "deepl" -> deeplTranslate(bmp, items, result, missing, src, auto)
            "gemini" -> geminiTranslate(bmp, items, result, missing, src, auto)
            else -> offlineTranslate(bmp, items, result, missing, src, auto)
        }
    }

    private fun offlineTranslate(
        bmp: Bitmap, items: List<Item>, result: Array<String?>,
        missing: List<Int>, src: String, auto: Boolean
    ) {
        val translator = translators.getOrPut(src) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(TranslateLanguage.TURKISH)
                    .build()
            )
        }
        if (!auto) toast("Çevriliyor... (ilk seferde dil paketi iner)")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                var left = missing.size
                for (i in missing) {
                    translator.translate(items[i].text).addOnCompleteListener { t ->
                        if (t.isSuccessful) {
                            result[i] = t.result
                            cache["offline|$src|${items[i].text}"] = t.result
                        } else {
                            result[i] = items[i].text
                        }
                        left--
                        if (left == 0) show(bmp, items, result, auto)
                    }
                }
            }
            .addOnFailureListener {
                fail("Dil paketi inemedi, internet var mı?", auto)
            }
    }

    // İnternetli motor hata verirse çeviri durmasın, internetsiz motora geç
    private fun fallbackOffline(
        msg: String, bmp: Bitmap, items: List<Item>, result: Array<String?>,
        missing: List<Int>, src: String, auto: Boolean
    ) {
        if (src == TranslateLanguage.TURKISH) {
            fail("$msg. Türkçe algılandı; yanlış dilden çevirmedim.", auto)
            return
        }
        errToast("$msg — internetsiz çeviriye geçildi")
        offlineTranslate(bmp, items, result, missing, src, auto)
    }

    private fun deeplTranslate(
        bmp: Bitmap, items: List<Item>, result: Array<String?>,
        missing: List<Int>, src: String, auto: Boolean
    ) {
        val key = prefs.deeplKey
        if (key.isBlank()) {
            fail("DeepL anahtarı girilmemiş", auto)
            return
        }
        val protect = prefs.protectNames
        val payload = missing.map {
            if (protect) Names.protect(items[it].text) else Names.escape(items[it].text)
        }
        Thread {
            val out = DeepL.translate(key, payload)
            handler.post {
                if (out == null || out.size != payload.size) {
                    fallbackOffline("DeepL hatası: ${DeepL.lastError}", bmp, items, result, missing, src, auto)
                } else {
                    for (n in missing.indices) {
                        val t = Names.clean(out[n])
                        result[missing[n]] = t
                        cache["deepl|$src|${items[missing[n]].text}"] = t
                    }
                    show(bmp, items, result, auto)
                }
            }
        }.start()
    }

    private fun geminiTranslate(
        bmp: Bitmap, items: List<Item>, result: Array<String?>,
        missing: List<Int>, src: String, auto: Boolean
    ) {
        val key = prefs.geminiKey
        if (key.isBlank()) {
            fail("Gemini anahtarı girilmemiş", auto)
            return
        }
        val model = prefs.geminiModel.ifBlank { "gemini-flash-latest" }
        val payload = missing.map { items[it].text }
        if (!auto) toast("Gemini ile çevriliyor...")
        Thread {
            val out = Gemini.translate(key, model, payload)
            handler.post {
                if (out == null || out.size != payload.size) {
                    fallbackOffline("Gemini hatası: ${Gemini.lastError}", bmp, items, result, missing, src, auto)
                } else {
                    for (n in missing.indices) {
                        val t = out[n]
                        result[missing[n]] = t
                        cache["gemini|$src|${items[missing[n]].text}"] = t
                    }
                    show(bmp, items, result, auto)
                }
            }
        }.start()
    }

    // ---------- Gösterim ----------

    private fun sampleColor(bmp: Bitmap, r: Rect): Int {
        val x = max(0, min(bmp.width - 1, r.left - 3))
        val y = max(0, min(bmp.height - 1, r.top + r.height() / 2))
        val c = bmp.getPixel(x, y)
        return Color.argb(255, Color.red(c), Color.green(c), Color.blue(c))
    }

    private fun show(bmp: Bitmap, items: List<Item>, result: Array<String?>, auto: Boolean) {
        if (auto && !autoOn) {
            done()
            return
        }
        val root = FrameLayout(this)
        if (!auto) root.setOnClickListener { dismissOverlay() }
        for (i in items.indices) {
            val item = items[i]
            val r = item.rect
            val tr = result[i] ?: item.text
            val bgC = sampleColor(bmp, r)
            val lum = 0.299 * Color.red(bgC) + 0.587 * Color.green(bgC) + 0.114 * Color.blue(bgC)
            val maxPx = (r.height().toFloat() / item.lines * 0.7f).coerceIn(24f, 64f)
            val tv = TextView(this)
            tv.text = tr
            tv.setTextColor(if (lum > 140) Color.BLACK else Color.WHITE)
            tv.setBackgroundColor(bgC)
            tv.setPadding(6, 2, 6, 2)
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setAutoSizeTextTypeUniformWithConfiguration(
                16, maxPx.toInt(), 2, TypedValue.COMPLEX_UNIT_PX
            )
            if (!auto) {
                tv.setOnClickListener { dismissOverlay() }
                tv.setOnLongClickListener {
                    prefs.addIgnored(norm(item.text))
                    root.removeView(tv)
                    toast("Bu yazı bir daha çevrilmeyecek")
                    true
                }
            }
            val p = FrameLayout.LayoutParams(max(r.width(), 120), r.height() + 8)
            p.leftMargin = r.left
            p.topMargin = max(0, r.top - 4)
            root.addView(tv, p)
        }
        addFullWindow(root, !auto)
        handler.removeCallbacks(dismissRunnable)
        if (!auto) handler.postDelayed(dismissRunnable, 10000)
        if (auto) {
            // çeviri ekranda: kaydırma izleme sıfırdan başlar
            overlayRects = items.map {
                val r = it.rect
                Rect(r.left, r.top - 4, r.left + max(r.width(), 120), r.bottom + 4)
            }
            overlayShift = 0
            lastGray = null
            lastGrayShift = 0
            scrolled = false
            prevFp = null
        }

        if (prefs.tts && prefs.mode == "area") {
            val txt = items.indices.sortedBy { items[it].rect.top }
                .joinToString(" ") { result[it] ?: items[it].text }
            if (txt != lastSpoken) {
                lastSpoken = txt
                tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "ceviri")
            }
        }
        done()
    }

    private fun fullParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return p
    }

    private fun bringButtonToFront() {
        button?.let {
            try {
                wm.removeView(it)
                wm.addView(it, lp)
            } catch (e: Exception) {
            }
        }
    }

    private fun addFullWindow(root: View, touchable: Boolean) {
        val old = overlay
        wm.addView(root, fullParams(touchable))
        overlay = root
        old?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        bringButtonToFront()
    }

    private fun dismissOverlay() {
        handler.removeCallbacks(dismissRunnable)
        overlay?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        overlay = null
    }

    // ---------- Alan seçme ----------

    private fun openEditor() {
        if (editor != null) return
        dismissOverlay()
        val areas = prefs.areas()
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(70, 0, 0, 0))
        val av = AreaView(this, areas)
        root.addView(
            av,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundColor(Color.argb(200, 0, 0, 0))
        bar.setPadding(24, 16, 24, 16)
        val hint = TextView(this)
        hint.text = "Çevrilecek yeri parmağınla çiz (birden fazla çizebilirsin)"
        hint.setTextColor(Color.WHITE)
        hint.textSize = 14f
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val clear = Button(this)
        clear.text = "Alanları sil"
        clear.setOnClickListener {
            areas.clear()
            av.invalidate()
        }
        val ok = Button(this)
        ok.text = "Bitti"
        ok.setOnClickListener { closeEditor(areas) }
        row.addView(clear)
        row.addView(ok)
        bar.addView(hint)
        bar.addView(row)
        val bp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        bp.gravity = Gravity.TOP
        bp.topMargin = statusBarH
        root.addView(bar, bp)
        wm.addView(root, fullParams(true))
        editor = root
        bringButtonToFront()
    }

    private fun closeEditor(areas: List<Rect>) {
        prefs.setAreas(areas)
        prefs.mode = if (areas.isEmpty()) "full" else "area"
        editor?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        editor = null
        resetAutoState()
        toast(if (areas.isEmpty()) "Alan yok: tüm ekran çevrilecek" else "${areas.size} alan kaydedildi")
    }

    // ---------- Buton ----------

    private fun idleColor(): Int {
        val a = Themes.accent(prefs.theme)
        return Color.argb(190, Color.red(a), Color.green(a), Color.blue(a))
    }

    private fun updateButtonLook() {
        (button?.background as? GradientDrawable)?.setColor(
            if (autoOn) Color.argb(220, 20, 160, 70) else idleColor()
        )
    }

    private fun toggleAuto() {
        if (editor != null) return
        autoOn = !autoOn
        updateButtonLook()
        handler.removeCallbacks(autoRunnable)
        resetAutoState()
        if (autoOn) {
            toast("Otomatik çeviri açık (buton yeşil)")
            handler.postDelayed(autoRunnable, 300)
        } else {
            dismissOverlay()
            toast("Otomatik çeviri kapalı")
        }
    }

    private fun onButtonTap() {
        if (editor != null) return
        if (autoOn || prefs.auto) {
            toggleAuto()
        } else {
            takeShot()
        }
    }

    private fun addButton() {
        val btn = TextView(this)
        btn.text = "Ç"
        btn.textSize = 20f
        btn.setTextColor(Color.WHITE)
        btn.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(idleColor())
        btn.background = bg
        val size = (56 * resources.displayMetrics.density).toInt()
        lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 300
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var longFired = false
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                pendingTap?.let { handler.removeCallbacks(it) }
                openEditor()
            }
        }
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    longFired = false
                    handler.postDelayed(longPress, 700)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    wm.updateViewLayout(btn, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longFired) {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 350) {
                            // çift dokunuş: otomatik modu aç/kapat
                            lastTap = 0L
                            pendingTap?.let { handler.removeCallbacks(it) }
                            pendingTap = null
                            toggleAuto()
                        } else {
                            lastTap = now
                            val r = Runnable { onButtonTap() }
                            pendingTap = r
                            handler.postDelayed(r, 350)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
        wm.addView(btn, lp)
        button = btn
    }

    override fun onDestroy() {
        autoOn = false
        handler.removeCallbacksAndMessages(null)
        processingBitmap?.let { if (!it.isRecycled) it.recycle() }
        processingBitmap = null
        if (this::wm.isInitialized) {
            dismissOverlay()
            editor?.let {
                try {
                    wm.removeView(it)
                } catch (e: Exception) {
                }
            }
            button?.let {
                try {
                    wm.removeView(it)
                } catch (e: Exception) {
                }
            }
        }
        translators.values.forEach { it.close() }
        try {
            recognizers.forEach { it.close() }
            langId.close()
        } catch (e: Exception) {
        }
        tts?.shutdown()
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
