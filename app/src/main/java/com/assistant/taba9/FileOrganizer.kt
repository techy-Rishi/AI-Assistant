package com.assistant.taba9

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

data class ExtractedDocContent(
    val fullText: String,
    val spatialLines: List<String>
)

data class SmartFileDecision(
    val targetRelativeDir: String,
    val newBaseFileName: String,
    val debugSummary: String
)

class FileOrganizer(
    private val context: Context,
    private val aiEngine: OfflineAiEngine
) {
    companion object {
        @Volatile
        var isCancelled = false

        private val SUPPORTED_EXTENSIONS = setOf(
            "pdf", "jpg", "jpeg", "png", "webp",
            "docx", "pptx", "xlsx",
            "txt", "md", "csv", "json", "py", "kt", "java", "xml", "html", "c", "cpp"
        )

        private val PROTECTED_DIRS = setOf(
            "android", "dcim", "music", "movies", "ringtones", "alarms", "notifications", "pictures"
        )

        private val NON_NAME_WORDS = setOf(
            "government", "india", "income", "tax", "department", "unique", "identification",
            "authority", "election", "commission", "driving", "licence", "license", "union",
            "republic", "high", "school", "intermediate", "examination", "board", "university",
            "institute", "technology", "prasad", "uttar", "pradesh", "secondary", "education",
            "certificate", "marksheet", "statement", "marks", "grade", "sheet", "provisional",
            "admit", "card", "hall", "ticket", "male", "female", "address", "date", "birth",
            "permanent", "account", "number", "signature", "registrar", "controller", "result",
            "semester", "bachelor", "master", "computer", "science", "engineering", "national",
            "testing", "agency", "ministry", "human", "resource", "development", "roll", "code",
            "aadhaar", "mera", "pehchan", "uidai", "bharat", "sarkar", "father", "mother",
            "name", "candidate", "student", "holder", "applicant", "total", "passed", "division",
            "first", "second", "third", "district", "state", "tehsil", "post", "village", "road",
            "house", "pin", "pincode", "mobile", "phone", "email", "help", "www", "http", "https",
            "download", "issue", "valid", "till", "blood", "group", "organ", "donor", "elector",
            "photo", "identity", "pan", "tan", "gst", "invoice", "receipt", "bank", "branch",
            "ifsc", "account", "saving", "current", "passbook", "syllabus", "lecture", "central",
            "cbse", "icse", "upmsp", "aktu", "kalam", "technical", "lucknow", "jaunpur", "varanasi",
            "allahabad", "prayagraj", "delhi", "session", "regular", "private", "theory", "practical",
            "subject", "wise", "performance", "matriculation", "senior", "plan", "study", "project"
        )
    }

    private val latinOcr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val hindiOcr = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun organizeWholeStorage(onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        isCancelled = false
        val rootDir = Environment.getExternalStorageDirectory()
        if (!rootDir.exists() || !rootDir.canRead()) {
            return@withContext "❌ Cannot access tablet storage. Tap 'Grant All Files Access' first."
        }

        val masterSortedHub = File(rootDir, "AI_Organized_Files")
        if (!masterSortedHub.exists()) masterSortedHub.mkdirs()

        val candidateFiles = mutableListOf<File>()
        collectManageableFiles(rootDir, candidateFiles, maxDepth = 5, currentDepth = 0)

        if (candidateFiles.isEmpty()) {
            return@withContext "No documents, PDFs, Office files, or ID images found."
        }

        onProgress("🔍 Found ${candidateFiles.size} files. Initiating 10-Page Deep-Scan & OCR Analysis (this may take a few minutes)...\n")
        val report = StringBuilder()
        var movedCount = 0

        for ((index, file) in candidateFiles.withIndex()) {
            if (isCancelled) {
                report.append("\n🛑 Stopped by user.")
                break
            }

            // 10-Page Deep Content Extraction
            val extracted = extractDeepContent10Pages(file)
            val cleanOrigName = stripOldAutoTags(file.nameWithoutExtension)
            val decision = buildSmartDecision(cleanOrigName, extracted)

            val targetDir = File(masterSortedHub, decision.targetRelativeDir)
            if (!targetDir.exists()) targetDir.mkdirs()

            val sanitizedBase = decision.newBaseFileName
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { "Document" }

            if (isAlreadyProperlyNamedInDir(file, targetDir, sanitizedBase)) {
                onProgress("[${index + 1}/${candidateFiles.size}] ✓ Already sorted: ${decision.targetRelativeDir}/${file.name}")
                continue
            }

            val destFile = getNextAvailableFile(targetDir, sanitizedBase, file.extension)
            if (file.renameTo(destFile)) {
                movedCount++
                val logLine = "[${index + 1}/${candidateFiles.size}] 📂 ${file.name}\n   ➔ ${decision.targetRelativeDir}/${destFile.name}\n   (${decision.debugSummary})"
                onProgress(logLine)
                report.append(logLine).append("\n")
            }
        }

        deleteEmptyDirectories(masterSortedHub)
        "\n✅ 10-Page Deep Scan Completed! Organized $movedCount of ${candidateFiles.size} files inside /AI_Organized_Files/."
    }

    private fun buildSmartDecision(
        cleanOrigName: String,
        extracted: ExtractedDocContent
    ): SmartFileDecision {
        val match = aiEngine.evaluateDocument(extracted.fullText, cleanOrigName)
        val personName = extractPersonNameUniversal(extracted.spatialLines, extracted.fullText, match.archetypeId)
        val personSlug = personName?.replace(Regex("\\s+"), "_")

        val docSpecificTitle = if (match.isPersonCentric) {
            match.archetypeId
        } else {
            val extractedTopic = extractDocumentHeadingOrTopic(extracted.spatialLines, cleanOrigName)
            if (extractedTopic.isNotEmpty() && !match.archetypeId.contains(extractedTopic, ignoreCase = true)) {
                "${match.archetypeId}_$extractedTopic"
            } else {
                match.archetypeId
            }
        }

        val targetFolder: String
        val finalBaseName: String

        if (personSlug != null && (match.isPersonCentric || match.category in setOf("Personal_IDs", "Marksheets_And_Degrees", "Govt_And_School_Certificates", "Exams_And_Admissions", "Career_And_Employment"))) {
            targetFolder = personSlug
            finalBaseName = "${personSlug}_${match.archetypeId}"
        } else if (match.isPersonCentric) {
            targetFolder = "${match.category}/${match.archetypeId}"
            finalBaseName = match.archetypeId
        } else {
            targetFolder = "${match.category}/${match.archetypeId}"
            finalBaseName = docSpecificTitle
        }

        val snippetPreview = extracted.fullText.replace("\n", " ").take(65)
        val debugInfo = "Type: ${match.archetypeId} | Person: ${personName ?: "None"} | Evidence: ${match.matchedEvidence} | Text: \"$snippetPreview...\""

        return SmartFileDecision(targetFolder, finalBaseName, debugInfo)
    }

    private fun extractPersonNameUniversal(
        spatialLines: List<String>,
        fullText: String,
        archetypeId: String
    ): String? {
        val lines = if (spatialLines.isNotEmpty()) spatialLines else fullText.lines()

        val labelRegexes = listOf(
            Regex("(?<!father'?s?\\s)(?<!mother'?s?\\s)(?<!guardian'?s?\\s)(?:candidate'?s?\\s*name|student'?s?\\s*name|name\\s*of\\s*(?:the\\s*)?(?:student|candidate|applicant|cardholder|holder|patient)|applicant'?s?\\s*name|certify\\s+that|certified\\s+that|awarded\\s+to|presented\\s+to)\\s*[:\\.-]?\\s*(?:mr\\.?|ms\\.?|mrs\\.?|shri|smt|km\\.?|kumari)?\\s*([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})", RegexOption.IGNORE_CASE),
            Regex("(?<!father'?s?\\s)(?<!mother'?s?\\s)(?:^|\\s|:)name\\s*[:\\.-]+\\s*(?:mr\\.?|ms\\.?|mrs\\.?|shri|smt|km\\.?|kumari)?\\s*([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})", RegexOption.IGNORE_CASE)
        )

        for (line in lines) {
            val l = line.lowercase()
            if (l.contains("father") || l.contains("mother") || l.contains("पिता") || l.contains("माता") ||
                l.contains("school") || l.contains("college") || l.contains("institute") || l.contains("bank")
            ) continue

            for (rx in labelRegexes) {
                val m = rx.find(line)
                if (m != null) {
                    val valid = validateHumanName(m.groupValues[1])
                    if (valid != null) return valid
                }
            }
        }

        for (i in 0 until lines.size - 1) {
            val l = lines[i].lowercase().trim()
            if ((l.contains("candidate's name") || l.contains("student's name") || l.contains("name of candidate") ||
                        l.contains("name of student") || l == "name" || l == "name:") &&
                !l.contains("father") && !l.contains("mother")
            ) {
                val nextLineName = validateHumanName(lines[i + 1])
                if (nextLineName != null) return nextLineName
            }
        }

        for (i in lines.indices) {
            val l = lines[i].lowercase()
            val isAnchor = l.contains("dob") || l.contains("date of birth") || l.contains("year of birth") ||
                    l.contains("जन्म तिथि") || l.contains("s/o") || l.contains("d/o") || l.contains("w/o") ||
                    l.contains("father's name") || l.contains("पिता का नाम") || l.contains("पिता :")

            if (isAnchor) {
                val beforeRelation = lineBeforeRelation(lines[i])
                if (beforeRelation != null) return beforeRelation

                for (offset in 1..3) {
                    if (i - offset >= 0) {
                        val prev = lines[i - offset]
                        if (!prev.lowercase().contains("mother") && !prev.lowercase().contains("माता")) {
                            val valid = validateHumanName(prev)
                            if (valid != null) return valid
                        }
                    }
                }
            }
        }

        if (archetypeId in setOf("Resume_CV", "PAN_Card", "Aadhaar_Card")) {
            for (line in lines.take(15)) {
                val l = line.lowercase()
                if (l.contains("father") || l.contains("mother")) continue
                val valid = validateHumanName(line)
                if (valid != null) return valid
            }
        }

        return null
    }

    private fun lineBeforeRelation(line: String): String? {
        val m = Regex("^([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})\\s+(?:S/O|D/O|W/O|C/O)\\b", RegexOption.IGNORE_CASE).find(line.trim())
        return if (m != null) validateHumanName(m.groupValues[1]) else null
    }

    private fun validateHumanName(rawInput: String): String? {
        val cleaned = rawInput
            .replace(Regex("(?i)\\b(s/o|d/o|w/o|c/o|dob|male|female|mr\\.?|ms\\.?|mrs\\.?|shri|smt|kumari|km\\.?|roll|reg|no|name).*"), "")
            .replace(Regex("[^a-zA-Z\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        val words = cleaned.split(" ").filter { it.isNotEmpty() }
        if (words.size !in 2..3) return null

        for (w in words) {
            val lw = w.lowercase()
            if (w.length < 2 || w.length > 14) return null
            if (lw in NON_NAME_WORDS) return null
            if (!lw.any { it in "aeiouy" }) return null
        }

        return words.joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun extractDocumentHeadingOrTopic(spatialLines: List<String>, cleanOrigName: String): String {
        if (!isGenericFilename(cleanOrigName) && cleanOrigName.length in 4..30) {
            return cleanOrigName
        }
        for (line in spatialLines.take(8)) {
            val words = line.split(Regex("\\s+"))
                .map { it.replace(Regex("[^a-zA-Z0-9]"), "") }
                .filter { it.length in 3..14 && it.lowercase() !in NON_NAME_WORDS }
                .take(3)
            if (words.size >= 2) {
                return words.joinToString("_")
            }
        }
        return ""
    }

    // 10-PAGE DEEP CONTENT EXTRACTOR FOR PDFs, IMAGES, AND OFFICE FILES
    private suspend fun extractDeepContent10Pages(file: File): ExtractedDocContent {
        return try {
            when (file.extension.lowercase()) {
                "pdf" -> extractPdf10PagesDeep(file)
                "jpg", "jpeg", "png", "webp" -> extractImageDualOcr(file)
                "docx" -> extractOfficeXmlText(file, "word/document.xml")
                "pptx" -> extractPptxSlidesText(file)
                "xlsx" -> extractOfficeXmlText(file, "xl/sharedStrings.xml")
                "txt", "md", "csv", "json", "py", "kt", "java", "xml", "html", "c", "cpp" -> {
                    val text = file.bufferedReader().use { r ->
                        val buf = CharArray(8000)
                        val n = r.read(buf, 0, 8000)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    ExtractedDocContent(text, text.lines())
                }
                else -> ExtractedDocContent(file.nameWithoutExtension, listOf(file.nameWithoutExtension))
            }
        } catch (_: Exception) {
            ExtractedDocContent(file.nameWithoutExtension, listOf(file.nameWithoutExtension))
        }
    }

    // Scans UP TO 10 PAGES of any PDF (Digital text extraction + Scanned Page OCR fallback)
    private suspend fun extractPdf10PagesDeep(pdfFile: File): ExtractedDocContent {
        var digitalText = ""
        var totalPages = 1
        try {
            PDDocument.load(pdfFile).use { doc ->
                totalPages = doc.numberOfPages
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = totalPages.coerceAtMost(10) // 10 Pages Deep Scan!
                }
                digitalText = stripper.getText(doc).trim()
            }
        } catch (_: Exception) {}

        if (digitalText.length >= 150) {
            val lines = digitalText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return ExtractedDocContent(digitalText.take(10000), lines)
        }

        // Scanned PDF (10 Pages Deep Render & OCR)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val combinedText = StringBuilder(digitalText)
        val combinedSpatialLines = mutableListOf<String>()

        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pagesToScan = renderer.pageCount.coerceAtMost(10) // Up to 10 pages scanned!

            for (pageIndex in 0 until pagesToScan) {
                val page = renderer.openPage(pageIndex)
                val width = (page.width * 2).coerceIn(1200, 2400)
                val height = (page.height * 2).coerceIn(1200, 3000)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageOcr = runDualSpatialOcr(bitmap)
                bitmap.recycle()

                combinedText.append("\n").append(pageOcr.fullText)
                combinedSpatialLines.addAll(pageOcr.spatialLines)
            }
        } catch (_: Exception) {
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }

        return ExtractedDocContent(combinedText.toString().take(10000), combinedSpatialLines)
    }

    private suspend fun extractImageDualOcr(imageFile: File): ExtractedDocContent {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        val sampleSize = if (maxDim > 3000) 2 else 1

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeFile(imageFile.absolutePath, opts)
            ?: return ExtractedDocContent("", emptyList())
        val res = runDualSpatialOcr(bmp)
        bmp.recycle()
        return res
    }

    private suspend fun runDualSpatialOcr(bitmap: Bitmap): ExtractedDocContent {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val latinResult: Text = try { latinOcr.process(inputImage).await() } catch (_: Exception) { return ExtractedDocContent("", emptyList()) }
        val hindiResult: Text? = try { hindiOcr.process(inputImage).await() } catch (_: Exception) { null }

        val allLines = mutableListOf<Text.Line>()
        for (b in latinResult.textBlocks) allLines.addAll(b.lines)

        if (hindiResult != null) {
            for (b in hindiResult.textBlocks) {
                for (line in b.lines) {
                    if (line.text.any { it.code in 0x0900..0x097F }) {
                        allLines.add(line)
                    }
                }
            }
        }

        if (allLines.isEmpty()) return ExtractedDocContent("", emptyList())

        val sortedByY = allLines.sortedBy { it.boundingBox?.centerY() ?: 0 }
        val rows = mutableListOf<MutableList<Text.Line>>()
        val yTolerance = 26

        for (line in sortedByY) {
            val cy = line.boundingBox?.centerY() ?: 0
            val matchRow = rows.lastOrNull { r ->
                abs(cy - (r.first().boundingBox?.centerY() ?: 0)) <= yTolerance
            }
            if (matchRow != null) matchRow.add(line) else rows.add(mutableListOf(line))
        }

        val spatialStrings = rows.map { row ->
            row.sortedBy { it.boundingBox?.left ?: 0 }
                .map { it.text.trim() }
                .distinct()
                .joinToString(" : ")
        }

        return ExtractedDocContent(spatialStrings.joinToString("\n").take(8000), spatialStrings)
    }

    private fun extractOfficeXmlText(file: File, xmlEntryPath: String): ExtractedDocContent {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(xmlEntryPath) ?: return ExtractedDocContent("", emptyList())
                val rawXml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                val text = rawXml
                    .replace(Regex("</w:p>|</si>|</row>"), "\n")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("[ \\t]+"), " ")
                    .trim()
                val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                ExtractedDocContent(text.take(8000), lines)
            }
        } catch (_: Exception) {
            ExtractedDocContent("", emptyList())
        }
    }

    private fun extractPptxSlidesText(file: File): ExtractedDocContent {
        return try {
            ZipFile(file).use { zip ->
                val sb = StringBuilder()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) {
                        val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        val slideText = xml.replace(Regex("</a:p>"), "\n")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("[ \\t]+"), " ")
                        sb.append(slideText).append("\n")
                    }
                }
                val full = sb.toString().trim()
                ExtractedDocContent(full.take(8000), full.lines().map { it.trim() }.filter { it.isNotEmpty() })
            }
        } catch (_: Exception) {
            ExtractedDocContent("", emptyList())
        }
    }

    private fun stripOldAutoTags(name: String): String {
        return name
            .replace(Regex("_\\d+$"), "")
            .replace(Regex("(?i)^(Academic_Marksheet|Marksheet|Certificate|Invoice|Document|Doc|General_Document|Aadhaar_Card|PAN_Card)_*"), "")
    }

    private fun isAlreadyProperlyNamedInDir(file: File, targetDir: File, expectedBase: String): Boolean {
        if (file.parentFile?.canonicalPath != targetDir.canonicalPath) return false
        val currentBase = file.nameWithoutExtension
        if (currentBase.equals(expectedBase, ignoreCase = true)) return true
        val numberedRegex = Regex("^${Regex.escape(expectedBase)}_\\d+$", RegexOption.IGNORE_CASE)
        return numberedRegex.matches(currentBase)
    }

    private fun getNextAvailableFile(targetDir: File, baseName: String, ext: String): File {
        val cleanExt = if (ext.isNotEmpty()) ".$ext" else ""
        var candidate = File(targetDir, "$baseName$cleanExt")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(targetDir, "${baseName}_$counter$cleanExt")
            counter++
        }
        return candidate
    }

    private fun collectManageableFiles(dir: File, outList: MutableList<File>, maxDepth: Int, currentDepth: Int) {
        if (isCancelled || currentDepth > maxDepth || outList.size >= 300) return
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

    private fun deleteEmptyDirectories(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                deleteEmptyDirectories(child)
                if (child.listFiles()?.isEmpty() == true) {
                    child.delete()
                }
            }
        }
    }
}
