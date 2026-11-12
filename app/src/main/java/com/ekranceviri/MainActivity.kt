package com.ekranceviri

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var rbOffline: RadioButton
    private lateinit var rbDeepl: RadioButton
    private lateinit var rbGemini: RadioButton
    private lateinit var etDeepl: EditText
    private lateinit var etGemini: EditText
    private lateinit var etModel: EditText
    private lateinit var swAuto: Switch
    private lateinit var rbFull: RadioButton
    private lateinit var rbArea: RadioButton
    private lateinit var swTts: Switch
    private lateinit var swNames: Switch
    private lateinit var swMultiOcr: Switch
    private lateinit var rgBubbleShape: RadioGroup
    private lateinit var sbBubbleSize: SeekBar
    private lateinit var sbBubbleOpacity: SeekBar
    private lateinit var sbAutoInterval: SeekBar
    private lateinit var etBubbleLabel: EditText
    private lateinit var tvBubbleSize: TextView
    private lateinit var tvBubbleOpacity: TextView
    private lateinit var tvAutoInterval: TextView
    private var ready = false

    private var cText = Color.WHITE
    private var cAccent = Color.BLUE
    private var cCard = Color.DKGRAY
    private var hasPhoto = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun photoFile(): File = File(filesDir, "bg.jpg")

    private fun label(t: String, size: Float, bold: Boolean): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(cText)
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(0, dp(4), 0, dp(6))
        return tv
    }

    private fun radio(t: String, id: Int): RadioButton {
        val rb = RadioButton(this)
        rb.text = t
        rb.id = id
        rb.setTextColor(cText)
        rb.buttonTintList = ColorStateList.valueOf(cAccent)
        return rb
    }

    private fun sw(t: String): Switch {
        val s = Switch(this)
        s.text = t
        s.setTextColor(cText)
        s.setPadding(0, dp(8), 0, dp(8))
        s.thumbTintList = ColorStateList.valueOf(cAccent)
        s.trackTintList = ColorStateList.valueOf(Color.argb(120, Color.red(cAccent), Color.green(cAccent), Color.blue(cAccent)))
        return s
    }

    private fun edit(hint: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setSingleLine(true)
        e.setTextColor(cText)
        e.setHintTextColor(Color.argb(130, Color.red(cText), Color.green(cText), Color.blue(cText)))
        e.backgroundTintList = ColorStateList.valueOf(cAccent)
        return e
    }

    private fun button(t: String, filled: Boolean): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        if (filled) {
            bg.setColor(cAccent)
            b.setTextColor(Color.WHITE)
        } else {
            bg.setColor(Color.TRANSPARENT)
            bg.setStroke(dp(2), cAccent)
            b.setTextColor(cAccent)
        }
        b.background = bg
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    private fun card(root: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        val alpha = if (hasPhoto) 215 else 255
        bg.setColor(
            Color.argb(alpha, Color.red(cCard), Color.green(cCard), Color.blue(cCard))
        )
        c.background = bg
        c.setPadding(dp(16), dp(12), dp(16), dp(12))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12)
        c.layoutParams = lp
        if (title.isNotEmpty()) c.addView(label(title, 17f, true))
        root.addView(c)
        return c
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        render()
    }

    private fun render() {
        val t = prefs.theme
        cText = Themes.text(t)
        cAccent = Themes.accent(t)
        cCard = Themes.card(t)
        val bgColor = Themes.bg(t)
        window.statusBarColor = bgColor

        val frame = FrameLayout(this)
        frame.setBackgroundColor(bgColor)

        var photo: Bitmap? = null
        if (photoFile().exists()) {
            photo = try {
                BitmapFactory.decodeFile(photoFile().absolutePath)
            } catch (e: Exception) {
                null
            }
        }
        hasPhoto = photo != null
        if (photo != null) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(photo)
            frame.addView(
                iv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val scrim = View(this)
            scrim.setBackgroundColor(
                if (Themes.isLight(t)) Color.argb(90, 255, 255, 255) else Color.argb(120, 0, 0, 0)
            )
            frame.addView(
                scrim,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(40))
        val scroll = ScrollView(this)
        scroll.addView(root)
        frame.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(frame)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = android.view.Gravity.CENTER_VERTICAL
        val brand = ImageView(this)
        brand.setImageResource(R.drawable.ic_brand_mark)
        brand.scaleType = ImageView.ScaleType.FIT_CENTER
        header.addView(brand, LinearLayout.LayoutParams(dp(64), dp(64)))
        val title = label("Ekran Çeviri", 28f, true)
        title.setPadding(dp(12), 0, 0, dp(8))
        header.addView(title)
        val headerLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        headerLp.bottomMargin = dp(12)
        root.addView(header, headerLp)

        // Çeviri motoru
        val c1 = card(root, "Çeviri motoru")
        val g1 = RadioGroup(this)
        rbOffline = radio("İnternetsiz (ücretsiz, daha sade Türkçe)", 101)
        rbDeepl = radio("DeepL (doğal Türkçe, ücretsiz anahtar)", 102)
        rbGemini = radio("Gemini (çok doğal Türkçe, ücretsiz anahtar)", 103)
        g1.addView(rbOffline)
        g1.addView(rbDeepl)
        g1.addView(rbGemini)
        c1.addView(g1)
        etDeepl = edit("DeepL anahtarı (:fx ile biter)")
        etDeepl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        c1.addView(etDeepl)
        etGemini = edit("Gemini anahtarı (aistudio.google.com)")
        etGemini.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        c1.addView(etGemini)
        etModel = edit("Gemini modeli (varsayılan: gemini-3.1-flash-lite)")
        c1.addView(etModel)
        val btnTest = button("Gemini anahtarını test et", false)
        btnTest.setOnClickListener { testGemini() }
        c1.addView(btnTest)

        // Güçlü çevrimdışı HY-MT modeli (ilk indirme yaklaşık 1,9 GB).
        val hyManager = HyMtModelManager(this)
        val hyStatus = label(
            if (hyManager.isInstalled()) "HY-MT modeli hazır" else "HY-MT modeli henüz indirilmedi (yaklaşık 1,9 GB)",
            13f, false
        )
        c1.addView(hyStatus)
        val btnHy = button(
            if (hyManager.isInstalled()) "HY-MT modeli indirildi" else "Güçlü çevrimdışı modeli indir",
            false
        )
        btnHy.isEnabled = !hyManager.isInstalled()
        btnHy.setOnClickListener {
            btnHy.isEnabled = false
            btnHy.text = "İndirme hazırlanıyor…"
            hyManager.download(object : HyMtModelManager.Listener {
                override fun onProgress(progress: HyMtModelManager.Progress) {
                    runOnUiThread {
                        val percent = if (progress.total > 0) {
                            (progress.downloaded * 100 / progress.total).toInt().coerceIn(0, 100)
                        } else 0
                        hyStatus.text = if (progress.total > 0 && progress.message.contains("indir")) {
                            "HY-MT indiriliyor: %$percent"
                        } else progress.message
                        btnHy.text = progress.message
                    }
                }
                override fun onComplete(success: Boolean, message: String) {
                    runOnUiThread {
                        hyStatus.text = message
                        btnHy.text = if (success) "HY-MT modeli hazır" else "Yeniden dene"
                        btnHy.isEnabled = !success
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        c1.addView(btnHy)

        val btnImportHy = button("HY-MT ZIP dosyasını telefondan seç", false)
        btnImportHy.setOnClickListener {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            startActivityForResult(picker, 201)
        }
        c1.addView(btnImportHy)

        // Ne çevrilsin
        val c2 = card(root, "Ne çevrilsin?")
        val g2 = RadioGroup(this)
        rbFull = radio("Tüm ekran", 201)
        rbArea = radio("Seçtiğim alan (Ç butonuna uzun basıp çiz)", 202)
        g2.addView(rbFull)
        g2.addView(rbArea)
        c2.addView(g2)

        // Seçenekler
        val c3 = card(root, "Seçenekler")
        swAuto = sw("Otomatik mod: Ç'ye bir kez bas, ekran durunca kendisi çevirsin")
        swNames = sw("İsimleri koru (DeepL'de çalışır, Gemini zaten korur)")
        swTts = sw("Çeviriyi Türkçe sesle oku (seçili alan modunda)")
        swMultiOcr = sw("Gelişmiş çok dilli OCR (Japonca, Korece, Çince ve Latin birlikte; daha doğru ama daha fazla işlem)")
        c3.addView(swAuto)
        c3.addView(swNames)
        c3.addView(swTts)
        c3.addView(swMultiOcr)

        val accessibilityInfo = label(
            "İsteğe bağlı: Normal uygulama yazılarını OCR kullanmadan okumayı dener. Oyun ve resim içindeki yazılarda ekran OCR modu kullanılır. Parola ve yazı giriş alanları atlanır.",
            12f, false
        )
        c3.addView(accessibilityInfo)
        val btnAccessibility = button("Erişilebilirlik metin çevirisini ayarla", false)
        btnAccessibility.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Erişilebilirlik metin çevirisi")
                .setMessage(
                    "Bu izin, açık olan uygulamadaki görünür normal metin öğelerini okuyup Türkçeye çevirmek içindir. " +
                    "Parola ve yazı giriş alanları atlanır. Oyunların çizim tabanlı yazıları bu yöntemle okunamaz; " +
                    "onlar için mevcut ekran yakalama + OCR sistemi çalışmaya devam eder. " +
                    "Özelliği yalnızca istersen Android Erişilebilirlik ayarlarından etkinleştir."
                )
                .setPositiveButton("Ayarları aç") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        c3.addView(btnAccessibility)
        val btnHistory = button("Son çevirileri göster", false)
        btnHistory.setOnClickListener { showTranslationHistory() }
        c3.addView(btnHistory)
        val btnClearHistory = button("Çeviri geçmişini temizle", false)
        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Kaydedilmiş çevirilerin tamamı silinsin mi?")
                .setPositiveButton("Sil") { _, _ ->
                    prefs.clearHistory()
                    Toast.makeText(this, "Çeviri geçmişi temizlendi", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        c3.addView(btnClearHistory)

        val btnClear = button("\"Bir daha çevirme\" listesini temizle", false)
        btnClear.setOnClickListener {
            prefs.clearIgnored()
            Toast.makeText(this, "Liste temizlendi", Toast.LENGTH_SHORT).show()
        }
        c3.addView(btnClear)

        // Yüzen baloncuk kişiselleştirme
        val cBubble = card(root, "Yüzen baloncuk")
        cBubble.addView(label("Baloncuk şekli", 14f, true))
        rgBubbleShape = RadioGroup(this).apply { orientation = LinearLayout.HORIZONTAL }
        rgBubbleShape.addView(radio("Daire", 401))
        rgBubbleShape.addView(radio("Yuvarlak kare", 402))
        rgBubbleShape.addView(radio("Kare", 403))
        cBubble.addView(rgBubbleShape)

        val palette = linkedMapOf(
            "Mavi" to 0xFF4F6BED.toInt(),
            "Mor" to 0xFF8B5CF6.toInt(),
            "Turkuaz" to 0xFF00A6A6.toInt(),
            "Yeşil" to 0xFF22A06B.toInt(),
            "Turuncu" to 0xFFF08032.toInt(),
            "Pembe" to 0xFFE64A88.toInt(),
            "Kırmızı" to 0xFFE53935.toInt(),
            "Beyaz" to 0xFFF2F2F2.toInt()
        )
        val initialColorName = palette.entries.firstOrNull { it.value == prefs.bubbleColor }?.key ?: "Özel"
        val colorButton = button("Baloncuk rengi: $initialColorName", false)
        colorButton.setOnClickListener {
            val entries = palette.keys.toTypedArray()
            val selected = palette.values.indexOf(prefs.bubbleColor).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Baloncuk rengi")
                .setSingleChoiceItems(entries, selected) { dialog, which ->
                    prefs.bubbleColor = palette.values.elementAt(which)
                    colorButton.text = "Baloncuk rengi: ${entries[which]}"
                    refreshRunningBubble()
                    dialog.dismiss()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        cBubble.addView(colorButton)

        cBubble.addView(label("Baloncuk boyutu", 14f, true))
        tvBubbleSize = label("", 12f, false)
        cBubble.addView(tvBubbleSize)
        sbBubbleSize = SeekBar(this).apply { max = 40 }
        cBubble.addView(sbBubbleSize)

        cBubble.addView(label("Baloncuk saydamlığı", 14f, true))
        tvBubbleOpacity = label("", 12f, false)
        cBubble.addView(tvBubbleOpacity)
        sbBubbleOpacity = SeekBar(this).apply { max = 65 }
        cBubble.addView(sbBubbleOpacity)

        etBubbleLabel = edit("Baloncuk yazısı (Ç, T, vb.)")
        cBubble.addView(etBubbleLabel)

        cBubble.addView(label("Otomatik tarama hızı", 14f, true))
        tvAutoInterval = label("", 12f, false)
        cBubble.addView(tvAutoInterval)
        sbAutoInterval = SeekBar(this).apply { max = 3 }
        cBubble.addView(sbAutoInterval)

        // Görünüm
        val c4 = card(root, "Görünüm")
        val g3 = RadioGroup(this)
        for (i in Themes.names.indices) g3.addView(radio(Themes.names[i], 301 + i))
        g3.check(301 + prefs.theme)
        c4.addView(g3)
        val btnPhoto = button("Arka plan fotoğrafı seç", false)
        btnPhoto.setOnClickListener {
            saveAll()
            val i = Intent(Intent.ACTION_GET_CONTENT)
            i.type = "image/*"
            startActivityForResult(i, 200)
        }
        c4.addView(btnPhoto)
        if (hasPhoto) {
            val btnNoPhoto = button("Fotoğrafı kaldır", false)
            btnNoPhoto.setOnClickListener {
                saveAll()
                photoFile().delete()
                render()
            }
            c4.addView(btnNoPhoto)
        }

        val btnStart = button("BAŞLAT", true)
        btnStart.setOnClickListener { start() }
        root.addView(btnStart)

        val btnStop = button("DURDUR", false)
        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Durduruldu", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnStop)

        val help = label(
            "Ç butonu: kısa bas = çevir, çift dokun = otomatik modu aç/kapat, " +
                "uzun bas = çevrilecek alanı çiz. " +
                "Çıkan bir çeviriye uzun basarsan o yazı bir daha çevrilmez.",
            14f, false
        )
        help.setPadding(dp(4), dp(16), dp(4), 0)
        root.addView(help)

        loadAll()
        ready = true

        rgBubbleShape.setOnCheckedChangeListener { _, id ->
            prefs.bubbleShape = when (id) {
                402 -> "rounded"
                403 -> "square"
                else -> "circle"
            }
            refreshRunningBubble()
        }
        sbBubbleSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.bubbleSize = progress + 40
                tvBubbleSize.text = "${prefs.bubbleSize} dp"
                if (fromUser) refreshRunningBubble()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        sbBubbleOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.bubbleOpacity = progress + 35
                tvBubbleOpacity.text = "%${prefs.bubbleOpacity}"
                if (fromUser) refreshRunningBubble()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        sbAutoInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private val intervals = intArrayOf(250, 400, 650, 1000)
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.autoIntervalMs = intervals[progress.coerceIn(0, intervals.lastIndex)]
                tvAutoInterval.text = "${prefs.autoIntervalMs} ms"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        etBubbleLabel.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.bubbleText = etBubbleLabel.text.toString()
                refreshRunningBubble()
            }
        }

        g3.setOnCheckedChangeListener { _, id ->
            val n = id - 301
            if (n in Themes.names.indices && n != prefs.theme) {
                saveAll()
                prefs.theme = n
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ready) loadAll()
    }

    override fun onPause() {
        if (ready) saveAll()
        super.onPause()
    }

    private fun loadAll() {
        rbOffline.isChecked = prefs.engine != "deepl" && prefs.engine != "gemini"
        rbDeepl.isChecked = prefs.engine == "deepl"
        rbGemini.isChecked = prefs.engine == "gemini"
        etDeepl.setText(prefs.deeplKey)
        etGemini.setText(prefs.geminiKey)
        etModel.setText(prefs.geminiModel)
        swAuto.isChecked = prefs.auto
        rbArea.isChecked = prefs.mode == "area"
        rbFull.isChecked = prefs.mode != "area"
        swTts.isChecked = prefs.tts
        swMultiOcr.isChecked = prefs.multilingualOcr
        swNames.isChecked = prefs.protectNames
        rgBubbleShape.check(when (prefs.bubbleShape) {
            "rounded" -> 402
            "square" -> 403
            else -> 401
        })
        sbBubbleSize.progress = prefs.bubbleSize - 40
        sbBubbleOpacity.progress = prefs.bubbleOpacity - 35
        sbAutoInterval.progress = when {
            prefs.autoIntervalMs <= 300 -> 0
            prefs.autoIntervalMs <= 500 -> 1
            prefs.autoIntervalMs <= 800 -> 2
            else -> 3
        }
        etBubbleLabel.setText(prefs.bubbleText)
        tvBubbleSize.text = "${prefs.bubbleSize} dp"
        tvBubbleOpacity.text = "%${prefs.bubbleOpacity}"
        tvAutoInterval.text = "${prefs.autoIntervalMs} ms"
    }

    private fun saveAll() {
        prefs.engine = when {
            rbGemini.isChecked -> "gemini"
            rbDeepl.isChecked -> "deepl"
            else -> "offline"
        }
        prefs.deeplKey = etDeepl.text.toString()
        prefs.geminiKey = etGemini.text.toString()
        val m = etModel.text.toString().trim()
        prefs.geminiModel = if (m.isEmpty()) "gemini-3.1-flash-lite" else m
        prefs.auto = swAuto.isChecked
        prefs.mode = if (rbArea.isChecked) "area" else "full"
        prefs.tts = swTts.isChecked
        prefs.multilingualOcr = swMultiOcr.isChecked
        prefs.protectNames = swNames.isChecked
        prefs.bubbleShape = when (rgBubbleShape.checkedRadioButtonId) {
            402 -> "rounded"
            403 -> "square"
            else -> "circle"
        }
        prefs.bubbleSize = sbBubbleSize.progress + 40
        prefs.bubbleOpacity = sbBubbleOpacity.progress + 35
        prefs.autoIntervalMs = intArrayOf(250, 400, 650, 1000)[sbAutoInterval.progress.coerceIn(0, 3)]
        prefs.bubbleText = etBubbleLabel.text.toString()
    }


    private fun showTranslationHistory() {
        val entries = prefs.translationHistory()
        if (entries.isEmpty()) {
            Toast.makeText(this, "Henüz kayıtlı çeviri yok", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { (original, translated) ->
            "Orijinal: ${original.take(110)}\nTürkçe: ${translated.take(110)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Son çeviriler · seçerek kopyala")
            .setItems(labels) { _, which ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Çeviri", entries[which].second)
                )
                Toast.makeText(this, "Türkçe çeviri kopyalandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun refreshRunningBubble() {
        try {
            startService(Intent(this, OverlayService::class.java).setAction("com.ekranceviri.REFRESH_BUBBLE"))
        } catch (_: Exception) {
            // The style is saved and will be applied the next time the overlay starts.
        }
    }

    private fun start() {
        saveAll()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "İzni aç, sonra geri dönüp BAŞLAT'a bas", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (prefs.engine == "deepl" && prefs.deeplKey.isBlank()) {
            Toast.makeText(this, "DeepL anahtarını yaz", Toast.LENGTH_LONG).show()
            return
        }
        if (prefs.engine == "gemini" && prefs.geminiKey.isBlank()) {
            Toast.makeText(this, "Gemini anahtarını yaz", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    private fun testGemini() {
        saveAll()
        val key = prefs.geminiKey
        if (key.isBlank()) {
            Toast.makeText(this, "Önce Gemini anahtarını yaz", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Test ediliyor...", Toast.LENGTH_SHORT).show()
        val model = prefs.geminiModel
        Thread {
            val t0 = System.currentTimeMillis()
            val out = Gemini.translate(key, model, listOf("Hello, how are you today?"))
            val ms = System.currentTimeMillis() - t0
            runOnUiThread {
                val msg = if (out == null) {
                    "HATA: ${Gemini.lastError}\n\nModel: $model"
                } else {
                    "Çalışıyor ($ms ms)\nİstenen: $model\nGoogle yanıtı: ${Gemini.lastModelVersion}\n\n${out[0]}"
                }
                AlertDialog.Builder(this)
                    .setTitle("Gemini testi")
                    .setMessage(msg)
                    .setPositiveButton("Tamam", null)
                    .show()
            }
        }.start()
    }

    private fun savePhoto(uri: Uri) {
        try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                Toast.makeText(this, "Fotoğraf okunamadı", Toast.LENGTH_SHORT).show()
                return
            }
            var s = 1
            while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, o2)
            }
            if (bmp == null) {
                Toast.makeText(this, "Fotoğraf okunamadı", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                FileOutputStream(photoFile()).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
            render()
        } catch (e: Exception) {
            Toast.makeText(this, "Fotoğraf yüklenemedi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 200 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) savePhoto(uri)
            return
        }
        if (requestCode == 201 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                Toast.makeText(this, "Seçilen dosya açılamadı", Toast.LENGTH_LONG).show()
                return
            }
            Toast.makeText(this, "HY-MT dosyası kontrol ediliyor ve yükleniyor…", Toast.LENGTH_LONG).show()
            HyMtModelManager(this).importArchive(input, object : HyMtModelManager.Listener {
                override fun onProgress(progress: HyMtModelManager.Progress) {
                    runOnUiThread {
                        val percent = if (progress.total > 0) {
                            (progress.downloaded * 100 / progress.total).toInt().coerceIn(0, 100)
                        } else 0
                        // Progress is also reflected in the persistent notification/toast below.
                    }
                }
                override fun onComplete(success: Boolean, message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        render()
                    }
                }
            })
            return
        }
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            stopService(Intent(this, OverlayService::class.java))
            val i = Intent(this, OverlayService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
