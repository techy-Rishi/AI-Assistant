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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class ExtractedDocContent(
    val fullText: String,
    val spatialLines: List<String>
)

data class SmartFileDecision(
    val targetRelativeDir: String,
    val newBaseFileName: String
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
            "android", "dcim", "music", "movies", "ringtones", "alarms", "notifications"
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
            "cbse", "icse", "upmsp", "aktu", "dr", "apj", "abdul", "kalam", "technical", "lucknow",
            "allahabad", "prayagraj", "delhi", "mumbai", "session", "regular", "private", "theory",
            "practical", "positional", "subject", "wise", "performance", "cum", "matriculation"
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
        collectManageableFiles(rootDir, candidateFiles, maxDepth = 5, currentDepth = 0)

        if (candidateFiles.isEmpty()) {
            return@withContext "No documents, IDs, or images found across tablet storage."
        }

        val summary = StringBuilder("Deep-Analyzed ${candidateFiles.size} files (including Scanned PDFs):\n")
        var movedOrUpdatedCount = 0

        for (file in candidateFiles) {
            if (isCancelled) {
                summary.append("\n🛑 Stopped by Master OFF.")
                break
            }

            // Deep Extraction: Reads text AND runs High-Res OCR on both Images and Scanned PDFs!
            val extracted = extractDeepContent(file)
            val cleanOrigName = stripOldGenericNames(file.nameWithoutExtension)
            val decision = analyzeAndBuildPersonFolder(cleanOrigName, extracted)

            val targetDir = File(masterSortedHub, decision.targetRelativeDir)
            if (!targetDir.exists()) targetDir.mkdirs()

            val sanitizedBase = decision.newBaseFileName
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { "Document" }

            // Skip if file is already in the exact right folder with the exact right base name
            if (isAlreadyProperlyNamedInDir(file, targetDir, sanitizedBase)) {
                continue
            }

            val destFile = getNextAvailableFile(targetDir, sanitizedBase, file.extension)

            if (file.renameTo(destFile)) {
                movedOrUpdatedCount++
                summary.append("• ${file.name} ➔ ${decision.targetRelativeDir}/${destFile.name}\n")
            }
        }

        deleteEmptyDirectories(masterSortedHub)
        summary.append("Total organized/renamed: $movedOrUpdatedCount files.")
        summary.toString()
    }

    private fun analyzeAndBuildPersonFolder(
        cleanOrigName: String,
        extracted: ExtractedDocContent
    ): SmartFileDecision {
        val fullText = "${extracted.fullText}\n$cleanOrigName"
        val lower = fullText.lowercase()

        // 1. Detect EXACT Document Type (10th_Marksheet, 12th_Marksheet, Graduation_Sem_3_Marksheet, Aadhaar_Card, etc.)
        val docInfo = detectSpecificDocumentType(lower, fullText, cleanOrigName)
        val mainCategory = docInfo.first
        val specificDocTag = docInfo.second

        // 2. Extract Person Name using Spatial Row-Aligned Lines + Structural Heuristics
        val personName = extractPersonNameFromSpatialLines(extracted.spatialLines, fullText, specificDocTag)
        val personSlug = personName?.replace(Regex("\\s+"), "_")

        val isPersonDoc = mainCategory in setOf(
            "Personal_IDs", "Marksheets_Certificates", "Exams_Admissions", "Career_Resumes"
        )

        // 3. Build Person Folder & Exact File Name:
        // If person name is found -> Folder: AI_Organized_Files/<Person_Name>/
        // File inside it -> <Person_Name>_<Specific_Doc_Tag>.ext (e.g., Rohit_Kumar_10th_Marksheet.pdf, Rohit_Kumar_Aadhaar_Card.jpg)
        return if (personSlug != null) {
            SmartFileDecision(
                targetRelativeDir = personSlug,
                newBaseFileName = "${personSlug}_$specificDocTag"
            )
        } else if (isPersonDoc) {
            SmartFileDecision(
                targetRelativeDir = "Unidentified_Person_Docs/$mainCategory",
                newBaseFileName = specificDocTag
            )
        } else {
            SmartFileDecision(
                targetRelativeDir = mainCategory,
                newBaseFileName = specificDocTag
            )
        }
    }

    // Distinguishes 10th, 12th, Graduation (B.Tech/B.Sc/BA + Semester), Diploma, Aadhaar, PAN, Certificates, etc.
    private fun detectSpecificDocumentType(
        lower: String,
        rawText: String,
        cleanOrigName: String
    ): Pair<String, String> {

        // --- A. PERSONAL IDs ---
        val aadhaarMatch = Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b").find(rawText)
        if (aadhaarMatch != null || lower.contains("unique identification") || lower.contains("uidai") ||
            lower.contains("mera aadhaar") || lower.contains("aadhaar") || lower.contains("आधार")
        ) {
            return Pair("Personal_IDs", "Aadhaar_Card")
        }

        val panMatch = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b").find(rawText.uppercase())
        if (panMatch != null || lower.contains("income tax department") || lower.contains("permanent account number") || lower.contains("pan card")) {
            return Pair("Personal_IDs", "PAN_Card")
        }

        val voterMatch = Regex("\\b[A-Z]{3}[0-9]{7}\\b").find(rawText.uppercase())
        if (voterMatch != null || lower.contains("election commission") || lower.contains("elector photo") || lower.contains("voter")) {
            return Pair("Personal_IDs", "Voter_ID_Card")
        }

        if (lower.contains("driving licence") || lower.contains("driving license") || lower.contains("transport department")) {
            return Pair("Personal_IDs", "Driving_License")
        }

        if (lower.contains("passport") || lower.contains("republic of india")) {
            return Pair("Personal_IDs", "Passport")
        }

        // --- B. 10th / 12th / GRADUATION MARKSHEETS (DEEP ANALYSIS) ---
        val isMarksheetOrResult = lower.contains("marksheet") || lower.contains("marks statement") ||
                lower.contains("statement of marks") || lower.contains("grade sheet") ||
                lower.contains("high school") || lower.contains("intermediate") ||
                lower.contains("secondary school") || lower.contains("senior school") ||
                lower.contains("matriculation") || lower.contains("roll no") ||
                lower.contains("roll number") || lower.contains("maximum marks") ||
                lower.contains("marks obtained") || lower.contains("theory") ||
                lower.contains("sgpa") || lower.contains("cgpa") || lower.contains("semester")

        if (isMarksheetOrResult && !lower.contains("admit card") && !lower.contains("hall ticket")) {
            // 1. Check GRADUATION / UNIVERSITY (B.Tech, B.E, B.Sc, B.A, B.Com, M.Tech, MCA, MBA, Semester, SGPA, CGPA)
            val isGraduation = lower.contains("bachelor") || lower.contains("b.tech") || lower.contains("btech") ||
                    lower.contains("b.sc") || lower.contains("b.a.") || lower.contains("b.com") ||
                    lower.contains("university") || lower.contains("institute of technology") ||
                    lower.contains("aktu") || lower.contains("sgpa") || lower.contains("cgpa") ||
                    lower.contains("semester") || lower.contains("credit")

            if (isGraduation) {
                val semMatch = Regex("(?:semester|sem)\\s*[:\\.-]?\\s*([1-8]|viii|vii|vi|v|iv|iii|ii|i)\\b", RegexOption.IGNORE_CASE).find(rawText)
                val yearMatch = Regex("(?:year|yr)\\s*[:\\.-]?\\s*([1-4]|iv|iii|ii|i)\\b", RegexOption.IGNORE_CASE).find(rawText)
                val suffix = when {
                    semMatch != null -> "Graduation_Sem_${semMatch.groupValues[1].uppercase()}_Marksheet"
                    yearMatch != null -> "Graduation_Year_${yearMatch.groupValues[1].uppercase()}_Marksheet"
                    lower.contains("final") || lower.contains("degree") || lower.contains("provisional") -> "Graduation_Degree_Marksheet"
                    else -> "Graduation_Marksheet"
                }
                return Pair("Marksheets_Certificates", suffix)
            }

            // 2. Check 12th (INTERMEDIATE / SENIOR SECONDARY / CLASS XII)
            // Also checks subject combinations: Physics + Chemistry + (Mathematics/Biology) without Social Science = 12th!
            val has12thKeywords = lower.contains("intermediate") || lower.contains("senior school") ||
                    lower.contains("senior secondary") || lower.contains("higher secondary") ||
                    lower.contains("class xii") || lower.contains("class 12") || lower.contains("12th") ||
                    lower.contains("10+2") || lower.contains("inter examination")
            val has12thSubjects = (lower.contains("physics") && lower.contains("chemistry") && !lower.contains("social science")) ||
                    (lower.contains("accountancy") && lower.contains("business studies"))

            if (has12thKeywords || has12thSubjects) {
                return Pair("Marksheets_Certificates", "12th_Marksheet")
            }

            // 3. Check 10th (HIGH SCHOOL / SECONDARY SCHOOL / MATRICULATION / CLASS X)
            // Subject check: Social Science / Science + Mathematics + Hindi/English = 10th!
            val has10thKeywords = lower.contains("high school") || lower.contains("secondary school") ||
                    lower.contains("matriculation") || lower.contains("class x") ||
                    lower.contains("class 10") || lower.contains("10th") || lower.contains("highschool")
            val has10thSubjects = lower.contains("social science") || (lower.contains("science") && !lower.contains("computer science") && !lower.contains("physics"))

            if (has10thKeywords || has10thSubjects) {
                return Pair("Marksheets_Certificates", "10th_Marksheet")
            }

            // 4. Check Diploma / Polytechnic
            if (lower.contains("diploma") || lower.contains("polytechnic") || lower.contains("bteup")) {
                return Pair("Marksheets_Certificates", "Diploma_Marksheet")
            }

            return Pair("Marksheets_Certificates", "Academic_Marksheet")
        }

        // --- C. GOVT & COLLEGE CERTIFICATES ---
        if (lower.contains("caste certificate") || lower.contains("jati praman") || lower.contains("backward class") || lower.contains("scheduled caste") || lower.contains("जाति प्रमाण")) {
            return Pair("Marksheets_Certificates", "Caste_Certificate")
        }
        if (lower.contains("income certificate") || lower.contains("aay praman") || lower.contains("annual income") || lower.contains("आय प्रमाण")) {
            return Pair("Marksheets_Certificates", "Income_Certificate")
        }
        if (lower.contains("domicile") || lower.contains("residence certificate") || lower.contains("niwas praman") || lower.contains("निवास प्रमाण")) {
            return Pair("Marksheets_Certificates", "Domicile_Certificate")
        }
        if (lower.contains("transfer certificate") || lower.contains("migration") || lower.contains("character certificate")) {
            return Pair("Marksheets_Certificates", "Transfer_Migration_Certificate")
        }
        if (lower.contains("certificate") || lower.contains("nptel") || lower.contains("swayam") || lower.contains("workshop")) {
            return Pair("Marksheets_Certificates", extractTopicTag(rawText, cleanOrigName, "Certificate"))
        }

        // --- D. EXAMS, ADMIT CARDS & SCORECARDS ---
        if (lower.contains("admit card") || lower.contains("hall ticket") || lower.contains("e-admit")) {
            val exam = when {
                lower.contains("gate") -> "GATE_Admit_Card"
                lower.contains("ugc") || lower.contains("net") -> "UGC_NET_Admit_Card"
                lower.contains("jee") -> "JEE_Admit_Card"
                else -> "Admit_Card"
            }
            return Pair("Exams_Admissions", exam)
        }
        if (lower.contains("scorecard") || lower.contains("score card") || lower.contains("rank card") || lower.contains("jrf")) {
            val exam = when {
                lower.contains("gate") -> "GATE_Scorecard"
                lower.contains("ugc") || lower.contains("net") -> "UGC_NET_Scorecard"
                else -> "Exam_Scorecard"
            }
            return Pair("Exams_Admissions", exam)
        }

        // --- E. RESUME & CAREER ---
        if (lower.contains("curriculum vitae") || lower.contains("resume") || (lower.contains("skills") && lower.contains("education") && lower.contains("projects"))) {
            return Pair("Career_Resumes", "Resume_CV")
        }

        // --- F. FINANCE & BANKING ---
        if (lower.contains("passbook") || lower.contains("bank statement") || lower.contains("ifsc")) {
            return Pair("Finance_Bills_Receipts", "Bank_Passbook_Statement")
        }
        if (lower.contains("invoice") || lower.contains("gstin") || lower.contains("receipt") || lower.contains("bill")) {
            return Pair("Finance_Bills_Receipts", extractTopicTag(rawText, cleanOrigName, "Invoice_Receipt"))
        }

        // --- G. STUDY NOTES & RESEARCH PAPERS ---
        if (lower.contains("abstract") && lower.contains("introduction") && (lower.contains("references") || lower.contains("ieee"))) {
            return Pair("Academic_Study_Material", extractTopicTag(rawText, cleanOrigName, "Research_Paper"))
        }
        val aiCat = aiEngine.classifyFileContent(rawText)
        return Pair(aiCat, extractTopicTag(rawText, cleanOrigName, "Document"))
    }

    // Uses spatially reconstructed rows (where left-column label and right-column value are joined on the same line!)
    private fun extractPersonNameFromSpatialLines(
        spatialLines: List<String>,
        fullText: String,
        docTag: String
    ): String? {
        val allLines = if (spatialLines.isNotEmpty()) spatialLines else fullText.lines()

        // 1. Check row-aligned lines for Student / Candidate / Cardholder Name
        // Note: We EXCLUDE "Father's Name" and "Mother's Name" so the student's own name is picked first!
        val primaryNameRegexes = listOf(
            Regex("(?<!father'?s?\\s)(?<!mother'?s?\\s)(?<!guardian'?s?\\s)(?:candidate'?s?\\s*name|student'?s?\\s*name|name\\s*of\\s*(?:the\\s*)?(?:student|candidate|applicant|cardholder|holder)|applicant'?s?\\s*name|प्रमाणित किया जाता है कि|certify\\s+that|awarded\\s+to)\\s*[:\\.-]?\\s*(?:mr\\.?|ms\\.?|mrs\\.?|shri|smt|km\\.?|kumari)?\\s*([A-Za-z]+(?:\\s+[A-Za-z]+){1,3})", RegexOption.IGNORE_CASE),
            Regex("(?<!father'?s?\\s)(?<!mother'?s?\\s)(?:^|\\s)name\\s*[:\\.-]?\\s*(?:mr\\.?|ms\\.?|mrs\\.?|shri|smt|km\\.?|kumari)?\\s*([A-Za-z]+(?:\\s+[A-Za-z]+){1,3})", RegexOption.IGNORE_CASE)
        )

        for (line in allLines) {
            val lowerLine = line.lowercase()
            if (lowerLine.contains("father") || lowerLine.contains("mother") || lowerLine.contains("school name") || lowerLine.contains("college name") || lowerLine.contains("bank name")) {
                continue
            }
            for (regex in primaryNameRegexes) {
                val match = regex.find(line)
                if (match != null) {
                    val valid = validateAndFormatPersonName(match.groupValues[1])
                    if (valid != null) return valid
                }
            }
        }

        // 2. Check if Label is on Line i and Student Name is on Line i+1
        for (i in 0 until allLines.size - 1) {
            val l = allLines[i].lowercase().trim()
            if ((l.contains("candidate's name") || l.contains("student's name") || l.contains("name of candidate") || l.contains("name of student") || l == "name" || l == "name:") &&
                !l.contains("father") && !l.contains("mother")
            ) {
                val nextLineName = validateAndFormatPersonName(allLines[i + 1])
                if (nextLineName != null) return nextLineName
            }
        }

        // 3. Aadhaar Card / ID Card Heuristic: Name sits 1 or 2 lines ABOVE "DOB", "Year of Birth", "S/O", "D/O", "W/O", "Father"
        for (i in allLines.indices) {
            val l = allLines[i].lowercase()
            if (l.contains("dob") || l.contains("date of birth") || l.contains("year of birth") ||
                l.contains("जन्म तिथि") || l.contains("s/o") || l.contains("d/o") ||
                l.contains("w/o") || l.contains("father's name") || l.contains("पिता का नाम")
            ) {
                for (offset in 1..2) {
                    if (i - offset >= 0) {
                        val prevLine = allLines[i - offset]
                        if (!prevLine.lowercase().contains("mother")) {
                            val candidate = validateAndFormatPersonName(prevLine)
                            if (candidate != null) return candidate
                        }
                    }
                }
            }
        }

        // 4. PAN Card / General ID Heuristic: Look for clean 2-3 word all-letter lines that aren't headers
        if (docTag.contains("PAN") || docTag.contains("Aadhaar") || docTag.contains("Marksheet") || docTag.contains("Resume")) {
            for (line in allLines.take(25)) {
                val lowerLine = line.lowercase()
                if (lowerLine.contains("father") || lowerLine.contains("mother")) continue
                val candidate = validateAndFormatPersonName(line)
                if (candidate != null) return candidate
            }
        }

        return null
    }

    private fun validateAndFormatPersonName(rawInput: String): String? {
        val cleaned = rawInput
            .replace(Regex("(?i)\\b(s/o|d/o|w/o|c/o|dob|male|female|mr\\.?|ms\\.?|mrs\\.?|shri|smt|kumari|km\\.?|roll|reg|no).*"), "")
            .replace(Regex("[^a-zA-Z\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        val words = cleaned.split(" ").filter { it.isNotEmpty() }
        if (words.size !in 2..3) return null

        for (w in words) {
            val lw = w.lowercase()
            if (w.length < 2 || w.length > 15) return null
            if (lw in NON_NAME_WORDS) return null
            if (!lw.any { it in "aeiouy" }) return null
        }

        return words.joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    // CORE UPGRADE: Reads both Digital Text AND Renders Scanned PDFs to Bitmap for Spatial OCR!
    private suspend fun extractDeepContent(file: File): ExtractedDocContent {
        return try {
            when (file.extension.lowercase()) {
                "txt", "md", "csv", "json", "py", "kt", "java", "xml", "html" -> {
                    val text = file.bufferedReader().use { reader ->
                        val buf = CharArray(3000)
                        val read = reader.read(buf, 0, 3000)
                        if (read > 0) String(buf, 0, read) else ""
                    }
                    ExtractedDocContent(text, text.lines())
                }
                "pdf" -> {
                    // Step 1: Try Digital Text Extraction via PDFBox
                    var digitalText = ""
                    try {
                        PDDocument.load(file).use { doc ->
                            val stripper = PDFTextStripper().apply {
                                startPage = 1
                                endPage = doc.numberOfPages.coerceAtMost(2)
                            }
                            digitalText = stripper.getText(doc).trim()
                        }
                    } catch (_: Exception) {}

                    // Step 2: If PDF is a Scanned Image (digitalText has < 40 chars), render Page 1 to Bitmap & run ML Kit OCR!
                    if (digitalText.length < 40) {
                        runOcrOnScannedPdf(file)
                    } else {
                        ExtractedDocContent(digitalText.take(3000), digitalText.lines())
                    }
                }
                "jpg", "jpeg", "png", "webp" -> {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                    val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                    val sampleSize = if (maxDim > 2800) 2 else 1

                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                        ?: return ExtractedDocContent("", emptyList())
                    val result = runSpatialOcrOnBitmap(bmp)
                    bmp.recycle()
                    result
                }
                else -> ExtractedDocContent(file.nameWithoutExtension, listOf(file.nameWithoutExtension))
            }
        } catch (_: Exception) {
            ExtractedDocContent(file.nameWithoutExtension, listOf(file.nameWithoutExtension))
        }
    }

    // Renders Page 1 of a Scanned PDF into a crisp Bitmap using Android's native PdfRenderer and runs OCR
    private suspend fun runOcrOnScannedPdf(pdfFile: File): ExtractedDocContent {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return ExtractedDocContent("", emptyList())

            val page = renderer.openPage(0)
            // Render at 2x scale (approx 150-200 DPI) on a white background so OCR reads small marksheet text clearly
            val width = (page.width * 2).coerceAtMost(2200)
            val height = (page.height * 2).coerceAtMost(2800)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            val ocrContent = runSpatialOcrOnBitmap(bitmap)
            bitmap.recycle()
            ocrContent
        } catch (_: Exception) {
            ExtractedDocContent("", emptyList())
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    // Groups ML Kit OCR lines by their Y-coordinate (vertical position) so table columns stay on the same line!
    // Example: Left column "Candidate's Name" (Y=410) + Right column "ROHIT KUMAR" (Y=412) -> "Candidate's Name : ROHIT KUMAR"
    private suspend fun runSpatialOcrOnBitmap(bitmap: Bitmap): ExtractedDocContent {
        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText: Text = ocrRecognizer.process(image).await()

        val allLines = mutableListOf<Text.Line>()
        for (block in visionText.textBlocks) {
            allLines.addAll(block.lines)
        }

        if (allLines.isEmpty()) return ExtractedDocContent("", emptyList())

        // Sort all detected text lines top-to-bottom by Y coordinate
        val sortedByY = allLines.sortedBy { it.boundingBox?.centerY() ?: 0 }

        val reconstructedRows = mutableListOf<MutableList<Text.Line>>()
        val yTolerancePx = 24 // Lines within 24px vertically belong to the same horizontal table row

        for (line in sortedByY) {
            val cy = line.boundingBox?.centerY() ?: 0
            val existingRow = reconstructedRows.lastOrNull { row ->
                val rowY = row.first().boundingBox?.centerY() ?: 0
                abs(cy - rowY) <= yTolerancePx
            }
            if (existingRow != null) {
                existingRow.add(line)
            } else {
                reconstructedRows.add(mutableListOf(line))
            }
        }

        // Sort each horizontal row left-to-right by X coordinate and join with " : "
        val spatialRowStrings = reconstructedRows.map { row ->
            row.sortedBy { it.boundingBox?.left ?: 0 }
                .joinToString(" : ") { it.text.trim() }
        }

        val combinedFullText = spatialRowStrings.joinToString("\n")
        return ExtractedDocContent(combinedFullText.take(3500), spatialRowStrings)
    }

    // Strips old broken names like "Marksheet_1", "Marksheet_2", "Document_1" so re-classification starts fresh
    private fun stripOldGenericNames(name: String): String {
        return name
            .replace(Regex("_\\d+$"), "")
            .replace(Regex("(?i)^(Academic_Marksheet|Marksheet|Certificate|Invoice|Document|Doc|Aadhaar_Card|PAN_Card)_*"), "")
    }

    private fun extractTopicTag(rawText: String, origName: String, prefix: String): String {
        if (!isGenericFilename(origName) && origName.length >= 4) {
            return origName
        }
        val words = rawText.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9]"), "") }
            .filter { w ->
                w.length in 4..14 &&
                        w.lowercase() !in NON_NAME_WORDS &&
                        w.lowercase() !in setOf("this", "that", "with", "from", "have", "your", "page")
            }
            .distinctBy { it.lowercase() }
            .take(2)
        return if (words.isNotEmpty()) {
            "${prefix}_${words.joinToString("_")}"
        } else {
            prefix
        }
    }

    private fun isGenericFilename(name: String): Boolean {
        val lower = name.lowercase()
        return lower.isBlank() || lower.startsWith("img") || lower.startsWith("wa0") ||
                lower.startsWith("scan") || lower.startsWith("screenshot") ||
                lower.startsWith("doc") || lower.startsWith("marksheet") ||
                lower.startsWith("whatsapp") || lower.startsWith("camscanner") ||
                lower.startsWith("adobe") || lower.matches(Regex("^[0-9_-]+$"))
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
        if (isCancelled || currentDepth > maxDepth || outList.size >= 200) return
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
