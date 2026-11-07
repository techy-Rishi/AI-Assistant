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
    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun organizeDownloadsFolder(): String = withContext(Dispatchers.IO) {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val files = downloadDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
            ?: return@withContext "Unable to read /Download. Grant All Files Access."

        if (files.isEmpty()) return@withContext "No loose files found in /Download."

        val summary = StringBuilder("Sorted ${files.size} files:\n")
        for (file in files) {
            val snippet = extractSnippetSafely(file)
            val categoryFolder = aiEngine.classifyFileContent("${file.name} $snippet")
            val targetDir = File(downloadDir, categoryFolder)
            if (!targetDir.exists()) targetDir.mkdirs()

            val destFile = File(targetDir, file.name)
            val moved = file.renameTo(destFile)
            if (moved) {
                summary.append("• ${file.name} → $categoryFolder/\n")
            }
        }
        summary.toString()
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
                            endPage = 1 // Memory-safe for 4GB RAM Galaxy Tab A9
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
