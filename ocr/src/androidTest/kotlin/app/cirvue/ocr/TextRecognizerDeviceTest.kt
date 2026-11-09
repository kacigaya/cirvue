package app.cirvue.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic images only. Models must be staged by the caller; no image files are written. */
@RunWith(AndroidJUnit4::class)
class TextRecognizerDeviceTest {
    @Test fun renderedText() {
        val args = InstrumentationRegistry.getArguments()
        val root = File(requireNotNull(args.getString("modelDir")) { "Pass -e modelDir /data/local/tmp/cirvue-ocr-test" })
        val environment = ai.onnxruntime.OrtEnvironment.getEnvironment()
        for (name in listOf("det", "rec")) {
            environment.createSession(File(root, "$name.onnx").absolutePath).use { session ->
                Log.i("CirvueOcrTest", "$name inputs=${session.inputInfo} outputs=${session.outputInfo}")
            }
        }
        TextRecognizer.open(File(root, "det.onnx"), File(root, "rec.onnx"), File(root, "rec.yml")).use { ocr ->
            for ((width, height) in listOf(1080 to 2400, 820 to 140)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
                    canvas.drawText("Sony WH-1000XM5", 40f, 85f, paint)
                    if (height > 140) {
                        paint.textSize = 36f
                        canvas.drawText("Wireless headphones for daily listening.", 40f, 200f, paint)
                        canvas.drawText("Clear sound and a comfortable fit.", 40f, 260f, paint)
                        canvas.drawText("Battery life up to thirty hours.", 40f, 320f, paint)
                    }
                    var start = SystemClock.elapsedRealtimeNanos()
                    val boxes = ocr.detect(bitmap)
                    val detMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    start = SystemClock.elapsedRealtimeNanos()
                    val lines = ocr.recognize(bitmap)
                    val recMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                    Log.i("CirvueOcrTest", "${width}x$height detectMs=$detMs recognizeMs=$recMs boxes=$boxes")
                    lines.forEach { Log.i("CirvueOcrTest", "text=${it.text} confidence=${it.confidence} box=${it.box}") }
                    assertTrue("No boxes for ${width}x$height", boxes.isNotEmpty())
                    val text = lines.joinToString(" ") { it.text }
                    assertTrue(text, text.contains("Sony", ignoreCase = true))
                    assertTrue(text, text.contains("WH-1000XM5"))
                    if (height > 140) assertTrue(text, text.contains("comfortable fit"))
                    assertTrue(lines.all { it.confidence in 0f..1f })
                } finally { bitmap.recycle() }
            }
            val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                blank.eraseColor(Color.WHITE)
                assertTrue(ocr.recognize(blank).isEmpty())
            } finally { blank.recycle() }
        }
    }
}
