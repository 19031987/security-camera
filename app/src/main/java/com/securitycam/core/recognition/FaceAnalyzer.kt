package com.securitycam.core.recognition

import android.graphics.Bitmap

/** Finds the faces in an image and turns each usable one into an embedding. */
interface FaceAnalyzer {
    /**
     * Returns one embedding per face that is large and clear enough to compare.
     * An empty list means no usable face; null means the analysis itself failed.
     */
    fun analyze(bitmap: Bitmap): List<FloatArray>?
}
