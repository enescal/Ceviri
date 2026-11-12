package com.ekranceviri

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads the verified HY-MT ONNX package used by RTranslator 3.0.
 * The archive is streamed to disk and only the inference graph and tokenizer
 * are extracted. No model-sized byte arrays are allocated.
 */
class HyMtModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, "Translation/HY-MT")

    data class Progress(val downloaded: Long, val total: Long, val message: String)

    interface Listener {
        fun onProgress(progress: Progress)
        fun onComplete(success: Boolean, message: String)
    }

    fun isInstalled(): Boolean {
        val model = File(modelDir, "model_int8_final.onnx")
        val tokenizer = File(modelDir, "tokenizer.json")
        val hasWeights = model.isFile && model.length() > 100_000_000L ||
            (modelDir.listFiles()?.any { it.isFile && it != tokenizer && it != model && it.length() > 100_000_000L } == true)
        return model.isFile && model.length() > 1_000_000L && hasWeights &&
            tokenizer.isFile && tokenizer.length() > 100_000L
    }

    fun download(listener: Listener) {
        Thread({
            var connection: HttpURLConnection? = null
            val archive = File(appContext.filesDir, "HY-MT.zip.part")
            try {
                modelDir.mkdirs()
                listener.onProgress(Progress(0, ARCHIVE_SIZE, "HY-MT indiriliyor…"))
                connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("Accept-Encoding", "identity")
                    connect()
                }
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Model sunucusu HTTP $code yanıtı verdi")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: ARCHIVE_SIZE
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                BufferedInputStream(connection.inputStream, 1024 * 1024).use { input ->
                    FileOutputStream(archive).use { raw ->
                        BufferedOutputStream(raw, 1024 * 1024).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var read: Int
                            var lastReport = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 500) {
                                    listener.onProgress(Progress(downloaded, total, "HY-MT indiriliyor…"))
                                    lastReport = now
                                }
                            }
                            output.flush()
                            raw.fd.sync()
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(ARCHIVE_SHA256, ignoreCase = true)) {
                    throw IllegalStateException("İndirilen modelin SHA-256 kontrolü başarısız")
                }
                listener.onProgress(Progress(total, total, "Model dosyaları açılıyor…"))
                extractRequiredFiles(archive, listener)
                if (!isInstalled()) throw IllegalStateException("Model dosyaları eksik veya bozuk")
                archive.delete()
                listener.onComplete(true, "HY-MT modeli indirildi ve doğrulandı.")
            } catch (e: Exception) {
                archive.delete()
                File(modelDir, "model_int8_final.onnx.part").delete()
                File(modelDir, "tokenizer.json.part").delete()
                listener.onComplete(false, e.message ?: "Model indirilemedi")
            } finally {
                connection?.disconnect()
            }
        }, "HY-MT-model-download").start()
    }


    fun importArchive(source: java.io.InputStream, listener: Listener) {
        Thread({
            val archive = File(appContext.filesDir, "HY-MT-import.zip.part")
            try {
                modelDir.mkdirs()
                listener.onProgress(Progress(0, ARCHIVE_SIZE, "HY-MT dosyası kopyalanıyor…"))
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                source.use { input ->
                    BufferedInputStream(input, 1024 * 1024).use { buffered ->
                        FileOutputStream(archive).use { raw ->
                            BufferedOutputStream(raw, 1024 * 1024).use { output ->
                                val buffer = ByteArray(1024 * 1024)
                                var read: Int
                                var lastReport = 0L
                                while (buffered.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    digest.update(buffer, 0, read)
                                    copied += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport >= 500) {
                                        listener.onProgress(Progress(copied, ARCHIVE_SIZE, "HY-MT dosyası kopyalanıyor…"))
                                        lastReport = now
                                    }
                                }
                                output.flush()
                                raw.fd.sync()
                            }
                        }
                    }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(ARCHIVE_SHA256, ignoreCase = true)) {
                    throw IllegalStateException("Seçilen ZIP dosyası HY-MT arşivi değil veya eksik. SHA-256 kontrolü başarısız.")
                }
                listener.onProgress(Progress(copied, copied, "Model dosyaları açılıyor…"))
                extractRequiredFiles(archive, listener)
                if (!isInstalled()) throw IllegalStateException("Model dosyaları eksik veya bozuk")
                archive.delete()
                listener.onComplete(true, "HY-MT dosyadan yüklendi ve doğrulandı.")
            } catch (e: Exception) {
                archive.delete()
                listener.onComplete(false, e.message ?: "HY-MT dosyadan yüklenemedi")
            }
        }, "HY-MT-model-import").start()
    }

    private fun extractRequiredFiles(archive: File, listener: Listener) {
        var modelDone = false
        var tokenizerDone = false
        var externalFiles = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive), 1024 * 1024)).use { zip ->
            var entry = zip.nextEntry
            val buffer = ByteArray(1024 * 1024)
            while (entry != null) {
                if (!entry.isDirectory) {
                    // ONNX quantization stores large tensors in external-data sidecar files.
                    // Extract every verified archive file, not only the graph and tokenizer.
                    val name = entry.name.substringAfterLast("/")
                    require(name.isNotBlank() && name != "." && name != "..") { "Model arşivinde geçersiz dosya adı" }
                    val isModel = name == "model_int8_final.onnx"
                    val isTokenizer = name == "tokenizer.json"
                    val target = File(modelDir, if (isModel || isTokenizer) "$name.part" else name)
                    require(target.canonicalPath.startsWith(modelDir.canonicalPath + File.separator)) {
                        "Model arşivinde güvenli olmayan dosya yolu"
                    }
                    target.parentFile?.mkdirs()
                    var written = 0L
                    FileOutputStream(target).use { raw ->
                        BufferedOutputStream(raw, 1024 * 1024).use { output ->
                            var read: Int
                            while (zip.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                written += read
                            }
                            output.flush()
                            raw.fd.sync()
                        }
                    }
                    if (isModel && written > 1_000_000L) {
                        modelDone = target.renameTo(File(modelDir, name))
                    } else if (isTokenizer && written > 100_000L) {
                        tokenizerDone = target.renameTo(File(modelDir, name))
                    } else if (isModel || isTokenizer) {
                        target.delete()
                    } else if (written > 0L) {
                        externalFiles++
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!modelDone || !tokenizerDone) {
            throw IllegalStateException("HY-MT arşivinde gerekli ONNX/tokenizer dosyaları bulunamadı")
        }
        if (externalFiles == 0 && File(modelDir, "model_int8_final.onnx").length() <= 100_000_000L) {
            throw IllegalStateException("HY-MT ONNX ağırlık dosyaları eksik")
        }
    }
    companion object {
        const val ARCHIVE_SIZE = 1_926_506_894L
        const val ARCHIVE_URL = "https://github.com/niedev/OnnxModelsEnhancer/releases/download/v1.0.0-beta/HY-MT.zip"
        const val ARCHIVE_SHA256 = "15d5dd9b0634fa0daac27fd50a06ec46c940eaadac2409d51361d3aa0ce3aff8"
    }
}
