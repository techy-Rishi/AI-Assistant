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
            return@withContext "Cannot access tablet storage. Grant All Files Access in Settings."
        }

        val masterSortedHub = File(rootDir, "AI_Organized_Files")
        if (!masterSortedHub.exists()) masterSortedHub.mkdirs()

        val candidateFiles = mutableListOf<File>()
        collectManageableFiles(rootDir, candidateFiles, maxDepth = 4, currentDepth = 0)

        if (candidateFiles.isEmpty()) {
            return@withContext "No unsorted documents, PDFs, or images found across tablet storage."
        }

        val summary = StringBuilder("Scanned tablet storage & organized ${candidateFiles.size} files into /AI_Organized_Files:\n")
        var movedCount = 0

        for (file in candidateFiles) {
            if (isCancelled) {
                summary.append("\n🛑 File organization stopped by Master OFF button.")
                break
            }
            val snippet = extractSnippetSafely(file)
            val categoryFolder = aiEngine.classifyFileContent("${file.name} $snippet")
            val targetDir = File(masterSortedHub, categoryFolder)
            if (!targetDir.exists()) targetDir.mkdirs()

            // Avoid overwriting if a file with the same name exists
            var destFile = File(targetDir, file.name)
            if (destFile.exists()) {
                destFile = File(targetDir, "${file.nameWithoutExtension}_${System.currentTimeMillis() % 10000}.${file.extension}")
            }

            if (file.renameTo(destFile)) {
                movedCount++
                summary.append("• ${file.name} → AI_Organized_Files/$categoryFolder/\n")
            }
        }
        summary.append("Total moved: $movedCount files.")
        summary.toString()
    }

    private fun collectManageableFiles(
        dir: File,
        outList: MutableList<File>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        if (isCancelled || currentDepth > maxDepth || outList.size >= 80) return
        val children = dir.listFiles() ?: return

        for (child in children) {
            if (isCancelled) return
            if (child.name.startsWith(".")) continue

            if (child.isDirectory) {
                if (currentDepth == 0 && PROTECTED_DIRS.contains(child.name.lowercase())) {
                    continue
                }
                collectManageableFiles(child, outList, maxDepth, currentDepth + 1)
            } else if (child.isFile) {
                val ext = child.extension.lowercase()
                if (SUPPORTED_EXTENSIONS.contains(ext)) {
                    outList.add(child)
                }
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
