package com.assistant.taba9

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.sqrt

data class DocumentArchetype(
    val id: String,
    val category: String,
    val isPersonCentric: Boolean,
    val regexPatterns: List<Regex>,
    val strongPhrases: List<String>,
    val keywords: List<String>,
    val semanticAnchor: String,
    var anchorVector: FloatArray = FloatArray(0)
)

data class ClassificationMatch(
    val archetypeId: String,
    val category: String,
    val isPersonCentric: Boolean,
    val score: Float,
    val matchedEvidence: String
)

class OfflineAiEngine(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val vocab = HashMap<String, Long>()
    private val archetypes = mutableListOf<DocumentArchetype>()

    fun initEngine() {
        loadVocab()
        initOnnx()
        loadDocumentKnowledge()
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

    private fun loadDocumentKnowledge() {
        archetypes.clear()
        val jsonStr = context.assets.open("document_knowledge.json").bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)
        val arr = root.getJSONArray("archetypes")

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val regexArr = obj.optJSONArray("regex_patterns")
            val regexList = mutableListOf<Regex>()
            if (regexArr != null) {
                for (j in 0 until regexArr.length()) {
                    regexList.add(Regex(regexArr.getString(j), RegexOption.IGNORE_CASE))
                }
            }

            val phrasesArr = obj.getJSONArray("strong_phrases")
            val phrases = (0 until phrasesArr.length()).map { phrasesArr.getString(it).lowercase() }

            val kwArr = obj.getJSONArray("keywords")
            val kws = (0 until kwArr.length()).map { kwArr.getString(it).lowercase() }

            val anchor = obj.getString("semantic_anchor")
            val arch = DocumentArchetype(
                id = obj.getString("id"),
                category = obj.getString("category"),
                isPersonCentric = obj.getBoolean("is_person_centric"),
                regexPatterns = regexList,
                strongPhrases = phrases,
                keywords = kws,
                semanticAnchor = anchor,
                anchorVector = embedText(anchor)
            )
            archetypes.add(arch)
        }
    }

    fun evaluateDocument(fullText: String, origFileName: String): ClassificationMatch {
        val combined = "$origFileName\n$fullText"
        val lower = combined.lowercase()
        val docVec = embedText(lower.take(1500))

        var bestArch: DocumentArchetype? = null
        var bestScore = 0f
        var bestReason = ""

        for (arch in archetypes) {
            var score = 0f
            val reasons = mutableListOf<String>()

            // 1. Exact ID/Document Regex Pattern Match (+5.0)
            for (rx in arch.regexPatterns) {
                val m = rx.find(combined)
                if (m != null) {
                    score += 5.0f
                    reasons.add("pattern:${m.value}")
                }
            }

            // 2. Strong Multi-Word Phrase Match (+2.5 each)
            for (phrase in arch.strongPhrases) {
                if (lower.contains(phrase)) {
                    score += 2.5f
                    if (reasons.size < 3) reasons.add("\"$phrase\"")
                }
            }

            // 3. Domain Keyword Hits (+0.65 each)
            for (kw in arch.keywords) {
                if (lower.contains(kw)) {
                    score += 0.65f
                    if (reasons.size < 3) reasons.add(kw)
                }
            }

            // 4. Semantic Vector Cosine Similarity (+0.0 to 1.6)
            val semSim = cosineSimilarity(docVec, arch.anchorVector)
            if (semSim > 0.25f) {
                score += (semSim * 1.6f)
            }

            if (score > bestScore) {
                bestScore = score
                bestArch = arch
                bestReason = reasons.joinToString(", ").ifEmpty { "semantic:${"%.2f".format(semSim)}" }
            }
        }

        // Special Disambiguation for 10th vs 12th vs Graduation Marksheets
        if (bestArch != null && bestArch.category == "Marksheets_And_Degrees") {
            val refinedId = refineMarksheetLevel(lower, combined, bestArch.id)
            return ClassificationMatch(
                archetypeId = refinedId,
                category = bestArch.category,
                isPersonCentric = true,
                score = bestScore,
                matchedEvidence = bestReason
            )
        }

        // Special Disambiguation for Subject Notes & Study Material
        if (bestArch != null && bestArch.id == "Subject_Lecture_Notes") {
            val subjectTag = detectSpecificSubject(lower)
            return ClassificationMatch(
                archetypeId = subjectTag,
                category = "Academic_And_Research",
                isPersonCentric = false,
                score = bestScore,
                matchedEvidence = bestReason
            )
        }

        if (bestArch == null || bestScore < 0.85f) {
            return ClassificationMatch(
                archetypeId = "General_Document",
                category = "General_Documents",
                isPersonCentric = false,
                score = bestScore,
                matchedEvidence = "low_confidence"
            )
        }

        return ClassificationMatch(
            archetypeId = bestArch.id,
            category = bestArch.category,
            isPersonCentric = bestArch.isPersonCentric,
            score = bestScore,
            matchedEvidence = bestReason
        )
    }

    private fun refineMarksheetLevel(lower: String, raw: String, initialId: String): String {
        if (lower.contains("b.tech") || lower.contains("btech") || lower.contains("bachelor") ||
            lower.contains("sgpa") || lower.contains("cgpa") || lower.contains("semester") || lower.contains("aktu")
        ) {
            val semMatch = Regex("(?:semester|sem)\\s*[:\\.-]?\\s*([1-8]|viii|vii|vi|v|iv|iii|ii|i)\\b", RegexOption.IGNORE_CASE).find(raw)
            return if (semMatch != null) {
                "Graduation_Sem_${semMatch.groupValues[1].uppercase()}_Marksheet"
            } else {
                "Graduation_Marksheet"
            }
        }
        if (lower.contains("intermediate") || lower.contains("senior school") || lower.contains("senior secondary") ||
            lower.contains("class xii") || lower.contains("12th") || lower.contains("इण्टरमीडिएट") ||
            (lower.contains("physics") && lower.contains("chemistry") && !lower.contains("social science"))
        ) {
            return "12th_Marksheet"
        }
        if (lower.contains("high school") || lower.contains("secondary school") || lower.contains("matriculation") ||
            lower.contains("class x") || lower.contains("10th") || lower.contains("हाई स्कूल") || lower.contains("social science")
        ) {
            return "10th_Marksheet"
        }
        return initialId
    }

    private fun detectSpecificSubject(lower: String): String {
        return when {
            lower.contains("operating system") || lower.contains("deadlock") || lower.contains("cpu scheduling") -> "OS_Lecture_Notes"
            lower.contains("data structure") || lower.contains("algorithm") || lower.contains("binary tree") || lower.contains("dynamic programming") -> "DSA_Algorithms_Notes"
            lower.contains("database") || lower.contains("dbms") || lower.contains("normalization") || lower.contains("sql") -> "DBMS_Notes"
            lower.contains("computer network") || lower.contains("tcp/ip") || lower.contains("osi model") -> "Computer_Networks_Notes"
            lower.contains("compiler") || lower.contains("automata") || lower.contains("turing machine") -> "TOC_Compiler_Notes"
            lower.contains("machine learning") || lower.contains("computer vision") || lower.contains("lane detection") || lower.contains("neural network") -> "AI_ML_Notes"
            lower.contains("discrete") || lower.contains("linear algebra") || lower.contains("probability") -> "Engineering_Maths_Notes"
            else -> "Subject_Study_Notes"
        }
    }

    private fun embedText(text: String): FloatArray {
        val env = ortEnv
        val session = ortSession
        if (env != null && session != null && vocab.isNotEmpty()) {
            try {
                val tokens = tokenizeWordPiece(text)
                val seqLen = tokens.size.toLong()
                val mask = LongArray(tokens.size) { 1L }
                val types = LongArray(tokens.size) { 0L }
                val shape = longArrayOf(1, seqLen)

                val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape)
                val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
                val typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape)

                val inputs = mapOf(
                    "input_ids" to idTensor,
                    "attention_mask" to maskTensor,
                    "token_type_ids" to typeTensor
                )

                session.run(inputs).use { results ->
                    @Suppress("UNCHECKED_CAST")
                    val output = results[0].value as Array<Array<FloatArray>>
                    val tokEmb = output[0]
                    val pooled = FloatArray(384)
                    for (t in tokEmb) {
                        for (d in 0 until 384) pooled[d] += t[d]
                    }
                    var norm = 0f
                    for (d in 0 until 384) {
                        pooled[d] /= tokEmb.size.toFloat()
                        norm += pooled[d] * pooled[d]
                    }
                    norm = sqrt(norm) + 1e-8f
                    for (d in 0 until 384) pooled[d] /= norm

                    idTensor.close()
                    maskTensor.close()
                    typeTensor.close()
                    return pooled
                }
            } catch (_: Exception) {}
        }
        val fallback = FloatArray(384)
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        for (w in words) {
            val idx = (w.hashCode() and 0x7FFFFFFF) % 384
            fallback[idx] += 1f
        }
        return fallback
    }

    private fun tokenizeWordPiece(text: String): LongArray {
        val cls = vocab["[CLS]"] ?: 101L
        val sep = vocab["[SEP]"] ?: 102L
        val unk = vocab["[UNK]"] ?: 100L
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.take(96)
        val ids = mutableListOf(cls)
        for (w in words) ids.add(vocab[w] ?: unk)
        ids.add(sep)
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
