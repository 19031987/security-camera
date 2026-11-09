package com.securitycam.core.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * On-device face analysis: ML Kit finds faces and their landmarks, each face is warped to the
 * model's canonical crop, and MobileFaceNet turns it into a 128-number embedding.
 * Nothing leaves the device. Must be called off the main thread.
 */
class MlKitFaceAnalyzer(private val context: Context) : FaceAnalyzer {

    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build()
        )
    }

    private val interpreter: Interpreter by lazy {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        Interpreter(model, Interpreter.Options().apply { setNumThreads(2) })
    }

    private val inputA = newInputBuffer()
    private val inputB = newInputBuffer()
    private val pixels = IntArray(FaceAlignment.CROP_SIZE * FaceAlignment.CROP_SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    override fun analyze(bitmap: Bitmap): List<FloatArray>? {
        return try {
            val faces = Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)),
                DETECTION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            faces.mapNotNull { face -> embed(bitmap, face) }
        } catch (e: Exception) {
            Log.w(TAG, "Face analysis failed: ${e.message}")
            null
        }
    }

    /** Returns null for faces that are too small, turned too far, or missing landmarks. */
    private fun embed(bitmap: Bitmap, face: Face): FloatArray? {
        if (face.boundingBox.width() < MIN_FACE_SIZE_PX || face.boundingBox.height() < MIN_FACE_SIZE_PX) {
            return null
        }
        if (abs(face.headEulerAngleY) > MAX_HEAD_TURN_DEGREES) return null

        val eyeA = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val eyeB = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val mouthA = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthB = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null

        // Order by position in the image rather than trusting "left"/"right" naming.
        val (leftEye, rightEye) = orderByX(eyeA, eyeB)
        val (leftMouth, rightMouth) = orderByX(mouthA, mouthB)
        val source = floatArrayOf(
            leftEye.x, leftEye.y,
            rightEye.x, rightEye.y,
            leftMouth.x, leftMouth.y,
            rightMouth.x, rightMouth.y
        )
        val t = FaceAlignment.similarityTransform(source, FaceAlignment.TEMPLATE) ?: return null

        val size = FaceAlignment.CROP_SIZE
        val crop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            setValues(floatArrayOf(t[0], -t[1], t[2], t[1], t[0], t[3], 0f, 0f, 1f))
        }
        Canvas(crop).drawBitmap(bitmap, matrix, paint)
        crop.getPixels(pixels, 0, size, 0, 0, size, size)
        crop.recycle()

        // The model takes two images per call, as planar RGB in the range 0..1.
        fillInput(inputA)
        fillInput(inputB)
        val output = Array(2) { FloatArray(EMBEDDING_SIZE) }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputA, inputB), mapOf(0 to output))
        return FaceAlignment.l2Normalize(output[0])
    }

    private fun fillInput(buffer: ByteBuffer) {
        buffer.rewind()
        for (shift in intArrayOf(16, 8, 0)) {
            for (pixel in pixels) {
                buffer.putFloat(((pixel shr shift) and 0xFF) / 255f)
            }
        }
        buffer.rewind()
    }

    private fun orderByX(a: PointF, b: PointF): Pair<PointF, PointF> = if (a.x <= b.x) a to b else b to a

    private fun newInputBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(3 * FaceAlignment.CROP_SIZE * FaceAlignment.CROP_SIZE * 4)
            .order(ByteOrder.nativeOrder())

    companion object {
        private const val TAG = "MlKitFaceAnalyzer"
        const val MODEL_ASSET = "mobile_facenet.tflite"
        const val EMBEDDING_SIZE = 128

        /** Faces smaller than this in the frame carry too little detail to compare reliably. */
        const val MIN_FACE_SIZE_PX = 40
        const val MAX_HEAD_TURN_DEGREES = 40f
        private const val DETECTION_TIMEOUT_SECONDS = 5L
    }
}
