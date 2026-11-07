package com.assistant.taba9

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.ContactsContract
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

        // Notice: "ai_organized_files" is NO LONGER blocked here so already-classified files get re-classified!
        private val PROTECTED_DIRS = setOf(
            "android", "dcim", "music", "movies", "ringtones", "alarms", "notifications"
        )

        // Words that should NEVER be mistaken for a person's name on an ID or Marksheet
        private val NAME_STOPWORDS = setOf(
            "government", "india", "income", "tax", "department", "unique", "identification",
            "authority", "election", "commission", "driving", "licence", "license", "union",
            "republic", "high", "school", "intermediate", "examination", "board", "university",
            "institute", "technology", "prasad", "uttar", "pradesh", "secondary", "education",
            "certificate", "marksheet", "statement", "marks", "grade", "sheet", "provisional",
            "admit", "card", "hall", "ticket", "male", "female", "address", "date", "birth",
            "permanent", "account", "number", "signature", "registrar", "controller", "result",
            "semester", "bachelor", "master", "computer", "science", "engineering", "national",
            "testing", "agency", "ministry", "human", "resource", "development", "roll", "code"
        )
    }

    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val knownPeople = mutableListOf<String>()

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun organizeWholeStorage(): String = withContext(Dispatchers.IO) {
        isCancelled = false
        loadKnownPeopleAndContacts()

        val rootDir = Environment.getExternalStorageDirectory()
        if (!rootDir.exists() || !rootDir.canRead()) {
            return@withContext "Cannot access tablet storage. Grant All Files Access."
        }

        val masterSortedHub = File(rootDir, "AI_Organized_Files")
        if (!masterSortedHub.exists()) masterSortedHub.mkdirs()

        val candidateFiles = mutableListOf<File>()
        // Scan both outside storage AND inside AI_Organized_Files so previously sorted files get re-classified!
        collectManageableFiles(rootDir, candidateFiles, maxDepth = 5, currentDepth = 0)

        if (candidateFiles.isEmpty()) {
            return@withContext "No documents, IDs, or images found across tablet storage."
        }

        val summary = StringBuilder("Re-Classified & Person-Sorted ${candidateFiles.size} files:\n")
        var movedOrUpdatedCount = 0

        for (file in candidateFiles) {
            if (isCancelled) {
                summary.append("\n🛑 Stopped by Master OFF.")
                break
            }

            val snippet = extractSnippetSafely(file)
            val cleanOrigName = stripOldAutoPrefixes(file.nameWithoutExtension)
            val decision = classifyByPersonAndDocument(file, cleanOrigName, snippet)

            val targetDir = File(masterSortedHub, decision.targetRelativeDir)
            if (!targetDir.exists()) targetDir.mkdirs()

            val sanitizedBase = decision.newBaseFileName
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { cleanOrigName }

            var destFile = File(targetDir, "$sanitizedBase.${file.extension}")

            // If the file is ALREADY in the exact right folder with the exact right name, skip moving
            if (file.canonicalPath == destFile.canonicalPath) {
                continue
            }

            if (destFile.exists()) {
                destFile = File(targetDir, "${sanitizedBase}_${System.currentTimeMillis() % 1000}.${file.extension}")
            }

            if (file.renameTo(destFile)) {
                movedOrUpdatedCount++
                summary.append("• ${file.name} ➔ ${decision.targetRelativeDir}/${destFile.name}\n")
            }
        }

        // Clean up any empty old folders left behind inside AI_Organized_Files after re-classification
        deleteEmptyDirectories(masterSortedHub)

        summary.append("Total classified/updated: $movedOrUpdatedCount files.")
        summary.toString()
    }

    private fun classifyByPersonAndDocument(
        file: File,
        cleanOrigName: String,
        snippet: String
    ): SmartFileDecision {
        val combinedText = "$cleanOrigName\n$snippet"
        val lower = combinedText.lowercase()

        // STEP 1: Extract Person Name from Document Content or Filename
        val detectedPerson = extractPersonName(combinedText)
        val personFolderName = detectedPerson?.replace(Regex("\\s+"), "_")
        val personPrefix = if (personFolderName != null) "${personFolderName}_" else ""

        // STEP 2: Detect Specific Document Type & Sub-Type
        val docInfo = detectDetailedDocumentType(lower, combinedText, cleanOrigName)
        val mainCategory = docInfo.first
        val subCategory = docInfo.second
        val docSpecificTitle = docInfo.third

        // STEP 3: Build Folder Hierarchy
        // If a Person Name is found OR it's a Personal ID / Marksheet / Exam / Resume, organize by Person!
        val isPersonCentricDoc = mainCategory in setOf(
            "Personal_IDs", "Marksheets_Certificates", "Exams_Admissions", "Career_Resumes"
        )

        val relativeDir = when {
            personFolderName != null -> {
                // e.g. People_Records/Rishi_Yadav/Marksheets_Certificates
                "People_Records/$personFolderName/$mainCategory"
            }
            isPersonCentricDoc -> {
                // Personal ID/Marksheet where name wasn't readable by OCR -> Unidentified_Person
                "$mainCategory/$subCategory"
            }
            subCategory.isNotEmpty() -> {
                "$mainCategory/$subCategory"
            }
            else -> mainCategory
        }

        val finalBaseName = "$personPrefix$docSpecificTitle"
        return SmartFileDecision(relativeDir, finalBaseName)
    }

    // Detects 25+ Indian IDs, Marksheets, Certificates, Academic & Financial docs accurately
    private fun detectDetailedDocumentType(
        lower: String,
        rawCombined: String,
        cleanOrigName: String
    ): Triple<String, String, String> {

        // --- A. PERSONAL IDs ---
        val panMatch = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b").find(rawCombined.uppercase())
        if (panMatch != null || lower.contains("income tax department") || lower.contains("permanent account number") || lower.contains("pan card")) {
            val tail = panMatch?.value?.takeLast(4) ?: ""
            return Triple("Personal_IDs", "PAN_Card", if (tail.isNotEmpty()) "PAN_Card_$tail" else "PAN_Card")
        }

        val aadhaarMatch = Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b").find(rawCombined)
        if (aadhaarMatch != null || lower.contains("unique identification") || lower.contains("uidai") || lower.contains("aadhaar") || lower.contains("mera aadhaar")) {
            val tail = aadhaarMatch?.value?.takeLast(4) ?: ""
            return Triple("Personal_IDs", "Aadhaar_Card", if (tail.isNotEmpty()) "Aadhaar_Card_$tail" else "Aadhaar_Card")
        }

        val voterMatch = Regex("\\b[A-Z]{3}[0-9]{7}\\b").find(rawCombined.uppercase())
        if (voterMatch != null || lower.contains("election commission") || lower.contains("elector photo") || lower.contains("voter")) {
            return Triple("Personal_IDs", "Voter_ID", "Voter_ID_Card")
        }

        if (lower.contains("driving licence") || lower.contains("driving license") || lower.contains("transport department")) {
            return Triple("Personal_IDs", "Driving_License", "Driving_License")
        }

        if (lower.contains("passport") || lower.contains("republic of india")) {
            return Triple("Personal_IDs", "Passport", "Passport")
        }

        if (lower.contains("ration card") || lower.contains("food and civil supplies")) {
            return Triple("Personal_IDs", "Ration_Card", "Ration_Card")
        }

        // --- B. MARKSHEETS, DEGREES & GOVT CERTIFICATES ---
        if (lower.contains("marksheet") || lower.contains("marks statement") || lower.contains("statement of marks") ||
            lower.contains("grade sheet") || lower.contains("high school") || lower.contains("intermediate") ||
            lower.contains("secondary education") || (lower.contains("semester") && (lower.contains("sgpa") || lower.contains("cgpa") || lower.contains("marks")))
        ) {
            val level = when {
                lower.contains("high school") || lower.contains("class x") || lower.contains("10th") || lower.contains("secondary school examination") -> "10th_HighSchool_Marksheet"
                lower.contains("intermediate") || lower.contains("class xii") || lower.contains("12th") || lower.contains("senior school") -> "12th_Intermediate_Marksheet"
                Regex("semester\\s*[1-8]|sem\\s*[1-8]|b\\.?tech").containsMatchIn(lower) -> {
                    val sem = Regex("(?:semester|sem)\\s*([1-8]|[i|v|x]+)").find(lower)?.groupValues?.getOrNull(1)?.uppercase() ?: ""
                    if (sem.isNotEmpty()) "BTech_Sem_${sem}_Marksheet" else "BTech_Marksheet"
                }
                else -> "Academic_Marksheet"
            }
            return Triple("Marksheets_Certificates", "Marksheets", level)
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
            return Triple("Marksheets_Certificates", "School_College_Certificates", "Migration_TC_Certificate")
        }
        if (lower.contains("certificate of completion") || lower.contains("nptel") || lower.contains("swayam") || lower.contains("internship") || lower.contains("workshop") || lower.contains("certificate")) {
            val topic = extractMeaningfulKeywords(rawCombined, cleanOrigName, "Certificate")
            return Triple("Marksheets_Certificates", "Course_Certificates", topic)
        }

        // --- C. EXAMS, ADMIT CARDS & ADMISSIONS ---
        if (lower.contains("admit card") || lower.contains("hall ticket") || lower.contains("e-admit")) {
            val exam = when {
                lower.contains("gate") -> "GATE_Admit_Card"
                lower.contains("ugc") || lower.contains("net") -> "UGC_NET_Admit_Card"
                else -> "Exam_Admit_Card"
            }
            return Triple("Exams_Admissions", "Admit_Cards", exam)
        }
        if (lower.contains("scorecard") || lower.contains("score card") || lower.contains("rank card") || lower.contains("jrf") || lower.contains("qualified")) {
            return Triple("Exams_Admissions", "Scorecards_Results", extractMeaningfulKeywords(rawCombined, cleanOrigName, "Scorecard"))
        }
        if (lower.contains("allotment") || lower.contains("counselling") || lower.contains("application form") || lower.contains("confirmation page") || lower.contains("fee receipt")) {
            return Triple("Exams_Admissions", "Admission_Forms", extractMeaningfulKeywords(rawCombined, cleanOrigName, "Application"))
        }

        // --- D. RESUMES & CAREER ---
        if (lower.contains("curriculum vitae") || lower.contains("resume") || (lower.contains("skills") && lower.contains("education") && lower.contains("projects"))) {
            return Triple("Career_Resumes", "CV_Resume", "Resume_CV")
        }
        if (lower.contains("offer letter") || lower.contains("letter of intent") || lower.contains("appointment letter")) {
            return Triple("Career_Resumes", "Offer_Letters", "Offer_Letter")
        }

        // --- E. FINANCE, BANKING & BILLS ---
        if (lower.contains("bank statement") || lower.contains("passbook") || lower.contains("ifsc") || lower.contains("account statement")) {
            return Triple("Finance_Bills_Receipts", "Bank_Docs", "Bank_Statement")
        }
        if (lower.contains("irctc") || lower.contains("boarding pass") || lower.contains("pnr") || lower.contains("e-ticket")) {
            return Triple("Finance_Bills_Receipts", "Travel_Tickets", "Travel_Ticket")
        }
        if (lower.contains("invoice") || lower.contains("tax invoice") || lower.contains("gstin") || lower.contains("receipt") || lower.contains("bill amount") || lower.contains("upi")) {
            return Triple("Finance_Bills_Receipts", "Invoices_Bills", extractMeaningfulKeywords(rawCombined, cleanOrigName, "Invoice_Bill"))
        }

        // --- F. ACADEMIC STUDY MATERIAL & RESEARCH PAPERS ---
        if (lower.contains("abstract") && lower.contains("introduction") && (lower.contains("references") || lower.contains("methodology") || lower.contains("ieee"))) {
            return Triple("Academic_Study_Material", "Research_Papers", extractMeaningfulKeywords(rawCombined, cleanOrigName, "Paper"))
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
                else -> "General_CS_Notes"
            }
            val title = extractMeaningfulKeywords(rawCombined, cleanOrigName, subject)
            return Triple("Academic_Study_Material", subject, title)
        }

        if (aiCat == "Code_And_Data") {
            return Triple("Code_And_Data", "Source_Files", cleanOrigName)
        }

        // Fallback with meaningful content title instead of leaving IMG_001
        val fallbackTitle = extractMeaningfulKeywords(rawCombined, cleanOrigName, "Doc")
        return Triple(aiCat, "General", fallbackTitle)
    }

    // Extracts Person Name from Document Text using 3 layers:
    // 1. Known People from rules.json & Tablet Contacts
    // 2. Explicit Form Labels ("Student Name:", "Candidate's Name:", "Name of Applicant:", "Certify that")
    // 3. Relation Anchors ("S/O", "D/O", "W/O", "Father's Name")
    private fun extractPersonName(text: String): String? {
        val cleanText = text.replace("_", " ")
        val lowerText = cleanText.lowercase()

        // Layer 1: Check Known People list (from rules.json + Device Contacts)
        for (person in knownPeople) {
            if (person.length >= 4 && lowerText.contains(person.lowercase())) {
                return toTitleCase(person)
            }
        }

        // Layer 2: Explicit Indian Marksheet / ID / Certificate Name Labels
        val labelPatterns = listOf(
            Regex("(?:candidate'?s?\\s*name|student'?s?\\s*name|name\\s*of\\s*(?:the\\s*)?(?:student|candidate|applicant|holder)|applicant\\s*name)\\s*[:\\.-]?\\s*(?:mr\\.?|ms\\.?|shri|smt|km\\.?)?\\s*([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+){1,2})", RegexOption.IGNORE_CASE),
            Regex("(?:certify\\s+that|certified\\s+that|awarded\\s+to|presented\\s+to)\\s+(?:mr\\.?|ms\\.?|shri|smt|km\\.?)?\\s*([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+){1,2})", RegexOption.IGNORE_CASE),
            Regex("(?:^|\\n)\\s*name\\s*[:\\.-]\\s*(?:mr\\.?|ms\\.?|shri|smt)?\\s*([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+){1,2})", RegexOption.IGNORE_CASE),
            // Line immediately above S/O, D/O, W/O or DOB on Aadhaar cards
            Regex("([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,2})\\s*\\n\\s*(?:S/O|D/O|W/O|C/O|DOB|Year of Birth|जन्म तिथि)")
        )

        for (regex in labelPatterns) {
            val match = regex.find(cleanText)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (isValidPersonName(candidate)) {
                    return toTitleCase(candidate)
                }
            }
        }

        return null
    }

    private fun isValidPersonName(candidate: String): Boolean {
        val words = candidate.trim().split(Regex("\\s+"))
        if (words.size !in 2..3) return false
        for (w in words) {
            val lw = w.lowercase()
            if (w.length < 2 || lw in NAME_STOPWORDS || w.any { !it.isLetter() }) {
                return false
            }
        }
        return true
    }

    private fun toTitleCase(name: String): String {
        return name.trim().split(Regex("\\s+")).joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun stripOldAutoPrefixes(name: String): String {
        return name
            .replace(Regex("^(Certificate|Invoice|Academic_Notes|Personal_IDs|General_Notes|DSA_Algorithms|Operating_Systems|Doc)_+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("_\\d{3,4}$"), "")
    }

    private fun extractMeaningfulKeywords(rawText: String, origName: String, prefix: String): String {
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
            "${prefix}_$origName"
        }
    }

    private fun isGenericFilename(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("img") || lower.startsWith("wa0") || lower.startsWith("scan") ||
                lower.startsWith("screenshot") || lower.startsWith("doc") || lower.startsWith("whatsapp") ||
                lower.startsWith("camscanner") || lower.startsWith("adobe") || lower.matches(Regex("^[0-9_-]+$"))
    }

    @SuppressLint("Range")
    private fun loadKnownPeopleAndContacts() {
        knownPeople.clear()
        try {
            val jsonStr = context.assets.open("rules.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val arr = root.optJSONArray("known_people")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val p = arr.getString(i).trim()
                    if (p.isNotEmpty()) knownPeople.add(p)
                }
            }
        } catch (_: Exception) {}

        // Also pull multi-word contact names from the tablet's Contacts so family/friends are recognized automatically
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)?.trim() ?: continue
                    if (name.split(Regex("\\s+")).size in 2..3 && isValidPersonName(name)) {
                        if (knownPeople.none { kp -> kp.equals(name, ignoreCase = true) }) {
                            knownPeople.add(name)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun collectManageableFiles(dir: File, outList: MutableList<File>, maxDepth: Int, currentDepth: Int) {
        if (isCancelled || currentDepth > maxDepth || outList.size >= 150) return
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
                            // Scan up to 2 pages so if Page 1 is a title page, Page 2 still gets read
                            endPage = doc.numberOfPages.coerceAtMost(2)
                        }
                        stripper.getText(doc).take(2500)
                    }
                }
                "jpg", "jpeg", "png", "webp" -> {
                    // Use full resolution (unless image is > 2500px) so small text on Marksheets & IDs is crystal clear for OCR
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
