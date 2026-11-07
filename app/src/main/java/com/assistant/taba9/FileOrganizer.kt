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

        // "ai_organized_files" is NOT blocked so already-classified files are always re-checked & re-classified
        private val PROTECTED_DIRS = setOf(
            "android", "dcim", "music", "movies", "ringtones", "alarms", "notifications"
        )

        // Official headers/words that should NEVER be confused with a human name
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
            "download", "date", "issue", "valid", "till", "blood", "group", "organ", "donor",
            "elector", "photo", "identity", "pan", "tan", "gst", "invoice", "receipt", "bank",
            "branch", "ifsc", "account", "saving", "current", "passbook", "syllabus", "lecture"
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

        val summary = StringBuilder("Scanned & Classified ${candidateFiles.size} files:\n")
        var movedOrUpdatedCount = 0

        for (file in candidateFiles) {
            if (isCancelled) {
                summary.append("\n🛑 Stopped by Master OFF.")
                break
            }

            val snippet = extractSnippetSafely(file)
            val cleanOrigName = stripPreviousAutoTags(file.nameWithoutExtension)
            val decision = classifyUniversalDocument(cleanOrigName, snippet)

            val targetDir = File(masterSortedHub, decision.targetRelativeDir)
            if (!targetDir.exists()) targetDir.mkdirs()

            val sanitizedBase = decision.newBaseFileName
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { cleanOrigName }

            // Check if this exact file is ALREADY in targetDir with the right base name (or _1, _2...)
            if (isAlreadyProperlyNamedInDir(file, targetDir, sanitizedBase)) {
                continue
            }

            // Sequential Duplicate Numbering: BaseName.ext -> BaseName_1.ext -> BaseName_2.ext -> BaseName_3.ext
            val destFile = getNextAvailableFile(targetDir, sanitizedBase, file.extension)

            if (file.renameTo(destFile)) {
                movedOrUpdatedCount++
                summary.append("• ${file.name} ➔ ${decision.targetRelativeDir}/${destFile.name}\n")
            }
        }

        // Remove any empty old folders left inside AI_Organized_Files
        deleteEmptyDirectories(masterSortedHub)

        summary.append("Total moved/renamed: $movedOrUpdatedCount files.")
        summary.toString()
    }

    // Checks if file is already inside the right folder with the right base name so re-runs don't bump _1 to _2
    private fun isAlreadyProperlyNamedInDir(file: File, targetDir: File, expectedBase: String): Boolean {
        if (file.parentFile?.canonicalPath != targetDir.canonicalPath) return false
        val currentBase = file.nameWithoutExtension
        if (currentBase.equals(expectedBase, ignoreCase = true)) return true
        val numberedRegex = Regex("^${Regex.escape(expectedBase)}_\\d+$", RegexOption.IGNORE_CASE)
        return numberedRegex.matches(currentBase)
    }

    // Generates Name_Aadhaar_Card.jpg, and if taken: Name_Aadhaar_Card_1.jpg, Name_Aadhaar_Card_2.jpg...
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

    private fun classifyUniversalDocument(
        cleanOrigName: String,
        snippet: String
    ): SmartFileDecision {
        val combinedText = "$snippet\n$cleanOrigName"
        val lower = combinedText.lowercase()

        // STEP 1: Detect exact Document Type (e.g., Aadhaar_Card, PAN_Card, 10th_Marksheet, etc.)
        val docInfo = detectDocumentType(lower, combinedText, cleanOrigName)
        val mainCategory = docInfo.first
        val subCategory = docInfo.second
        val docTypeName = docInfo.third

        // STEP 2: Universal Unknown Person Name Extractor (works for ANY person without a known list)
        val detectedPerson = extractAnyPersonName(snippet, docTypeName)
        val personSlug = detectedPerson?.replace(Regex("\\s+"), "_")

        val isPersonDocument = mainCategory in setOf(
            "Personal_IDs", "Marksheets_Certificates", "Exams_Admissions", "Career_Resumes"
        )

        // STEP 3: Build Target Folder & File Name
        // Format: <Person_Name>_<DocTypeName> (e.g. Rahul_Sharma_Aadhaar_Card.jpg)
        val finalBaseName = if (personSlug != null) {
            "${personSlug}_$docTypeName"
        } else {
            docTypeName
        }

        val relativeDir = when {
            personSlug != null -> {
                // Organizes by that person's name: People_Records/Rahul_Sharma/Personal_IDs
                "People_Records/$personSlug/$mainCategory"
            }
            isPersonDocument -> {
                "$mainCategory/$subCategory"
            }
            subCategory.isNotEmpty() -> {
                "$mainCategory/$subCategory"
            }
            else -> mainCategory
        }

        return SmartFileDecision(relativeDir, finalBaseName)
    }

    // Universal Unknown Person Name Extractor — scans OCR/PDF lines structurally
    private fun extractAnyPersonName(rawSnippet: String, docType: String): String? {
        val lines = rawSnippet.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }

        // 1. Explicit Label Match (Marksheets, Certificates, Admit Cards, Resumes, IDs)
        // Matches: "Name : ...", "Candidate's Name : ...", "Student Name : ...", "Certify that ..."
        val labelRegexes = listOf(
            Regex("(?:candidate'?s?\\s*name|student'?s?\\s*name|name\\s*of\\s*(?:the\\s*)?(?:student|candidate|applicant|holder|cardholder)|applicant'?s?\\s*name)\\s*[:\\.-]?\\s*(?:mr\\.?|ms\\.?|mrs\\.?|shri|smt|km\\.?|kumari)?\\s*([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})", RegexOption.IGNORE_CASE),
            Regex("(?:this\\s+is\\s+to\\s+certify\\s+that|certified\\s+that|certify\\s+that|awarded\\s+to|presented\\s+to)\\s+(?:mr\\.?|ms\\.?|mrs\\.?|shri|smt|km\\.?|kumari)?\\s*([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\n)\\s*name\\s*[:\\.-]\\s*(?:mr\\.?|ms\\.?|mrs\\.?|shri|smt|km\\.?)?\\s*([A-Za-z]+(?:\\s+[A-Za-z]+){1,2})", RegexOption.IGNORE_CASE)
        )

        for (regex in labelRegexes) {
            for (match in regex.findAll(rawSnippet)) {
                val candidate = cleanExtractedName(match.groupValues[1])
                if (candidate != null) return candidate
            }
        }

        // 2. Multi-Line Label Check (When "Name" or "Candidate's Name" is on Line i, and actual name is on Line i+1)
        for (i in 0 until lines.size - 1) {
            val l = lines[i].lowercase()
            if (l in setOf("name", "name:", "candidate's name", "student's name", "name / नाम", "नाम / name")) {
                val nextLineCandidate = cleanExtractedName(lines[i + 1])
                if (nextLineCandidate != null) return nextLineCandidate
            }
        }

        // 3. Aadhaar Card Structural Heuristic:
        // On every Aadhaar card, the cardholder's English name sits 1 or 2 lines ABOVE "DOB:", "Year of Birth", "S/O", "D/O", "W/O", or "Male/Female"
        for (i in lines.indices) {
            val l = lines[i].lowercase()
            val isDobOrRelationAnchor = l.contains("dob") || l.contains("year of birth") ||
                    l.contains("जन्म तिथि") || l.contains("s/o") || l.contains("d/o") ||
                    l.contains("w/o") || l.contains("c/o") || l.contains("father")

            if (isDobOrRelationAnchor) {
                // Check 1 line above and 2 lines above (in case Hindi name is between English name and DOB)
                for (offset in 1..2) {
                    if (i - offset >= 0) {
                        val possibleName = cleanExtractedName(lines[i - offset])
                        if (possibleName != null) return possibleName
                    }
                }
            }

            // Aadhaar letter top section has "To" followed by Name
            if (l == "to" && i + 2 < lines.size) {
                val toName = cleanExtractedName(lines[i + 1]) ?: cleanExtractedName(lines[i + 2])
                if (toName != null) return toName
            }
        }

        // 4. PAN Card Structural Heuristic:
        // On PAN cards, the cardholder's name is in uppercase right below "INCOME TAX DEPARTMENT" / "GOVT. OF INDIA" and above Father's Name / DOB
        if (docType.contains("PAN", ignoreCase = true)) {
            for (line in lines) {
                val possibleName = cleanExtractedName(line)
                if (possibleName != null) return possibleName
            }
        }

        // 5. Resume / CV Heuristic: The very first valid 2-3 word human name in the top 5 lines of a Resume
        if (docType.contains("Resume", ignoreCase = true)) {
            for (line in lines.take(5)) {
                val possibleName = cleanExtractedName(line)
                if (possibleName != null) return possibleName
            }
        }

        return null
    }

    // Validates and formats a raw string into "First Last" Title Case if it's a real human name
    private fun cleanExtractedName(rawLine: String): String? {
        // Strip common relation suffixes if they got attached on the same line
        val stripped = rawLine
            .replace(Regex("(?i)\\b(s/o|d/o|w/o|c/o|dob|male|female|mr\\.?|ms\\.?|mrs\\.?|shri|smt|kumari).*"), "")
            .replace(Regex("[^a-zA-Z\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

        val words = stripped.split(" ").filter { it.isNotEmpty() }
        if (words.size !in 2..3) return null

        for (w in words) {
            val lw = w.lowercase()
            if (w.length < 2 || w.length > 15) return null
            if (lw in NON_NAME_WORDS) return null
            // Reject words with no vowels (OCR garbage like "XJkL")
            if (!lw.any { it in "aeiouy" }) return null
        }

        return words.joinToString(" ") { w ->
            w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    // Detects exact document type and returns Triple(MainCategory, SubCategory, CleanDocTypeSuffix)
    private fun detectDocumentType(
        lower: String,
        rawCombined: String,
        cleanOrigName: String
    ): Triple<String, String, String> {

        // --- 1. PERSONAL IDs ---
        val aadhaarMatch = Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b").find(rawCombined)
        if (aadhaarMatch != null || lower.contains("unique identification") || lower.contains("uidai") || lower.contains("aadhaar") || lower.contains("mera aadhaar") || lower.contains("adhar")) {
            return Triple("Personal_IDs", "Aadhaar_Cards", "Aadhaar_Card")
        }

        val panMatch = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b").find(rawCombined.uppercase())
        if (panMatch != null || lower.contains("income tax department") || lower.contains("permanent account number") || lower.contains("pan card")) {
            return Triple("Personal_IDs", "PAN_Cards", "PAN_Card")
        }

        val voterMatch = Regex("\\b[A-Z]{3}[0-9]{7}\\b").find(rawCombined.uppercase())
        if (voterMatch != null || lower.contains("election commission") || lower.contains("elector photo") || lower.contains("voter")) {
            return Triple("Personal_IDs", "Voter_IDs", "Voter_ID_Card")
        }

        if (lower.contains("driving licence") || lower.contains("driving license") || lower.contains("transport department")) {
            return Triple("Personal_IDs", "Driving_Licenses", "Driving_License")
        }

        if (lower.contains("passport") || lower.contains("republic of india")) {
            return Triple("Personal_IDs", "Passports", "Passport")
        }

        if (lower.contains("ration card") || lower.contains("food and civil supplies")) {
            return Triple("Personal_IDs", "Ration_Cards", "Ration_Card")
        }

        // --- 2. MARKSHEETS & CERTIFICATES ---
        if (lower.contains("marksheet") || lower.contains("marks statement") || lower.contains("statement of marks") ||
            lower.contains("grade sheet") || lower.contains("high school") || lower.contains("intermediate") ||
            lower.contains("secondary education") || (lower.contains("semester") && (lower.contains("sgpa") || lower.contains("cgpa") || lower.contains("marks")))
        ) {
            val docTag = when {
                lower.contains("high school") || lower.contains("class x") || lower.contains("10th") || lower.contains("secondary school examination") -> "10th_Marksheet"
                lower.contains("intermediate") || lower.contains("class xii") || lower.contains("12th") || lower.contains("senior school") -> "12th_Marksheet"
                Regex("semester\\s*[1-8]|sem\\s*[1-8]|b\\.?tech").containsMatchIn(lower) -> {
                    val sem = Regex("(?:semester|sem)\\s*([1-8]|[i|v|x]+)").find(lower)?.groupValues?.getOrNull(1)?.uppercase() ?: ""
                    if (sem.isNotEmpty()) "BTech_Sem_${sem}_Marksheet" else "BTech_Marksheet"
                }
                else -> "Marksheet"
            }
            return Triple("Marksheets_Certificates", "Marksheets", docTag)
        }

        if (lower.contains("caste certificate") || lower.contains("jati praman") || lower.contains("backward class") || lower.contains("scheduled caste")) {
            return Triple("Marksheets_Certificates", "Govt_Certificates", "Caste_Certificate")
        }
        if (lower.contains("income certificate") || lower.contains("aay praman") || lower.contains("annual income")) {
            return Triple("Marksheets_Certificates", "Govt_Certificates", "Income_Certificate")
        }
        if (lower.contains("domicile") || lower.contains("residence certificate") || lower.contains("niwas praman")) {
            return Triple("Marksheets_Certificates", "Govt_Certificates", "Domicile_Certificate")
        }
        if (lower.contains("transfer certificate") || lower.contains("migration certificate") || lower.contains("character certificate")) {
            return Triple("Marksheets_Certificates", "School_Certificates", "Migration_TC_Certificate")
        }
        if (lower.contains("certificate")) {
            return Triple("Marksheets_Certificates", "Certificates", "Certificate")
        }

        // --- 3. EXAMS & ADMISSIONS ---
        if (lower.contains("admit card") || lower.contains("hall ticket") || lower.contains("e-admit")) {
            val examTag = when {
                lower.contains("gate") -> "GATE_Admit_Card"
                lower.contains("ugc") || lower.contains("net") -> "UGC_NET_Admit_Card"
                else -> "Admit_Card"
            }
            return Triple("Exams_Admissions", "Admit_Cards", examTag)
        }
        if (lower.contains("scorecard") || lower.contains("score card") || lower.contains("rank card") || lower.contains("jrf")) {
            return Triple("Exams_Admissions", "Scorecards", "Scorecard")
        }
        if (lower.contains("allotment") || lower.contains("counselling") || lower.contains("application form") || lower.contains("confirmation page")) {
            return Triple("Exams_Admissions", "Admission_Docs", "Application_Form")
        }

        // --- 4. RESUMES & CAREER ---
        if (lower.contains("curriculum vitae") || lower.contains("resume") || (lower.contains("skills") && lower.contains("education") && lower.contains("projects"))) {
            return Triple("Career_Resumes", "Resumes", "Resume")
        }
        if (lower.contains("offer letter") || lower.contains("appointment letter") || lower.contains("internship")) {
            return Triple("Career_Resumes", "Offer_Letters", "Offer_Letter")
        }

        // --- 5. FINANCE & BILLS ---
        if (lower.contains("bank statement") || lower.contains("passbook") || lower.contains("ifsc")) {
            return Triple("Finance_Bills_Receipts", "Bank_Docs", "Bank_Passbook_Statement")
        }
        if (lower.contains("irctc") || lower.contains("boarding pass") || lower.contains("pnr") || lower.contains("e-ticket")) {
            return Triple("Finance_Bills_Receipts", "Tickets", "Travel_Ticket")
        }
        if (lower.contains("invoice") || lower.contains("gstin") || lower.contains("receipt") || lower.contains("bill amount") || lower.contains("upi")) {
            return Triple("Finance_Bills_Receipts", "Invoices_Bills", "Invoice_Receipt")
        }

        // --- 6. ACADEMIC STUDY MATERIAL & RESEARCH PAPERS ---
        if (lower.contains("abstract") && lower.contains("introduction") && (lower.contains("references") || lower.contains("methodology") || lower.contains("ieee"))) {
            val title = extractTopicTitle(rawCombined, cleanOrigName, "Research_Paper")
            return Triple("Academic_Study_Material", "Research_Papers", title)
        }

        val aiCat = aiEngine.classifyFileContent(rawCombined)
        if (aiCat == "Academic_Study_Material" || lower.contains("syllabus") || lower.contains("lecture") || lower.contains("algorithm") || lower.contains("operating system") || lower.contains("gate")) {
            val subject = when {
                lower.contains("operating system") || lower.contains("deadlock") || lower.contains("cpu scheduling") -> "Operating_Systems"
                lower.contains("algorithm") || lower.contains("data structure") || lower.contains("dynamic programming") -> "DSA_Algorithms"
                lower.contains("computer network") || lower.contains("tcp/ip") || lower.contains("osi model") -> "Computer_Networks"
                lower.contains("database") || lower.contains("sql") || lower.contains("normalization") -> "DBMS"
                lower.contains("compiler") || lower.contains("automata") || lower.contains("toc") -> "TOC_Compiler"
                lower.contains("machine learning") || lower.contains("computer vision") || lower.contains("lane detection") || lower.contains("neural") -> "AI_Computer_Vision"
                lower.contains("discrete") || lower.contains("engineering mathematics") || lower.contains("linear algebra") -> "Mathematics"
                lower.contains("syllabus") -> "Syllabus"
                else -> "CS_Notes"
            }
            val title = extractTopicTitle(rawCombined, cleanOrigName, subject)
            return Triple("Academic_Study_Material", subject, title)
        }

        if (aiCat == "Code_And_Data") {
            return Triple("Code_And_Data", "Source_Files", cleanOrigName)
        }

        val fallbackTitle = extractTopicTitle(rawCombined, cleanOrigName, "Document")
        return Triple(aiCat, "General", fallbackTitle)
    }

    // Strips old auto-generated prefixes and random numbers so re-classification produces a fresh, clean name
    private fun stripPreviousAutoTags(name: String): String {
        return name
            .replace(Regex("_\\d+$"), "")
            .replace(Regex("(?i)^(Certificate|Invoice|Academic_Notes|Personal_IDs|General_Notes|DSA_Algorithms|Operating_Systems|Doc|Document|Aadhaar_Card|PAN_Card)_+"), "")
    }

    private fun extractTopicTitle(rawText: String, origName: String, prefix: String): String {
        if (!isGenericFilename(origName) && origName.length >= 4) {
            return origName
        }
        val words = rawText.split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-zA-Z0-9]"), "") }
            .filter { w ->
                w.length in 4..14 &&
                        w.lowercase() !in setOf("this", "that", "with", "from", "have", "your", "page", "date", "http", "https")
            }
            .distinctBy { it.lowercase() }
            .take(3)
        return if (words.isNotEmpty()) {
            "${prefix}_${words.joinToString("_")}"
        } else {
            prefix
        }
    }

    private fun isGenericFilename(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("img") || lower.startsWith("wa0") || lower.startsWith("scan") ||
                lower.startsWith("screenshot") || lower.startsWith("doc") || lower.startsWith("whatsapp") ||
                lower.startsWith("camscanner") || lower.startsWith("adobe") || lower.matches(Regex("^[0-9_-]+$"))
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

    private suspend fun extractSnippetSafely(file: File): String {
        return try {
            when (file.extension.lowercase()) {
                "txt", "md", "csv", "json", "py", "kt", "java", "xml", "html" -> {
                    file.bufferedReader().use { reader ->
                        val buf = CharArray(2500)
                        val read = reader.read(buf, 0, 2500)
                        if (read > 0) String(buf, 0, read) else ""
                    }
                }
                "pdf" -> {
                    PDDocument.load(file).use { doc ->
                        val stripper = PDFTextStripper().apply {
                            startPage = 1
                            endPage = doc.numberOfPages.coerceAtMost(2)
                        }
                        stripper.getText(doc).take(2500)
                    }
                }
                "jpg", "jpeg", "png", "webp" -> {
                    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
                    val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                    val sampleSize = if (maxDim > 2600) 2 else 1

                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return ""
                    val image = InputImage.fromBitmap(bmp, 0)
                    val result = ocrRecognizer.process(image).await()
                    bmp.recycle()
                    result.text.take(2500)
                }
                else -> file.nameWithoutExtension
            }
        } catch (_: Exception) {
            file.nameWithoutExtension
        }
    }
}
