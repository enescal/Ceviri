package com.ekranceviri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var button: TextView? = null
    private var overlay: View? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var wantShot = false
    private var busy = false
    private var width = 0
    private var height = 0
    private val translators = HashMap<String, Translator>()
    private val dismissRunnable = Runnable { dismissOverlay() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null || projection != null) return START_NOT_STICKY
        startAsForeground()
        val code = intent.getIntExtra("code", 0)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
                try {
                    if (wantShot) {
                        wantShot = false
                        onShot(toBitmap(img))
                    }
                } finally {
                    img.close()
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

    private fun takeShot() {
        if (busy) return
        busy = true
        dismissOverlay()
        button?.visibility = View.INVISIBLE
        handler.postDelayed({
            wantShot = true
            lp.x += 1
            button?.let { wm.updateViewLayout(it, lp) }
        }, 250)
        handler.postDelayed({
            if (wantShot) {
                wantShot = false
                busy = false
                button?.visibility = View.VISIBLE
                toast("Görüntü alınamadı, tekrar dene")
            }
        }, 2000)
    }

    private fun onShot(bmp: Bitmap) {
        button?.visibility = View.VISIBLE
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res ->
                if (res.text.isBlank()) {
                    busy = false
                    toast("Yazı bulunamadı")
                } else {
                    detectAndTranslate(res)
                }
            }
            .addOnFailureListener {
                busy = false
                toast("OCR hatası: ${it.message}")
            }
    }

    private fun detectAndTranslate(res: Text) {
        LanguageIdentification.getClient()
            .identifyLanguage(res.text.take(500))
            .addOnSuccessListener { tag ->
                val src = if (tag == "und") TranslateLanguage.ENGLISH
                else TranslateLanguage.fromLanguageTag(tag)
                if (src == null || src == TranslateLanguage.TURKISH) {
                    busy = false
                    toast("Çevrilecek yabancı yazı bulunamadı (dil: $tag)")
                } else {
                    translateBlocks(res, src)
                }
            }
            .addOnFailureListener {
                busy = false
                toast("Dil algılanamadı: ${it.message}")
            }
    }

    private fun translateBlocks(res: Text, src: String) {
        val translator = translators.getOrPut(src) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(TranslateLanguage.TURKISH)
                    .build()
            )
        }
        toast("Çevriliyor... (ilk seferde dil paketi iner, biraz sürebilir)")
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                val blocks = res.textBlocks.filter { it.boundingBox != null }
                if (blocks.isEmpty()) {
                    busy = false
                    toast("Yazı bulunamadı")
                } else {
                    val results = arrayOfNulls<String>(blocks.size)
                    var left = blocks.size
                    blocks.forEachIndexed { i, b ->
                        val original = b.text.replace("\n", " ")
                        translator.translate(original).addOnCompleteListener { t ->
                            results[i] = if (t.isSuccessful) t.result else original
                            left--
                            if (left == 0) showOverlay(blocks, results)
                        }
                    }
                }
            }
            .addOnFailureListener {
                busy = false
                toast("Dil paketi inemedi, internet var mı? ${it.message}")
            }
    }

    private fun showOverlay(blocks: List<Text.TextBlock>, results: Array<String?>) {
        val root = FrameLayout(this)
        root.setOnClickListener { dismissOverlay() }
        blocks.forEachIndexed { i, b ->
            val r = b.boundingBox ?: return@forEachIndexed
            val lines = maxOf(1, b.lines.size)
            val px = (r.height().toFloat() / lines * 0.6f).coerceIn(22f, 64f)
            val tv = TextView(this).apply {
                text = results[i]
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(235, 0, 0, 0))
                setPadding(8, 4, 8, 4)
            }
            val p = FrameLayout.LayoutParams(
                maxOf(r.width(), 120), FrameLayout.LayoutParams.WRAP_CONTENT
            )
            p.leftMargin = r.left
            p.topMargin = r.top
            root.addView(tv, p)
        }
        val plp = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, plp)
        overlay = root
        button?.let {
            wm.removeView(it)
            wm.addView(it, lp)
        }
        handler.postDelayed(dismissRunnable, 10000)
        busy = false
    }

    private fun dismissOverlay() {
        handler.removeCallbacks(dismissRunnable)
        overlay?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        overlay = null
    }

    private fun addButton() {
        val btn = TextView(this).apply {
            text = "Ç"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(140, 30, 30, 30))
            }
        }
        val size = (56 * resources.displayMetrics.density).toInt()
        lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    wm.updateViewLayout(btn, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) takeShot()
                    true
                }
                else -> false
            }
        }
        wm.addView(btn, lp)
        button = btn
    }

    override fun onDestroy() {
        dismissOverlay()
        button?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        translators.values.forEach { it.close() }
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
