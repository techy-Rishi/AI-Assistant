package com.assistant.taba9

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.sqrt

data class ClassificationMatch(
    val archetypeId: String,
    val category: String,
    val isPersonCentric: Boolean,
    val score: Float,
    val matchedEvidence: String
)

class OfflineAiEngine(private val context: Context) {

    companion object {
        // Below this score a file is treated as "unclear" and left where it is.
        private const val MIN_CONFIDENCE = 2.0f
        private const val MAX_TOKENS = 128
        private const val KEYWORD_CAP = 3.25f

        private val REFINABLE_MARKSHEETS = setOf(
            "10th_HighSchool_Marksheet",
            "12th_Intermediate_Marksheet",
            "Graduation_Marksheet"
        )
    }

    private class Term(val label: String, val rx: Regex)

    private class Archetype(
        val id: String,
        val category: String,
        val isPersonCentric: Boolean,
        val idPatterns: List<Regex>,
        val strongPhrases: List<Term>,
        val keywords: List<Term>,
        var anchorVector: FloatArray = FloatArray(0)
    )

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val vocab = HashMap<String, Long>()
    private val archetypes = mutableListOf<Archetype>()
    private val termCache = HashMap<String, Regex>()

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var usingOnnx: Boolean = false
        private set

    @Volatile
    var initMessage: String = "Engine not initialised yet."
        private set

    @Synchronized
    fun initEngine() {
        if (isReady) return
        loadVocab()
        initOnnx()
        try {
            loadDocumentKnowledge()
            isReady = archetypes.isNotEmpty()
            initMessage = when {
                !isReady -> "document_knowledge.json has no archetypes."
                usingOnnx -> "Ready: ${archetypes.size} document types, semantic model ON, OCR English + Hindi."
                else -> "Ready: ${archetypes.size} document types, semantic model OFF (model/vocab missing), keyword mode only."
            }
        } catch (e: Exception) {
            isReady = false
            initMessage = "Knowledge base failed to load: ${e.message}"
        }
    }

    fun close() {
        try { ortSession?.close() } catch (_: Exception) {}
        ortSession = null
    }

    private fun loadVocab() {
        try {
            context.assets.open("vocab.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { idx, token -> vocab[token.trim()] = idx.toLong() }
            }
        } catch (_: Exception) {
        }
    }

    private fun initOnnx() {
        try {
            val env = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open("model_quantized.onnx").readBytes()
            ortEnv = env
            ortSession = env.createSession(modelBytes)
        } catch (_: Exception) {
            ortSession = null
        }
        usingOnnx = ortSession != null && vocab.isNotEmpty()
    }

