package com.ekranceviri

import android.content.Context
import android.app.ActivityManager
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Paths
import java.util.Locale

/**
 * HY-MT 1.5 inference adapter based on the public RTranslator 3.0 HY-MT ONNX path.
 * Uses greedy decoding (beam size 1) to keep screen-translation latency and RAM bounded.
 */
object HyMtEngine {
    private const val LAYERS = 32
    private const val KV_HEADS = 4
    private const val HEAD_SIZE = 128
    private const val MAX_NEW_TOKENS = 256
    private const val EOS_TOKEN = "<｜hy_place▁holder▁no▁2｜>"
    private const val START_TOKEN = "<｜hy_begin▁of▁sentence｜>"
    private const val USER_TOKEN = "<｜hy_User｜>"
    private const val END_TURN_TOKEN = "<｜hy_end▁of▁turn｜>"
    private const val ASSISTANT_TOKEN = "<｜hy_Assistant｜>"

    @Volatile private var environment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: HuggingFaceTokenizer? = null
    private val lock = Any()

    // Avoid loading this very large native model when Android reports low free RAM.
    private fun hasInferenceMemory(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        return !memory.lowMemory && memory.availMem >= 2_500_000_000L
    }

    fun isReady(context: Context): Boolean {
        if (!hasInferenceMemory(context)) return false
        val dir = java.io.File(context.applicationContext.filesDir, "Translation/HY-MT")
        val model = java.io.File(dir, "model_int8_final.onnx")
        val tokenizer = java.io.File(dir, "tokenizer.json")
        val hasWeights = model.isFile && model.length() > 100_000_000L ||
            (dir.listFiles()?.any { it.isFile && it != tokenizer && it != model && it.length() > 100_000_000L } == true)
        return model.isFile && model.length() > 1_000_000L && hasWeights &&
            tokenizer.isFile && tokenizer.length() > 100_000L
    }

