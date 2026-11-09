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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
    private var autoOn = false
    private var lastFp: IntArray? = null
    private var lastSpoken = ""
    private var width = 0
    private var height = 0
    private var statusBarH = 0
    private val translators = HashMap<String, Translator>()
    private val cache = HashMap<String, String>()
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
        return Bitmap.createBitmap(full, 0, 0, width, height)
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

    private fun capture(cb: (Bitmap?) -> Unit) {
        capGen++
        val gen = capGen
        button?.visibility = View.INVISIBLE
        overlay?.visibility = View.INVISIBLE
        handler.postDelayed({
            if (gen == capGen) {
                shotCb = cb
                wantShot = true
                nudgeWindow()
            }
        }, 120)
        handler.postDelayed({
            if (gen == capGen && wantShot) {
                wantShot = false
                shotCb = null
                cb(null)
            }
        }, 2200)
    }

    private fun done() {
        busy = false
        restoreViews()
        handler.removeCallbacks(autoRunnable)
        if (autoOn) handler.postDelayed(autoRunnable, 1200)
    }

    private fun fail(msg: String, auto: Boolean) {
        lastFp = null
        toast(msg)
        if (auto) {
            autoOn = false
            updateButtonLook()
        }
        done()
    }

    // ---------- Akış ----------

    private fun takeShot() {
        if (busy || editor != null) return
        busy = true
        dismissOverlay()
        capture { bmp ->
            restoreViews()
            if (bmp == null) {
                toast("Görüntü alınamadı, tekrar dene")
                done()
            } else {
                process(bmp, false)
            }
        }
    }

    private fun autoTick() {
        if (!autoOn) return
        if (busy || editor != null) {
            handler.postDelayed(autoRunnable, 600)
            return
        }
        busy = true
        capture { bmp ->
            restoreViews()
            if (bmp == null) {
                done()
            } else {
                process(bmp, true)
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
        val regs = regions()
        if (regs.isEmpty()) {
            toast("Önce alan çiz: Ç butonuna uzun bas")
            autoOn = false
            updateButtonLook()
            done()
            return
        }
        if (auto) {
            val fp = fingerprint(bmp, regs)
            val prev = lastFp
            lastFp = fp
            if (prev != null && !changed(prev, fp)) {
                done()
                return
            }
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

    private fun ocr(bmp: Bitmap, ox: Int, oy: Int, cb: (List<Item>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res ->
                val list = ArrayList<Item>()
                for (b in res.textBlocks) {
                    val r = b.boundingBox ?: continue
                    val t = b.text.replace("\n", " ").trim()
                    if (t.length < 2) continue
                    list.add(
                        Item(
                            Rect(r.left + ox, r.top + oy, r.right + ox, r.bottom + oy),
                            t, max(1, b.lines.size)
                        )
                    )
                }
                cb(list)
            }
            .addOnFailureListener { cb(emptyList()) }
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun afterOcr(bmp: Bitmap, all: List<Item>, auto: Boolean) {
        val ign = prefs.ignored()
        val items = all.filter { !ign.contains(norm(it.text)) }
        if (items.isEmpty()) {
            if (auto) dismissOverlay() else toast("Yazı bulunamadı")
            done()
            return
        }
        val sample = items.joinToString(" ") { it.text }.take(500)
        langId.identifyLanguage(sample)
            .addOnSuccessListener { tag ->
                if (tag == "tr") {
                    if (auto) dismissOverlay() else toast("Çevrilecek yabancı yazı bulunamadı")
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
            val c = cache["$eng|${items[i].text}"]
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
        if (eng == "deepl") {
            deeplTranslate(bmp, items, result, missing, auto)
        } else {
            offlineTranslate(bmp, items, result, missing, src, auto)
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
                            cache["offline|${items[i].text}"] = t.result
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

    private fun deeplTranslate(
        bmp: Bitmap, items: List<Item>, result: Array<String?>,
        missing: List<Int>, auto: Boolean
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
                    fail("DeepL hatası: ${DeepL.lastError}", auto)
                } else {
                    for (n in missing.indices) {
                        val t = Names.clean(out[n])
                        result[missing[n]] = t
                        cache["deepl|${items[missing[n]].text}"] = t
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
            val maxPx = (r.height().toFloat() / item.lines * 0.7f).coerceIn(24f, 72f)
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
        lastFp = null
        toast(if (areas.isEmpty()) "Alan yok: tüm ekran çevrilecek" else "${areas.size} alan kaydedildi")
    }

    // ---------- Buton ----------

    private fun updateButtonLook() {
        (button?.background as? GradientDrawable)?.setColor(
            if (autoOn) Color.argb(200, 20, 140, 60) else Color.argb(140, 30, 30, 30)
        )
    }

    private fun onButtonTap() {
        if (editor != null) return
        if (prefs.auto || autoOn) {
            autoOn = !autoOn
            updateButtonLook()
            handler.removeCallbacks(autoRunnable)
            if (autoOn) {
                lastFp = null
                lastSpoken = ""
                toast("Otomatik çeviri açık")
                handler.post(autoRunnable)
            } else {
                dismissOverlay()
                toast("Otomatik çeviri kapalı")
            }
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
        bg.setColor(Color.argb(140, 30, 30, 30))
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
                    if (!moved && !longFired) onButtonTap()
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
            recognizer.close()
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
