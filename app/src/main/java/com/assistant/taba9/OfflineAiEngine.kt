package com.assistant.taba9

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.sqrt

data class ParsedCommand(
    val intentId: String,
    val targetContact: String = "",
    val messageBody: String = "",
    val rawText: String = "",
    val confidence: Float = 0f
)

data class IntentRule(
    val id: String,
    val anchors: String,
    val keywords: List<String>,
    val regex: String,
    var vector: FloatArray = FloatArray(0)
)

data class FolderRule(
    val folderName: String,
    val anchors: String,
    val keywords: List<String>,
    var vector: FloatArray = FloatArray(0)
)

class OfflineAiEngine(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val vocab = HashMap<String, Long>()
    private val intentRules = mutableListOf<IntentRule>()
    private val folderRules = mutableListOf<FolderRule>()
    private var threshold = 0.38f

    fun initEngine() {
        loadVocab()
        initOnnx()
        loadRulesJson()
    }

    private fun loadVocab() {
        try {
            context.assets.open("vocab.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, token -> vocab[token.trim()] = idx.toLong() }
            }
        } catch (_: Exception) {}
    }

    private fun initOnnx() {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("model_quantized.onnx").readBytes()
            ortSession = ortEnv?.createSession(modelBytes)
        } catch (_: Exception) {}
    }

    private fun loadRulesJson() {
        val jsonStr = context.assets.open("rules.json").bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)
        threshold = root.optDouble("similarity_threshold", 0.38).toFloat()

        val intentsArr = root.getJSONArray("intents")
        for (i in 0 until intentsArr.length()) {
            val obj = intentsArr.getJSONObject(i)
            val kwArr = obj.getJSONArray("keywords")
            val kws = (0 until kwArr.length()).map { kwArr.getString(it).lowercase() }
            val rule = IntentRule(
                id = obj.getString("id"),
                anchors = obj.getString("anchors"),
                keywords = kws,
                regex = obj.optString("regex", "")
            )
            rule.vector = embedText(rule.anchors)
            intentRules.add(rule)
        }

        val foldersArr = root.getJSONArray("folder_rules")
        for (i in 0 until foldersArr.length()) {
            val obj = foldersArr.getJSONObject(i)
            val kwArr = obj.getJSONArray("keywords")
            val kws = (0 until kwArr.length()).map { kwArr.getString(it).lowercase() }
            val rule = FolderRule(
                folderName = obj.getString("folder_name"),
                anchors = obj.getString("anchors"),
                keywords = kws
            )
            rule.vector = embedText(rule.anchors)
            folderRules.add(rule)
        }
    }

    fun parseVoiceCommand(spokenText: String): ParsedCommand {
        val clean = spokenText.trim()
        val lower = clean.lowercase()
        val inputVec = embedText(lower)

        var bestIntent = "UNKNOWN"
        var bestScore = 0f
        var matchedRule: IntentRule? = null

        for (rule in intentRules) {
            val semSim = cosineSimilarity(inputVec, rule.vector)
            val kwHits = rule.keywords.count { lower.contains(it) }
            val kwBoost = (kwHits * 0.18f).coerceAtMost(0.45f)
            val totalScore = semSim + kwBoost

            if (totalScore > bestScore) {
                bestScore = totalScore
                bestIntent = rule.id
                matchedRule = rule
            }
        }

        if (bestScore < threshold || matchedRule == null) {
            return ParsedCommand("UNKNOWN", rawText = clean, confidence = bestScore)
        }

        var contact = ""
        var message = ""
        if (matchedRule.regex.isNotEmpty()) {
            val match = Regex(matchedRule.regex, RegexOption.IGNORE_CASE).find(clean)
            if (match != null) {
                contact = match.groupValues.getOrNull(1)?.trim() ?: ""
                message = match.groupValues.getOrNull(2)?.trim() ?: ""
            }
        }

        return ParsedCommand(
            intentId = bestIntent,
            targetContact = contact,
            messageBody = message,
            rawText = clean,
            confidence = bestScore
        )
    }

    fun classifyFileContent(snippet: String): String {
        val lower = snippet.lowercase()
        val fileVec = embedText(lower)

        var bestFolder = "Misc_Documents"
        var bestScore = 0.25f

        for (rule in folderRules) {
            val semSim = cosineSimilarity(fileVec, rule.vector)
            val kwHits = rule.keywords.count { lower.contains(it) }
            val totalScore = semSim + (kwHits * 0.12f)
            if (totalScore > bestScore) {
                bestScore = totalScore
                bestFolder = rule.folderName
            }
        }
        return bestFolder
    }

    private fun embedText(text: String): FloatArray {
        val env = ortEnv
        val session = ortSession
        if (env != null && session != null && vocab.isNotEmpty()) {
            try {
                val tokens = tokenizeWordPiece(text)
                val seqLen = tokens.size.toLong()
                val attentionMask = LongArray(tokens.size) { 1L }
                val tokenTypeIds = LongArray(tokens.size) { 0L }

                val shape = longArrayOf(1, seqLen)
                val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape)
                val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
                val typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)

                val inputs = mapOf(
                    "input_ids" to inputIdsTensor,
                    "attention_mask" to maskTensor,
                    "token_type_ids" to typeTensor
                )

                session.run(inputs).use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val output = results[0].value as Array<Array<FloatArray>>
                    val tokenEmbeddings = output[0]
                    val pooled = FloatArray(384)
                    for (t in tokenEmbeddings) {
                        for (d in 0 until 384) pooled[d] += t[d]
                    }
                    var norm = 0f
                    for (d in 0 until 384) {
                        pooled[d] /= tokenEmbeddings.size.toFloat()
                        norm += pooled[d] * pooled[d]
                    }
                    norm = sqrt(norm) + 1e-8f
                    for (d in 0 until 384) pooled[d] /= norm

                    inputIdsTensor.close()
                    maskTensor.close()
                    typeTensor.close()
                    return pooled
                }
            } catch (_: Exception) {}
        }
        // Character trigram hashing fallback (384-D) if ONNX is unavailable
        val fallback = FloatArray(384)
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        for (w in words) {
            val idx = (w.hashCode() and 0x7FFFFFFF) % 384
            fallback[idx] += 1f
        }
        return fallback
    }

    private fun tokenizeWordPiece(text: String): LongArray {
        val clsId = vocab["[CLS]"] ?: 101L
        val sepId = vocab["[SEP]"] ?: 102L
        val unkId = vocab["[UNK]"] ?: 100L
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.take(60)
        val ids = mutableListOf(clsId)
        for (w in words) {
            ids.add(vocab[w] ?: unkId)
        }
        ids.add(sepId)
        return ids.toLongArray()
    }

    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
        var dot = 0f
        var n1 = 0f
        var n2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            n1 += v1[i] * v1[i]
            n2 += v2[i] * v2[i]
        }
        return dot / (sqrt(n1) * sqrt(n2) + 1e-8f)
    }
}
