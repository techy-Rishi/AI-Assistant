package com.assistant.taba9

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

data class SmartFileDecision(
    val mainCategory: String,
    val subFolder: String,
    val suggestedBaseName: String?
)

class FileOrganizer(
    private val context: Context,
    private val aiEngine: OfflineAiEngine
) {
    companion object {
        @Volatile
        var isCancelled = false

        private val SUPPORTED_EXTENSIONS = setOf(
            "pdf", "txt", "md", "csv", "json", "py", "kt", "java", "xml", "html",
            "jpg", "jpeg", "png", "webp", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
        )

        private val PROTECTED_DIRS = setOf(
            "android", "ai_organized_files", "dcim", "music", "movies", "ringtones", "alarms", "notifications"
        )
    }

    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun organizeWholeStorage(): String = withContext(Dispatchers.IO) {
        isCancelled = false
        val rootDir = Environment.getExternalStorageDirectory()
        if (!rootDir.exists() || !rootDir.canRead()) {
            return@withContext "Cannot access tablet storage. Grant All Files Access."
        }

        val masterSortedHub = File(rootDir, "AI_Organized_Files")
        if (!masterSortedHub.exists()) masterSortedHub.mkdirs()

        val candidateFiles = mutableListOf<File>()
        collectManageableFiles(rootDir, candidateFiles, maxDepth = 4, currentDepth = 0)

        if (candidateFiles.isEmpty()) {
            return@withContext "No loose documents, IDs, or PDFs found to sort."
        }

        val summary = StringBuilder("Smart-Sorted & Renamed ${candidateFiles.size} files:\n")
        var movedCount = 0

        for (file in candidateFiles) {
            if (isCancelled) {
                summary.append("\n🛑 Stopped by Master OFF.")
                break
            }
            val snippet = extractSnippetSafely(file)
            val decision = analyzeAndNameDocument(file, snippet)

            val targetDir = if (decision.subFolder.isNotEmpty()) {
                File(File(masterSortedHub, decision.mainCategory), decision.subFolder)
            } else {
                File(masterSortedHub, decision.mainCategory)
            }
            if (!targetDir.exists()) targetDir.mkdirs()

            // Rename meaningless filenames (like IMG_2026..., WA001..., scan...) using extracted content!
            val finalBaseName = decision.suggestedBaseName ?: file.nameWithoutExtension
            var destFile = File(targetDir, "$finalBaseName.${file.extension}")
            if (destFile.exists()) {
                destFile = File(targetDir, "${finalBaseName}_${System.currentTimeMillis() % 1000}.${file.extension}")
            }

            if (file.renameTo(destFile)) {
                movedCount++
                val relPath = "${decision.mainCategory}/${decision.subFolder}".trimEnd('/')
                summary.append("• ${file.name} ➔ $relPath/${destFile.name}\n")
            }
        }
        summary.append("Total organized: $movedCount files.")
        summary.toString()
    }

    // Detects exact ID types (Aadhaar, PAN, Marksheet), Invoice numbers, or Subject names and auto-renames ugly filenames!
    private fun analyzeAndNameDocument(file: File, snippet: String): SmartFileDecision {
        val combined = "${file.nameWithoutExtension} $snippet"
        val lower = combined.lowercase()

        // 1. Check Personal IDs & Official Certificates
        val panMatch = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b").find(combined.uppercase())
        if (panMatch != null || lower.contains("income tax department") || lower.contains("permanent account number")) {
            val panTail = panMatch?.value?.takeLast(4) ?: "Doc"
            return SmartFileDecision("Personal_IDs", "PAN_Card", "PAN_Card_$panTail")
        }

        val aadhaarMatch = Regex("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b").find(combined)
        if (aadhaarMatch != null || lower.contains("unique identification") || lower.contains("aadhaar") || lower.contains("mera aadhaar")) {
            val last4 = aadhaarMatch?.value?.takeLast(4) ?: "UID"
            return SmartFileDecision("Personal_IDs", "Aadhaar_Card", "Aadhaar_$last4")
        }

        if (lower.contains("passport") || lower.contains("republic of india")) {
            return SmartFileDecision("Personal_IDs", "Passport", "Indian_Passport")
        }

        if (lower.contains("driving licence") || lower.contains("transport department")) {
            return SmartFileDecision("Personal_IDs", "Driving_License", "Driving_License")
        }

        if (lower.contains("marksheet") || lower.contains("grade sheet") || lower.contains("semester") || lower.contains("certificate")) {
            val cleanTitle = extractCleanTitle(snippet, file.nameWithoutExtension, "Certificate")
            return SmartFileDecision("Personal_IDs", "Certificates_Marksheets", cleanTitle)
        }

        // 2. Use ONNX Semantic AI Engine for general category
        val aiCategory = aiEngine.classifyFileContent(combined)

        // 3. Create dynamic sub-folders & smart names inside Academic_Notes & Finance_Bills
        if (aiCategory == "Academic_Notes") {
            val subjectSubFolder = when {
                lower.contains("operating system") || lower.contains("deadlock") || lower.contains("process") -> "Operating_Systems"
                lower.contains("algorithm") || lower.contains("data structure") || lower.contains("tree") || lower.contains("graph") -> "DSA_Algorithms"
                lower.contains("network") || lower.contains("tcp") || lower.contains("routing") -> "Computer_Networks"
                lower.contains("database") || lower.contains("sql") || lower.contains("normalization") -> "DBMS"
                lower.contains("gate") || lower.contains("previous year") -> "GATE_Prep"
                lower.contains("machine learning") || lower.contains("neural") || lower.contains("computer vision") -> "AI_ML"
                else -> "General_Notes"
            }
            val smartTitle = if (isGenericFilename(file.nameWithoutExtension)) {
                extractCleanTitle(snippet, file.nameWithoutExtension, subjectSubFolder)
            } else null
            return SmartFileDecision("Academic_Notes", subjectSubFolder, smartTitle)
        }

        if (aiCategory == "Finance_Bills") {
            val sub = if (lower.contains("bank") || lower.contains("statement")) "Bank_Statements" else "Invoices_Receipts"
            val smartTitle = if (isGenericFilename(file.nameWithoutExtension)) {
                extractCleanTitle(snippet, file.nameWithoutExtension, "Invoice")
            } else null
            return SmartFileDecision("Finance_Bills", sub, smartTitle)
        }

        return SmartFileDecision(aiCategory, "", null)
    }

    private fun isGenericFilename(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("img") || lower.startsWith("wa0") || lower.startsWith("scan") ||
                lower.startsWith("screenshot") || lower.startsWith("doc") || lower.matches(Regex("^[0-9_-]+$"))
    }

    private fun extractCleanTitle(snippet: String, fallback: String, prefix: String): String {
        val words = snippet.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9]"), "") }
            .filter { it.length in 3..15 }
            .take(4)
        return if (words.size >= 2) {
            "${prefix}_${words.joinToString("_")}"
        } else fallback
    }

    private fun collectManageableFiles(dir: File, outList: MutableList<File>, maxDepth: Int, currentDepth: Int) {
        if (isCancelled || currentDepth > maxDepth || outList.size >= 80) return
        val children = dir.listFiles() ?: return

        for (child in children) {
            if (isCancelled) return
            if (child.name.startsWith(".")) continue

            if (child.isDirectory) {
                if (currentDepth == 0 && PROTECTED_DIRS.contains(child.name.lowercase())) continue
                collectManageableFiles(child, outList, maxDepth, currentDepth + 1)
            } else if (child.isFile && SUPPORTED_EXTENSIONS.contains(child.extension.lowercase())) {
                outList.add(child)
            }
        }
    }

    private suspend fun extractSnippetSafely(file: File): String {
        return try {
            when (file.extension.lowercase()) {
                "txt", "md", "csv", "json", "py", "kt", "java", "xml", "html" -> {
                    file.bufferedReader().use { reader ->
                        val buf = CharArray(1200)
                        val read = reader.read(buf, 0, 1200)
                        if (read > 0) String(buf, 0, read) else ""
                    }
                }
                "pdf" -> {
                    PDDocument.load(file).use { doc ->
                        val stripper = PDFTextStripper().apply {
                            startPage = 1
                            endPage = 1
                        }
                        stripper.getText(doc).take(1200)
                    }
                }
                "jpg", "jpeg", "png", "webp" -> {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return ""
                    val image = InputImage.fromBitmap(bmp, 0)
                    val result = ocrRecognizer.process(image).await()
                    bmp.recycle()
                    result.text.take(1200)
                }
                else -> file.nameWithoutExtension
            }
        } catch (_: Exception) {
            file.nameWithoutExtension
        }
    }
}