    // Whole-word / whole-phrase matcher. Works for Latin and Devanagari text.
    private fun wordRegex(raw: String): Regex {
        val parts = raw.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val body = parts.joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{M}\\p{N}])$body(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
    }

    private fun toTerms(arr: org.json.JSONArray?): List<Term> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Term>()
        for (i in 0 until arr.length()) {
            val raw = arr.getString(i).trim().lowercase()
            if (raw.isNotEmpty()) out.add(Term(raw, wordRegex(raw)))
        }
        return out
    }

    private fun loadDocumentKnowledge() {
        archetypes.clear()
        val jsonStr = context.assets.open("document_knowledge.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(jsonStr).getJSONArray("archetypes")

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)

            // ID patterns are CASE-SENSITIVE on purpose (PAN, IFSC, passport etc. are uppercase).
            val idPatterns = mutableListOf<Regex>()
            val rxArr = obj.optJSONArray("regex_patterns")
            if (rxArr != null) {
                for (j in 0 until rxArr.length()) idPatterns.add(Regex(rxArr.getString(j)))
            }

            val arch = Archetype(
                id = obj.getString("id"),
                category = obj.getString("category"),
                isPersonCentric = obj.getBoolean("is_person_centric"),
                idPatterns = idPatterns,
                strongPhrases = toTerms(obj.optJSONArray("strong_phrases")),
                keywords = toTerms(obj.optJSONArray("keywords"))
            )
            arch.anchorVector = embedText(obj.getString("semantic_anchor")) ?: FloatArray(0)
            archetypes.add(arch)
        }
    }

    private fun unclassified(reason: String, score: Float = 0f) = ClassificationMatch(
        archetypeId = "General_Document",
        category = "General_Documents",
        isPersonCentric = false,
        score = score,
        matchedEvidence = reason
    )

    fun evaluateDocument(fullText: String, origFileName: String): ClassificationMatch {
        if (!isReady) return unclassified("engine_not_ready")

        val combined = "$origFileName\n$fullText"
        val lower = combined.lowercase()
        val docVec = embedText(lower.take(1500))

        var bestArch: Archetype? = null
        var bestScore = 0f
        var bestReason = ""

        for (arch in archetypes) {
            val reasons = mutableListOf<String>()

            // 1. Strong phrases (+2.5 each)
            var phraseScore = 0f
            for (t in arch.strongPhrases) {
                if (t.rx.containsMatchIn(lower)) {
                    phraseScore += 2.5f
                    if (reasons.size < 3) reasons.add("\"${t.label}\"")
                }
            }

            // 2. Keywords (+0.65 each, capped so generic words cannot dominate)
            var kwScore = 0f
            for (t in arch.keywords) {
                if (t.rx.containsMatchIn(lower)) {
                    kwScore += 0.65f
                    if (reasons.size < 3) reasons.add(t.label)
                }
            }
            kwScore = kwScore.coerceAtMost(KEYWORD_CAP)

            // 3. ID-number patterns. Full weight only when text evidence agrees;
            //    a bare "A1234567" alone is just a weak hint.
            var idScore = 0f
            for (rx in arch.idPatterns) {
                if (rx.containsMatchIn(combined)) {
                    idScore += if (phraseScore + kwScore > 0f) 5.0f else 1.0f
                    if (reasons.size < 3) reasons.add("id-pattern")
                }
            }

            var score = phraseScore + kwScore + idScore

            // 4. Semantic similarity (only when the ONNX model is really running)
            var sim = 0f
            if (docVec != null && arch.anchorVector.isNotEmpty()) {
                sim = cosineSimilarity(docVec, arch.anchorVector)
                if (sim > 0.25f) score += sim * 1.6f
            }

            if (score > bestScore) {
                bestScore = score
                bestArch = arch
                bestReason = reasons.joinToString(", ").ifEmpty { "semantic:${"%.2f".format(sim)}" }
            }
        }

        val best = bestArch
        if (best == null || bestScore < MIN_CONFIDENCE) {
            return unclassified("low_confidence", bestScore)
        }

        if (best.id in REFINABLE_MARKSHEETS) {
            return ClassificationMatch(
                archetypeId = refineMarksheetLevel(lower, combined, best.id),
                category = best.category,
                isPersonCentric = true,
                score = bestScore,
                matchedEvidence = bestReason
            )
        }

        if (best.id == "Subject_Lecture_Notes") {
            return ClassificationMatch(
                archetypeId = detectSpecificSubject(lower),
                category = "Academic_And_Research",
                isPersonCentric = false,
                score = bestScore,
                matchedEvidence = bestReason
            )
        }

        return ClassificationMatch(
            archetypeId = best.id,
            category = best.category,
            isPersonCentric = best.isPersonCentric,
            score = bestScore,
            matchedEvidence = bestReason
        )
    }

    private fun has(text: String, term: String): Boolean =
        termCache.getOrPut(term) { wordRegex(term) }.containsMatchIn(text)

    private fun hasAny(text: String, vararg terms: String): Boolean = terms.any { has(text, it) }

    private fun refineMarksheetLevel(lower: String, raw: String, initialId: String): String {
        if (hasAny(lower, "b.tech", "btech", "bachelor", "sgpa", "cgpa", "semester", "aktu")) {
            val semMatch = Regex(
                "(?<![A-Za-z])(?:semester|sem)\\s*[:.-]?\\s*([1-8]|viii|vii|vi|v|iv|iii|ii|i)(?![A-Za-z0-9])",
                RegexOption.IGNORE_CASE
            ).find(raw)
            return if (semMatch != null) {
                "Graduation_Sem_${semMatch.groupValues[1].uppercase()}_Marksheet"
            } else {
                "Graduation_Marksheet"
            }
        }
        if (hasAny(lower, "intermediate", "senior school", "senior secondary", "class xii", "12th", "इण्टरमीडिएट") ||
            (has(lower, "physics") && has(lower, "chemistry") && !has(lower, "social science"))
        ) {
            return "12th_Marksheet"
        }
        if (hasAny(lower, "high school", "secondary school", "matriculation", "class x", "10th", "हाई स्कूल", "social science")) {
            return "10th_Marksheet"
        }
        return initialId
    }

    private fun detectSpecificSubject(lower: String): String = when {
        hasAny(lower, "operating system", "deadlock", "cpu scheduling") -> "OS_Lecture_Notes"
        hasAny(lower, "data structure", "algorithm", "binary tree", "dynamic programming") -> "DSA_Algorithms_Notes"
        hasAny(lower, "database", "dbms", "normalization", "sql") -> "DBMS_Notes"
        hasAny(lower, "computer network", "tcp/ip", "osi model") -> "Computer_Networks_Notes"
        hasAny(lower, "compiler", "automata", "turing machine") -> "TOC_Compiler_Notes"
        hasAny(lower, "machine learning", "computer vision", "lane detection", "neural network") -> "AI_ML_Notes"
        hasAny(lower, "discrete", "linear algebra", "probability") -> "Engineering_Maths_Notes"
        else -> "Subject_Study_Notes"
    }

    // Returns null when the ONNX model is unavailable or inference fails.
    private fun embedText(text: String): FloatArray? {
        if (!usingOnnx) return null
        val env = ortEnv ?: return null
        val session = ortSession ?: return null
        val opened = mutableListOf<OnnxTensor>()
        return try {
            val tokens = tokenizeWordPiece(text)
            val n = tokens.size
            val shape = longArrayOf(1, n.toLong())

            val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), shape).also { opened.add(it) }
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape).also { opened.add(it) }
            val typeTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 0L }), shape).also { opened.add(it) }

            val inputs = mapOf(
                "input_ids" to idTensor,
                "attention_mask" to maskTensor,
                "token_type_ids" to typeTensor
            )

            session.run(inputs).use { results ->
                @Suppress("UNCHECKED_CAST")
                val output = results[0].value as Array<Array<FloatArray>>
                val tokEmb = output[0]
                val dim = tokEmb[0].size
                val pooled = FloatArray(dim)
                for (t in tokEmb) {
                    for (d in 0 until dim) pooled[d] += t[d]
                }
                var norm = 0f
                for (d in 0 until dim) {
                    pooled[d] = pooled[d] / tokEmb.size
                    norm += pooled[d] * pooled[d]
                }
                norm = sqrt(norm) + 1e-8f
                for (d in 0 until dim) pooled[d] = pooled[d] / norm
                pooled
            }
        } catch (_: Exception) {
            null
        } finally {
            opened.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }

    // Real greedy longest-match WordPiece (BERT style), not a plain vocab lookup.
    private fun tokenizeWordPiece(text: String): LongArray {
        val cls = vocab["[CLS]"] ?: 101L
        val sep = vocab["[SEP]"] ?: 102L
        val unk = vocab["[UNK]"] ?: 100L

        val ids = mutableListOf(cls)
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        for (word in words) {
            if (ids.size >= MAX_TOKENS - 1) break
            wordPieceInto(word, ids, unk)
        }
        if (ids.size > MAX_TOKENS - 1) ids.subList(MAX_TOKENS - 1, ids.size).clear()
        ids.add(sep)
        return ids.toLongArray()
    }

    private fun wordPieceInto(word: String, out: MutableList<Long>, unk: Long) {
        if (word.length > 40) {
            out.add(unk)
            return
        }
        val pieces = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Long? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found == null) {
                out.add(unk)
                return
            }
            pieces.add(found)
            start = end
        }
        out.addAll(pieces)
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
