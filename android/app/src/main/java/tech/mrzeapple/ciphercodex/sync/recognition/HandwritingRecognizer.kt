package tech.mrzeapple.ciphercodex.sync.recognition

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea

private const val PAGE_W = 1404f
private const val PAGE_H = 1872f

/** Thin blocking adapter over ML Kit Digital Ink (call only from Dispatchers.IO).
 *  Everything testable (segmentation, staleness, orchestration) lives OUTSIDE this class. */
class HandwritingRecognizer {
    private val model = DigitalInkRecognitionModel.builder(
        DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")!!).build()
    private val manager = RemoteModelManager.getInstance()
    private val client by lazy {
        DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
    }

    fun modelDownloaded(): Boolean = Tasks.await(manager.isModelDownloaded(model))

    fun downloadModel() {
        Tasks.await(manager.download(model, DownloadConditions.Builder().build()))
    }

    fun recognizeLine(line: List<RecStroke>, preContext: String): String {
        if (line.isEmpty()) return ""
        val ink = Ink.builder().apply {
            for (s in line) addStroke(Ink.Stroke.builder().apply {
                for (p in s.points)
                    addPoint(Ink.Point.create(p.x * PAGE_W, p.y * PAGE_H, s.createdAt + p.t))
            }.build())
        }.build()
        val top = line.minOf { s -> s.points.minOf { it.y } } * PAGE_H
        val bottom = line.maxOf { s -> s.points.maxOf { it.y } } * PAGE_H
        val ctx = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(20))
            .setWritingArea(WritingArea(PAGE_W, ((bottom - top) * 1.6f).coerceAtLeast(60f)))
            .build()
        val result = Tasks.await(client.recognize(ink, ctx))
        return result.candidates.firstOrNull()?.text ?: ""
    }

    fun close() = client.close()
}
