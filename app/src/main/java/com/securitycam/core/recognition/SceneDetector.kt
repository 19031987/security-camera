package com.securitycam.core.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Says what is in the part of a picture that moved. */
interface SceneDetector {
    /** [boxes] are the motion regions as fractions of the frame: left, top, right, bottom. */
    fun detect(bitmap: Bitmap, boxes: List<FloatArray>): SceneLabel
}

/**
 * Runs the bundled EfficientDet-Lite0 model on the device. Only the per-class scores are used
 * (is there a person / animal / vehicle?), so no box decoding is needed. Must be called off the
 * main thread. Any failure yields [SceneLabel.UNKNOWN], which alerts as plain "Motion".
 */
class TfliteSceneDetector(private val context: Context) : SceneDetector {

    private val interpreter: Interpreter by lazy {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
    }

    private val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    @Synchronized
    override fun detect(bitmap: Bitmap, boxes: List<FloatArray>): SceneLabel {
        return try {
            val region = SceneLabeler.regionOfInterest(boxes)
            val source = Rect(
                (region[0] * bitmap.width).toInt(),
                (region[1] * bitmap.height).toInt(),
                (region[2] * bitmap.width).toInt().coerceAtMost(bitmap.width),
                (region[3] * bitmap.height).toInt().coerceAtMost(bitmap.height)
            )
            if (source.width() <= 0 || source.height() <= 0) return SceneLabel.UNKNOWN

            val scaled = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            Canvas(scaled).drawBitmap(bitmap, source, Rect(0, 0, INPUT_SIZE, INPUT_SIZE), paint)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            scaled.recycle()

            // The model takes 320 x 320 RGB bytes.
            input.rewind()
            for (pixel in pixels) {
                input.put(((pixel shr 16) and 0xFF).toByte())
                input.put(((pixel shr 8) and 0xFF).toByte())
                input.put((pixel and 0xFF).toByte())
            }
            input.rewind()

            // Two outputs: class scores [1, anchors, classes] and boxes [1, anchors, 4].
            val outputs = HashMap<Int, Any>()
            var scores: Array<Array<FloatArray>>? = null
            for (i in 0 until interpreter.outputTensorCount) {
                val shape = interpreter.getOutputTensor(i).shape()
                val buffer = Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
                outputs[i] = buffer
                if (shape[2] == SceneLabeler.LABELS.size) scores = buffer
            }
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)

            val anchors = scores?.get(0) ?: return SceneLabel.UNKNOWN
            val best = FloatArray(SceneLabeler.LABELS.size)
            for (anchor in anchors) {
                for (c in best.indices) {
                    if (anchor[c] > best[c]) best[c] = anchor[c]
                }
            }
            SceneLabeler.summarize(best)
        } catch (e: Exception) {
            Log.w(TAG, "Scene detection failed: ${e.message}")
            SceneLabel.UNKNOWN
        }
    }

    companion object {
        private const val TAG = "TfliteSceneDetector"
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        const val INPUT_SIZE = 320
    }
}
