package com.assistant.taba9

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
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
    val debugSummary: String,
    val shouldMove: Boolean
)

class FileOrganizer(
    private val context: Context,
    private val aiEngine: OfflineAiEngine
) {
    companion object {
        @Volatile
        var isCancelled = false

        const val HUB_NAME = "AI_Organized_Files"
        private const val LOG_NAME = ".move_log.tsv"

        private const val MAX_DEPTH = 5
        private const val MAX_FILES = 1500
        private const val MAX_PDF_PAGES = 10
        private const val MAX_FILE_BYTES = 40L * 1024 * 1024
        private const val MIN_IMAGE_BYTES = 30_000L
        private const val MIN_AGE_MS = 30_000L

        // Source-code extensions were removed on purpose: moving them breaks projects.
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private val SUPPORTED_EXTENSIONS = setOf(
            "pdf", "jpg", "jpeg", "png", "webp",
            "docx", "pptx", "xlsx",
            "txt", "md", "csv"
        )
        private val TEXT_EXTENSIONS = setOf("txt", "md", "csv")

        private val PROTECTED_DIRS = setOf(
            "android", "dcim", "music", "movies", "ringtones", "alarms", "notifications", "pictures"
        )

        private val SKIPPED_DIR_NAMES = setOf(
            "node_modules", "venv", "__pycache__", "site-packages"
        )

        // A folder containing any of these is a code project: never touched.
        private val PROJECT_MARKERS = setOf(
            ".git", ".idea", "build.gradle", "build.gradle.kts", "settings.gradle",
            "settings.gradle.kts", "package.json", "pom.xml", "requirements.txt",
            "pyproject.toml", "pubspec.yaml", "Cargo.toml", "CMakeLists.txt", "AndroidManifest.xml"
        )

        private val PERSON_CATEGORIES = setOf(
            "Personal_IDs", "Marksheets_And_Degrees", "Govt_And_School_Certificates",
            "Exams_And_Admissions", "Career_And_Employment"
        )

        // "prasad" was removed: it is a common surname (Shiv Prasad) and was blocking real names.
        private val NON_NAME_WORDS = setOf(
            "government", "india", "income", "tax", "department", "unique", "identification",
            "authority", "election", "commission", "driving", "licence", "license", "union",
            "republic", "high", "school", "intermediate", "examination", "board", "university",
            "institute", "technology", "uttar", "pradesh", "secondary", "education",
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
            "ifsc", "saving", "current", "passbook", "syllabus", "lecture", "central",
            "cbse", "icse", "upmsp", "aktu", "kalam", "technical", "lucknow", "jaunpur", "varanasi",
            "allahabad", "prayagraj", "delhi", "session", "regular", "private", "theory", "practical",
            "subject", "wise", "performance", "matriculation", "senior", "plan", "study", "project",
            "curriculum", "vitae", "resume", "dear", "sir", "madam", "profile", "summary", "objective",
            "skills", "experience", "projects", "contact", "declaration", "hobbies", "languages",
            "career", "personal", "details", "information"
        )

        private const val NAME_GROUP = "([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})"
        private const val TITLE_OPT = "(?:(?:mrs|mr|ms|shri|smt|kumari|km)\\.?(?![A-Za-z]))?"

        // Titles need a "not followed by a letter" check, otherwise "Shrivastava" loses "Shri".
        private val LABEL_REGEXES = listOf(
            Regex(
                "(?<!father'?s?\\s)(?<!mother'?s?\\s)(?<!guardian'?s?\\s)" +
                    "(?:candidate'?s?\\s*name|student'?s?\\s*name|" +
                    "name\\s*of\\s*(?:the\\s*)?(?:student|candidate|applicant|cardholder|holder|patient)|" +
                    "applicant'?s?\\s*name|certify\\s+that|certified\\s+that|awarded\\s+to|presented\\s+to)" +
                    "\\s*[:.-]?\\s*$TITLE_OPT\\s*$NAME_GROUP",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                "(?<!father'?s?\\s)(?<!mother'?s?\\s)(?:^|\\s|:)name\\s*[:.-]+\\s*$TITLE_OPT\\s*$NAME_GROUP",
                RegexOption.IGNORE_CASE
            )
        )

        private val LEADING_TITLE = Regex(
            "^(?:mrs|mr|ms|shri|smt|kumari|km)\\.?(?![A-Za-z])\\s*",
            RegexOption.IGNORE_CASE
        )

        private val CUT_AT_NOISE = Regex(
            "(?<![A-Za-z])(?:s/o|d/o|w/o|c/o|dob|male|female|roll|reg|no|name)(?![A-Za-z]).*",
            RegexOption.IGNORE_CASE
        )
    }

    private val latinOcr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val hindiOcr = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun close() {
        try { latinOcr.close() } catch (_: Exception) {}
        try { hindiOcr.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // MAIN ENTRY
    // ------------------------------------------------------------------

    suspend fun organizeWholeStorage(
        dryRun: Boolean = false,
        reprocessSorted: Boolean = false,
        onProgress: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        isCancelled = false

        if (!aiEngine.isReady) {
            return@withContext "Engine is not ready yet: ${aiEngine.initMessage}"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return@withContext "All Files Access is not granted. Tap 'Grant All Files Access' first."
        }

        val rootDir = Environment.getExternalStorageDirectory()
        if (!rootDir.exists() || !rootDir.canRead()) {
            return@withContext "Cannot read storage."
        }

        val hub = File(rootDir, HUB_NAME)
        val candidates = mutableListOf<File>()
        collectManageableFiles(rootDir, hub, reprocessSorted, candidates, 0)

        if (candidates.isEmpty()) {
            return@withContext "No new documents found (already sorted files are skipped)."
        }

        val capNote = if (candidates.size >= MAX_FILES) " (limit $MAX_FILES reached, run again for the rest)" else ""
        onProgress("Found ${candidates.size} files$capNote. Scanning up to $MAX_PDF_PAGES pages each with OCR fallback...")

        var moved = 0
        var alreadySorted = 0
        var leftInPlace = 0
        var failed = 0
        var runStarted = false

        for ((index, file) in candidates.withIndex()) {
            if (isCancelled) {
                onProgress("Stopped by user.")
                break
            }
            if (!file.exists()) continue
            val tag = "[${index + 1}/${candidates.size}]"

            val extracted = extractDeepContent(file)
            val cleanOrigName = stripOldAutoTags(file.nameWithoutExtension)
            val decision = buildSmartDecision(cleanOrigName, extracted)

            if (!decision.shouldMove) {
                leftInPlace++
                onProgress("$tag Left in place (type unclear): ${file.name}")
                continue
            }

            val targetDir = File(hub, decision.targetRelativeDir)
            val sanitizedBase = decision.newBaseFileName
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { "Document" }

            if (isAlreadyProperlyNamedInDir(file, targetDir, sanitizedBase)) {
                alreadySorted++
                continue
            }

            val ext = file.extension
            val destName = if (ext.isNotEmpty()) "$sanitizedBase.$ext" else sanitizedBase

            if (dryRun) {
                moved++
                onProgress("$tag ${file.name}\n   would go to ${decision.targetRelativeDir}/$destName\n   (${decision.debugSummary})")
                continue
            }

            targetDir.mkdirs()
            val destFile = getNextAvailableFile(targetDir, sanitizedBase, ext)

            if (!runStarted) {
                appendMoveLog(hub, "#RUN\t${System.currentTimeMillis()}")
                runStarted = true
            }

            if (safeMove(file, destFile)) {
                appendMoveLog(hub, "${file.absolutePath}\t${destFile.absolutePath}")
                moved++
                onProgress("$tag ${file.name}\n   moved to ${decision.targetRelativeDir}/${destFile.name}\n   (${decision.debugSummary})")
            } else {
                failed++
                onProgress("$tag FAILED to move ${file.name}")
            }
        }

        if (!dryRun && hub.exists()) deleteEmptyDirectories(hub)

        val head = if (dryRun) "Preview finished. Nothing was moved. $moved files would be moved."
        else "Done. Moved $moved files."
        "$head Already sorted: $alreadySorted. Left in place (unclear): $leftInPlace. Failed: $failed. Scanned: ${candidates.size}."
    }

    // ------------------------------------------------------------------
    // UNDO
    // ------------------------------------------------------------------

    suspend fun undoLastRun(): String = withContext(Dispatchers.IO) {
        val hub = File(Environment.getExternalStorageDirectory(), HUB_NAME)
        val logFile = File(hub, LOG_NAME)
        if (!logFile.exists()) return@withContext "Nothing to undo."

        val lines = logFile.readLines()
        val lastRun = lines.indexOfLast { it.startsWith("#RUN") }
        if (lastRun < 0) return@withContext "Nothing to undo."

        val moves = lines.subList(lastRun + 1, lines.size).filter { it.contains('\t') }
        if (moves.isEmpty()) return@withContext "Nothing to undo."

        var restored = 0
        val failedLines = mutableListOf<String>()

        for (line in moves.asReversed()) {
            val parts = line.split('\t')
            if (parts.size != 2) continue
            val original = File(parts[0])
            val current = File(parts[1])

            if (!current.exists()) {
                failedLines.add(line)
                continue
            }
            val parent = original.parentFile
            if (parent == null) {
                failedLines.add(line)
                continue
            }
            parent.mkdirs()
            val target = if (original.exists()) {
                getNextAvailableFile(parent, original.nameWithoutExtension + "_restored", original.extension)
            } else original

            if (safeMove(current, target)) restored++ else failedLines.add(line)
        }

        val kept = lines.subList(0, lastRun).toMutableList()
        if (failedLines.isNotEmpty()) {
            kept.add("#RUN\tretry")
            kept.addAll(failedLines.asReversed())
        }
        logFile.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        deleteEmptyDirectories(hub)

        "Undo finished. Restored $restored files to their original place. Could not restore: ${failedLines.size}."
    }

    // ------------------------------------------------------------------
    // DECISION LOGIC
    // ------------------------------------------------------------------

    private fun buildSmartDecision(
        cleanOrigName: String,
        extracted: ExtractedDocContent
    ): SmartFileDecision {
        val match = aiEngine.evaluateDocument(extracted.fullText, cleanOrigName)

        if (match.archetypeId == "General_Document") {
            return SmartFileDecision("", "", "unclear: ${match.matchedEvidence}", false)
        }

        val personEligible = match.isPersonCentric || match.category in PERSON_CATEGORIES
        val personName = if (personEligible) {
            extractPersonNameUniversal(extracted.spatialLines, extracted.fullText, match.archetypeId)
        } else null
        val personSlug = personName?.replace(Regex("\\s+"), "_")

        val targetFolder: String
        val finalBaseName: String

        if (personSlug != null) {
            targetFolder = personSlug
            finalBaseName = "${personSlug}_${match.archetypeId}"
        } else if (match.isPersonCentric) {
            targetFolder = "${match.category}/${match.archetypeId}"
            finalBaseName = match.archetypeId
        } else {
            targetFolder = "${match.category}/${match.archetypeId}"
            val topic = extractDocumentHeadingOrTopic(extracted.spatialLines, cleanOrigName)
            finalBaseName = if (topic.isNotEmpty() && !match.archetypeId.contains(topic, ignoreCase = true)) {
                "${match.archetypeId}_$topic"
            } else match.archetypeId
        }

        // No text snippet and no ID numbers in the log on purpose (Aadhaar etc. are private).
        val debugInfo = "Type: ${match.archetypeId} | Person: ${personName ?: "None"} | Evidence: ${match.matchedEvidence}"
        return SmartFileDecision(targetFolder, finalBaseName, debugInfo, true)
    }

    private fun extractPersonNameUniversal(
        spatialLines: List<String>,
        fullText: String,
        archetypeId: String
    ): String? {
        val lines = if (spatialLines.isNotEmpty()) spatialLines else fullText.lines()

        // 1. Explicit "Name:" style labels
        for (line in lines) {
            val l = line.lowercase()
            if (l.contains("father") || l.contains("mother") || l.contains("पिता") || l.contains("माता") ||
                l.contains("school") || l.contains("college") || l.contains("institute") || l.contains("bank")
            ) continue

            for (rx in LABEL_REGEXES) {
                val m = rx.find(line)
                if (m != null) {
                    val valid = validateHumanName(m.groupValues[1])
                    if (valid != null) return valid
                }
            }
        }

        // 2. Label on one line, value on the next
        for (i in 0 until lines.size - 1) {
            val l = lines[i].lowercase().trim()
            if ((l.contains("candidate's name") || l.contains("student's name") || l.contains("name of candidate") ||
                        l.contains("name of student") || l == "name" || l == "name:") &&
                !l.contains("father") && !l.contains("mother")
            ) {
                val next = validateHumanName(lines[i + 1])
                if (next != null) return next
            }
        }

        // 3. Name near DOB / S/O / D/O anchors (ID cards)
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
                        val pl = prev.lowercase()
                        if (!pl.contains("mother") && !pl.contains("माता")) {
                            val valid = validateHumanName(prev)
                            if (valid != null) return valid
                        }
                    }
                }
            }
        }

        // 4. Top-of-page name for resumes / PAN / Aadhaar
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
        val m = Regex(
            "^([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})\\s+(?:S/O|D/O|W/O|C/O)(?![A-Za-z])",
            RegexOption.IGNORE_CASE
        ).find(line.trim())
        return if (m != null) validateHumanName(m.groupValues[1]) else null
    }

    private fun validateHumanName(rawInput: String): String? {
        var s = rawInput.trim().replace(LEADING_TITLE, "")
        s = s.replace(CUT_AT_NOISE, "")
        s = s.replace(Regex("[^a-zA-Z\\s]"), " ").trim().replace(Regex("\\s+"), " ")

        val words = mutableListOf<String>()
        for (w in s.split(" ").filter { it.isNotEmpty() }) {
            if (w.lowercase() in NON_NAME_WORDS) break
            words.add(w)
        }
        if (words.size !in 2..3) return null

        for (w in words) {
            val lw = w.lowercase()
            if (w.length < 2 || w.length > 14) return null
            if (!lw.any { it in "aeiouy" }) return null
        }

        return words.joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    // This function was missing in the original code, which broke compilation.
    private fun isGenericFilename(name: String): Boolean {
        val n = name.lowercase().trim()
        if (n.length < 4) return true
        if (Regex("^[0-9a-f\\-_ ()]+$").matches(n)) return true
        return Regex(
            "^(img|image|scan|scanned|doc|document|file|pdf|photo|screenshot|whatsapp|camscanner|download|untitled|new|copy)[-_ ]*[0-9a-f\\-_ ()]*$"
        ).matches(n)
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

    // ------------------------------------------------------------------
    // CONTENT EXTRACTION
    // ------------------------------------------------------------------

    private suspend fun extractDeepContent(file: File): ExtractedDocContent {
        val fallback = ExtractedDocContent(file.nameWithoutExtension, listOf(file.nameWithoutExtension))
        return try {
            when (file.extension.lowercase()) {
                "pdf" -> extractPdf(file)
                in IMAGE_EXTENSIONS -> extractImageDualOcr(file)
                "docx" -> extractOfficeXmlText(file, "word/document.xml")
                "pptx" -> extractPptxSlidesText(file)
                "xlsx" -> extractOfficeXmlText(file, "xl/sharedStrings.xml")
                in TEXT_EXTENSIONS -> {
                    val text = file.bufferedReader().use { r ->
                        val buf = CharArray(8000)
                        val n = r.read(buf, 0, 8000)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    ExtractedDocContent(text, text.lines())
                }
                else -> fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private suspend fun extractPdf(pdfFile: File): ExtractedDocContent {
        var digitalText = ""
        try {
            PDDocument.load(pdfFile).use { doc ->
                val stripper = PDFTextStripper().apply {
                    startPage = 1
                    endPage = doc.numberOfPages.coerceAtMost(MAX_PDF_PAGES)
                }
                digitalText = stripper.getText(doc).trim()
            }
        } catch (_: Exception) {
        }

        if (digitalText.length >= 150) {
            val lines = digitalText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return ExtractedDocContent(digitalText.take(10000), lines)
        }

        // Scanned PDF: render pages and OCR, stop early once there is enough text.
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val combinedText = StringBuilder(digitalText)
        val combinedLines = mutableListOf<String>()

        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pagesToScan = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)

            for (pageIndex in 0 until pagesToScan) {
                if (isCancelled) break
                val page = renderer.openPage(pageIndex)
                val bitmap = try {
                    // Keep the page aspect ratio, otherwise OCR text gets stretched.
                    val scale = 2000f / maxOf(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }

                val pageOcr = runDualSpatialOcr(bitmap)
                bitmap.recycle()

                combinedText.append("\n").append(pageOcr.fullText)
                combinedLines.addAll(pageOcr.spatialLines)

                if (combinedText.length >= 1500) break
            }
        } catch (_: Exception) {
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }

        return ExtractedDocContent(combinedText.toString().take(10000), combinedLines)
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
        val latinResult: Text = try {
            latinOcr.process(inputImage).await()
        } catch (_: Exception) {
            return ExtractedDocContent("", emptyList())
        }
        val hindiResult: Text? = try { hindiOcr.process(inputImage).await() } catch (_: Exception) { null }

        val allLines = mutableListOf<Text.Line>()
        for (b in latinResult.textBlocks) allLines.addAll(b.lines)

        if (hindiResult != null) {
            for (b in hindiResult.textBlocks) {
                for (line in b.lines) {
                    if (line.text.any { it.code in 0x0900..0x097F }) allLines.add(line)
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
                    .replace("&amp;", "&")
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
                            .replace("&amp;", "&")
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

    // ------------------------------------------------------------------
    // FILE HELPERS
    // ------------------------------------------------------------------

    // Only strips our own old tags and "_1" style counters, not years like "_2024".
    private fun stripOldAutoTags(name: String): String {
        return name
            .replace(Regex("_\\d{1,2}$"), "")
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

    // renameTo silently overwrites on some systems and fails across volumes.
    // This refuses to overwrite and falls back to copy + verify + delete.
    private fun safeMove(src: File, dest: File): Boolean {
        if (dest.exists()) return false
        if (src.renameTo(dest)) return true
        return try {
            src.copyTo(dest, overwrite = false)
            if (dest.length() == src.length()) {
                if (src.delete()) {
                    true
                } else {
                    dest.delete()
                    false
                }
            } else {
                dest.delete()
                false
            }
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    private fun appendMoveLog(hub: File, line: String) {
        try {
            hub.mkdirs()
            File(hub, LOG_NAME).appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    private fun looksLikeProject(children: Array<File>): Boolean =
        children.any { it.name in PROJECT_MARKERS }

    private fun collectManageableFiles(
        dir: File,
        hub: File,
        includeHub: Boolean,
        outList: MutableList<File>,
        depth: Int
    ) {
        if (isCancelled || depth > MAX_DEPTH || outList.size >= MAX_FILES) return
        val children = dir.listFiles() ?: return
        if (depth > 0 && looksLikeProject(children)) return

        val now = System.currentTimeMillis()
        val hubPath = try { hub.canonicalPath } catch (_: Exception) { hub.absolutePath }

        for (child in children) {
            if (isCancelled || outList.size >= MAX_FILES) return
            if (child.name.startsWith(".")) continue

            if (child.isDirectory) {
                if (!includeHub) {
                    val p = try { child.canonicalPath } catch (_: Exception) { child.absolutePath }
                    if (p == hubPath) continue
                }
                if (depth == 0 && child.name.lowercase() in PROTECTED_DIRS) continue
                if (child.name.lowercase() in SKIPPED_DIR_NAMES) continue
                collectManageableFiles(child, hub, includeHub, outList, depth + 1)
            } else if (child.isFile) {
                val ext = child.extension.lowercase()
                if (ext !in SUPPORTED_EXTENSIONS) continue
                val len = child.length()
                if (len == 0L || len > MAX_FILE_BYTES) continue
                if (ext in IMAGE_EXTENSIONS && len < MIN_IMAGE_BYTES) continue
                if (now - child.lastModified() < MIN_AGE_MS) continue // probably still downloading
                outList.add(child)
            }
        }
    }

    private fun deleteEmptyDirectories(dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                deleteEmptyDirectories(child)
                if (child.listFiles()?.isEmpty() == true) child.delete()
            }
        }
    }
}
