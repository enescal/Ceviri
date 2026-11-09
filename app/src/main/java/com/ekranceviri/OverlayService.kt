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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var button: TextView? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var wantShot = false
    private var width = 0
    private var height = 0

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
        button?.visibility = View.INVISIBLE
        handler.postDelayed({
            wantShot = true
            lp.x += 1
            button?.let { wm.updateViewLayout(it, lp) }
        }, 250)
        handler.postDelayed({
            if (wantShot) {
                wantShot = false
                button?.visibility = View.VISIBLE
                Toast.makeText(this, "Görüntü alınamadı, tekrar dene", Toast.LENGTH_SHORT).show()
            }
        }, 2000)
    }

    private fun onShot(bmp: Bitmap) {
        button?.visibility = View.VISIBLE
        Toast.makeText(this, "Ekran görüntüsü alındı: ${bmp.width}x${bmp.height}", Toast.LENGTH_SHORT).show()
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.RED)
            setPadding(4, 4, 4, 4)
        }
        val plp = WindowManager.LayoutParams(
            width / 3, height / 3,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END }
        wm.addView(iv, plp)
        handler.postDelayed({ try { wm.removeView(iv) } catch (e: Exception) {} }, 3000)
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
        button?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
