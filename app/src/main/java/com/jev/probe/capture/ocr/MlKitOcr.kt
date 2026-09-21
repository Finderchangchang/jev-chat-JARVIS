package com.jev.probe.capture.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * On-device OCR via ML Kit's bundled Chinese recognizer. Bundled, not the
 * play-services variant: the model ships inside the APK, so it also works on a
 * phone with no Google Play services and never downloads anything.
 *
 * Coordinates: ML Kit sees the (possibly cropped) screenshot, so a line's box is
 * in bitmap space. We add the crop offset back and divide by the screenshot
 * scale, handing the caller SCREEN coordinates — the same space node bounds use.
 * Set [scaleX]/[scaleY] from the ScreenCapture result before each batch.
 *
 * Recognition runs on ML Kit's own threads; the callback is posted to the main
 * thread so overlay work needs no extra hop.
 */
class MlKitOcr : OcrEngine {

    /** Screenshot bitmap size / screen size. Set per capture. */
    var scaleX: Float = 1f
    var scaleY: Float = 1f

    private val main = Handler(Looper.getMainLooper())

    override fun recognize(bitmap: Bitmap, region: Rect?, cb: (List<OcrLine>) -> Unit) {
        val src: Bitmap
        val ox: Int
        val oy: Int
        val cropped: Boolean
        if (region != null) {
            val r = Rect(region)
            if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.width() < 8 || r.height() < 8) {
                main.post { cb(emptyList()) }; return
            }
            src = try {
                Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
            } catch (e: Exception) {
                Log.w(TAG, "ocr crop failed: ${e.javaClass.simpleName}")
                main.post { cb(emptyList()) }; return
            }
            ox = r.left; oy = r.top; cropped = true
        } else {
            src = bitmap; ox = 0; oy = 0; cropped = false
        }

        val sx = if (scaleX > 0f) scaleX else 1f
        val sy = if (scaleY > 0f) scaleY else 1f
        val image = try {
            InputImage.fromBitmap(src, 0)
        } catch (e: Exception) {
            if (cropped) src.recycle()
            main.post { cb(emptyList()) }; return
        }

        client.process(image)
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val b = line.boundingBox ?: continue
                        val t = line.text.trim()
                        if (t.isEmpty()) continue
                        lines.add(OcrLine(t, Rect(
                            ((b.left + ox) / sx).toInt(),
                            ((b.top + oy) / sy).toInt(),
                            ((b.right + ox) / sx).toInt(),
                            ((b.bottom + oy) / sy).toInt())))
                    }
                }
                lines.sortBy { it.bounds.top }
                if (cropped) src.recycle()
                main.post { cb(lines) }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "ocr failed: ${e.javaClass.simpleName}")
                if (cropped) src.recycle()
                main.post { cb(emptyList()) }
            }
    }

    companion object {
        private const val TAG = "JEVASSIST"

        /** One recognizer for the process: creating it loads the bundled model. */
        private val client: TextRecognizer by lazy {
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
    }
}