    private fun ensureLoaded(context: Context) {
        if (session != null && tokenizer != null) return
        synchronized(lock) {
            if (session != null && tokenizer != null) return
            val dir = java.io.File(context.applicationContext.filesDir, "Translation/HY-MT")
            val model = java.io.File(dir, "model_int8_final.onnx")
            val vocab = java.io.File(dir, "tokenizer.json")
            check(model.isFile && vocab.isFile) { "HY-MT modeli indirilmemiş" }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(2))
                setInterOpNumThreads(1)
                setMemoryPatternOptimization(false)
                setCPUArenaAllocator(true)
            }
            try {
                val loaded = env.createSession(model.absolutePath, options)
                val tok = HuggingFaceTokenizer.newInstance(Paths.get(vocab.absolutePath))
                environment = env
                session = loaded
                tokenizer = tok
            } finally {
                options.close()
            }
        }
    }

    fun translate(context: Context, sourceCode: String, text: String): String {
        if (text.isBlank()) return text
        ensureLoaded(context)
        synchronized(lock) {
            val env = checkNotNull(environment)
            val ort = checkNotNull(session)
            val tok = checkNotNull(tokenizer)
            val language = sourceCode.substringBefore("-").lowercase(Locale.ROOT)
            val targetName = "Turkish"
            val prompt = if (language == "zh") {
                START_TOKEN + USER_TOKEN + "将以下文本翻译为土耳其语，注意只需要输出翻译后的结果，不要额外解释：\n\n" + text + END_TURN_TOKEN + ASSISTANT_TOKEN
            } else {
                START_TOKEN + USER_TOKEN + "Translate the following segment into " + targetName + ", without additional explanation.\n\n" + text + END_TURN_TOKEN + ASSISTANT_TOKEN
            }
            val encoded = tok.encode(arrayOf(prompt), false, false)
            val promptIds = encoded.ids.map { it.toInt() }.toIntArray()
            check(promptIds.isNotEmpty()) { "HY-MT metni tokenlara ayıramadı" }
            val eos = tok.encode(arrayOf(EOS_TOKEN), false, false).ids.first().toInt()
            val inputNames = ort.inputNames
            val outputNames = ort.outputNames
            check("input_ids" in inputNames && "attention_mask" in inputNames) {
                "HY-MT ONNX giriş şeması beklenenden farklı: ${inputNames.joinToString()}"
            }
            val missingCacheInputs = (0 until LAYERS).flatMap { layer ->
                listOf("past_key_values.$layer.key", "past_key_values.$layer.value")
            }.filterNot { it in inputNames }
            val missingCacheOutputs = (0 until LAYERS).flatMap { layer ->
                listOf("present.$layer.key", "present.$layer.value")
            }.filterNot { it in outputNames }
            check("logits" in outputNames && missingCacheInputs.isEmpty() && missingCacheOutputs.isEmpty()) {
                "HY-MT ONNX önbellek şeması uyumsuz. Eksik girdiler: " + missingCacheInputs.joinToString() + "; eksik çıktılar: " + missingCacheOutputs.joinToString()
            }
            val hasPositionIds = "position_ids" in inputNames
            val generated = ArrayList<Long>()
            var currentIds = promptIds.map { it.toLong() }.toLongArray()
            var attentionLength = promptIds.size
            var previous: OrtSession.Result? = null
            val emptyCache = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, KV_HEADS.toLong(), 0, HEAD_SIZE.toLong()))
            try {
                val tokenBudget = (text.length * 2).coerceIn(48, MAX_NEW_TOKENS)
                for (step in 0 until tokenBudget) {
                    val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(currentIds), longArrayOf(1, currentIds.size.toLong()))
                    val mask = LongArray(attentionLength) { 1L }
                    val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, attentionLength.toLong()))
                    val inputs = HashMap<String, OnnxTensor>()
                    inputs["input_ids"] = idsTensor
                    inputs["attention_mask"] = maskTensor
                    var positionTensor: OnnxTensor? = null
                    if (hasPositionIds) {
                        val positions = if (currentIds.size == promptIds.size) {
                            LongArray(currentIds.size) { it.toLong() }
                        } else longArrayOf((attentionLength - 1).toLong())
                        positionTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(positions), longArrayOf(1, positions.size.toLong()))
                        inputs["position_ids"] = positionTensor
                    }
                    for (layer in 0 until LAYERS) {
                        val keyName = "past_key_values.$layer.key"
                        val valueName = "past_key_values.$layer.value"
                        if (previous == null) {
                            inputs[keyName] = emptyCache
                            inputs[valueName] = emptyCache
                        } else {
                            inputs[keyName] = previous.get("present.$layer.key").get() as OnnxTensor
                            inputs[valueName] = previous.get("present.$layer.value").get() as OnnxTensor
                        }
                    }
                    val next = try { ort.run(inputs) } finally {
                        idsTensor.close()
                        maskTensor.close()
                        positionTensor?.close()
                    }
                    previous?.close()
                    previous = next
                    val logitsTensor = next.get("logits").get() as OnnxTensor
                    val logitsValue = logitsTensor.value
                    val last = when (logitsValue) {
                        is Array<*> -> {
                            val first = logitsValue[0]
                            when (first) {
                                is Array<*> -> (first[first.lastIndex] as FloatArray)
                                is FloatArray -> first
                                else -> error("HY-MT logits tensör biçimi desteklenmiyor: ${first?.javaClass?.name}")
                            }
                        }
                        else -> error("HY-MT logits çıkışı beklenmeyen türde: ${logitsValue?.javaClass?.name}")
                    }
                    var bestId = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (i in last.indices) {
                        if (last[i] > bestScore) { bestScore = last[i]; bestId = i }
                    }
                    if (bestId == eos) break
                    generated.add(bestId.toLong())
                    currentIds = longArrayOf(bestId.toLong())
                    attentionLength++
                }
                val output = tok.decode(generated.toLongArray()).trim()
                check(output.isNotBlank()) { "HY-MT boş çeviri üretti" }
                return output
            } finally {
                previous?.close()
                emptyCache.close()
            }
        }
    }
}
