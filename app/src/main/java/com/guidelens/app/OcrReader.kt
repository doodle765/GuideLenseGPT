package com.guidelens.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Offline OCR (ML Kit bundled model — no network needed). */
class OcrReader {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = text.textBlocks
                    .flatMap { it.lines }
                    .map { it.text.trim() }
                    .filter { it.length > 2 }
                    .sortedByDescending { it.length }
                    .take(3)
                cont.resume(lines.joinToString(". "))
            }
            .addOnFailureListener { cont.resume("") }
    }

    fun close() {
        recognizer.close()
    }
}
