# Ekran Çeviri

Android için ekrandaki yazıları algılayıp Türkçeye çeviren, yüzen butonla çalışan ekran çeviri uygulaması.

## Özellikler

- Ekranın tamamını veya seçilen birden fazla alanı çevirme
- Ekranda duran yeşil **Ç** butonu
- Tek dokunuşla çeviri, çift dokunuşla otomatik çeviri, uzun dokunuşla alan seçimi
- Latin, Çince, Devanagari, Japonca ve Korece OCR (modeller Google Play Hizmetleri'nden indirilir)
- Çevrimdışı ML Kit çevirisi
- İsteğe bağlı DeepL ve Gemini çeviri motorları
- Otomatik modda sayfa kaydırmasını takip etme
- Türkçe sesli okuma
- Çeviri görünümü için altı tema ve isteğe bağlı arka plan fotoğrafı
- Belirli metinleri tekrar çevirmeme

## Kurulum

1. GitHub Actions bölümündeki başarılı **Build APK** çalıştırmasını aç.
2. **EkranCeviri-APK** adlı artifact'i indirip ZIP dosyasını çıkar.
3. İçindeki `app-debug.apk` dosyasını Android cihazına kur.
4. Uygulamayı açıp **BAŞLAT** düğmesine bas.
5. Android'in istediği diğer uygulamaların üzerinde gösterme ve ekran yakalama izinlerini onayla.

## Kullanım

- **Ç butonuna kısa bas:** Ekranı çevirir.
- **Ç butonuna çift dokun:** Otomatik çeviriyi açar/kapatır.
- **Ç butonuna uzun bas:** Çevrilecek alanları parmağınla çizip **Bitti**'ye bas.
- Çeviri kutusuna uzun bas: O metni bir daha çevirmeme listesine ekler.

## Çeviri motorları

- **İnternetsiz:** ML Kit çeviri dil paketini ilk kullanımda indirir; sonraki çeviriler cihazda yapılır. OCR modelleri de ilk kullanımda Google Play Hizmetleri üzerinden indirilebilir.
- **DeepL:** DeepL API anahtarı gerekir.
- **Gemini:** Google AI Studio API anahtarı gerekir. Model alanı boş bırakılırsa varsayılan model kullanılır.

API anahtarları cihazın uygulama ayarlarında saklanır. Bunları herkese açık depoya veya ekran görüntülerine koyma.

## Teknik bilgiler

- Paket: `com.ekranceviri`
- Minimum Android: 8.0 (API 26)
- Hedef Android: 14 (API 34)
- Derleme: Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Java 17

## Bilinen sınırlar

- Ekran yakalama Android'in kendi izin penceresiyle kullanıcı tarafından başlatılmalıdır.
- Bazı korumalı uygulamalar ve DRM içerikleri ekran yakalamayı engelleyebilir.
- OCR modellerinin ilk kurulumu için Google Play Hizmetleri ve internet bağlantısı gerekir. Modeller indirildikten sonra OCR cihaz üzerinde çalışır.
- OCR ve çeviri kalitesi metnin yazı tipine, çözünürlüğüne ve seçilen motora göre değişir.
- Otomatik mod, ekran hareketi bittikten sonra çeviri yapar; sürekli kayan içerikte beklemek gerekir.


## v1.3 düzeltmeleri
- Gemini varsayılan modeli güncel kararlı Flash sürümüne alındı.
- İstekler küçültüldü, sunucu yanıt süresi artırıldı ve ağ hataları daha anlaşılır gösterilir.
- OCR motorları yalnızca aynı metni aynı bölgede bulduklarında sonuçları birleştirir.
- Uygulama simgesine ve ayarlar ekranına sağlanan logo görseli eklendi.
