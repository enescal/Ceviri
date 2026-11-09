package com.ekranceviri

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var rbOffline: RadioButton
    private lateinit var rbDeepl: RadioButton
    private lateinit var etKey: EditText
    private lateinit var swAuto: Switch
    private lateinit var rbFull: RadioButton
    private lateinit var rbArea: RadioButton
    private lateinit var swTts: Switch
    private lateinit var swNames: Switch

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, size: Float, bold: Boolean): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(Color.BLACK)
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(0, dp(16), 0, dp(4))
        return tv
    }

    private fun radio(t: String, id: Int): RadioButton {
        val rb = RadioButton(this)
        rb.text = t
        rb.id = id
        return rb
    }

    private fun sw(t: String): Switch {
        val s = Switch(this)
        s.text = t
        s.setPadding(0, dp(8), 0, dp(8))
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(40))
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("Ekran Çeviri", 26f, true))

        root.addView(label("Çeviri motoru", 17f, true))
        val g1 = RadioGroup(this)
        rbOffline = radio("İnternetsiz (ücretsiz, daha sade Türkçe)", 101)
        rbDeepl = radio("DeepL (daha doğal Türkçe, internet + ücretsiz anahtar)", 102)
        g1.addView(rbOffline)
        g1.addView(rbDeepl)
        root.addView(g1)
        etKey = EditText(this)
        etKey.hint = "DeepL anahtarı (:fx ile biter)"
        etKey.setSingleLine(true)
        root.addView(etKey)

        root.addView(label("Ne çevrilsin?", 17f, true))
        val g2 = RadioGroup(this)
        rbFull = radio("Tüm ekran", 201)
        rbArea = radio("Seçtiğim alan (Ç butonuna uzun basıp çiz)", 202)
        g2.addView(rbFull)
        g2.addView(rbArea)
        root.addView(g2)

        root.addView(label("Seçenekler", 17f, true))
        swAuto = sw("Otomatik mod: Ç'ye bir kez bas, ekran değiştikçe kendisi çevirsin")
        swNames = sw("İsimleri koru (DeepL'de çalışır)")
        swTts = sw("Çeviriyi Türkçe sesle oku (seçili alan modunda)")
        root.addView(swAuto)
        root.addView(swNames)
        root.addView(swTts)

        val btnClear = Button(this)
        btnClear.text = "\"Bir daha çevirme\" listesini temizle"
        btnClear.setOnClickListener {
            prefs.clearIgnored()
            Toast.makeText(this, "Liste temizlendi", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnClear)

        val btnStart = Button(this)
        btnStart.text = "BAŞLAT"
        btnStart.setOnClickListener { start() }
        root.addView(btnStart)

        val btnStop = Button(this)
        btnStop.text = "DURDUR"
        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Durduruldu", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnStop)

        val help = label(
            "Kullanım: BAŞLAT'a bas, izinleri ver, çevirmek istediğin uygulamaya geç. " +
                "Ç butonuna kısa bas: çevir. Uzun bas: çevrilecek alanı çiz. " +
                "Çıkan bir çeviriye uzun basarsan o yazı bir daha çevrilmez.",
            14f, false
        )
        root.addView(help)
    }

    override fun onResume() {
        super.onResume()
        loadAll()
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    private fun loadAll() {
        rbOffline.isChecked = prefs.engine != "deepl"
        rbDeepl.isChecked = prefs.engine == "deepl"
        etKey.setText(prefs.deeplKey)
        swAuto.isChecked = prefs.auto
        rbArea.isChecked = prefs.mode == "area"
        rbFull.isChecked = prefs.mode != "area"
        swTts.isChecked = prefs.tts
        swNames.isChecked = prefs.protectNames
    }

    private fun saveAll() {
        prefs.engine = if (rbDeepl.isChecked) "deepl" else "offline"
        prefs.deeplKey = etKey.text.toString()
        prefs.auto = swAuto.isChecked
        prefs.mode = if (rbArea.isChecked) "area" else "full"
        prefs.tts = swTts.isChecked
        prefs.protectNames = swNames.isChecked
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
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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
