package com.securitycam.core.recognition

/**
 * Geometry for turning a detected face into the upright, centred 112 x 112 crop that the
 * embedding model was trained on. Pure maths, no Android dependencies.
 */
object FaceAlignment {

    const val CROP_SIZE = 112

    /**
     * Where the landmarks sit in the model's canonical crop, as x,y pairs:
     * image-left eye, image-right eye, image-left mouth corner, image-right mouth corner.
     */
    val TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * Least-squares similarity transform (rotation, uniform scale, translation, no mirroring)
     * mapping [source] points onto [target] points. Both are flat x,y arrays of equal length.
     *
     * Returns [a, b, tx, ty] where  x' = a*x - b*y + tx  and  y' = b*x + a*y + ty,
     * or null if the source points all coincide.
     */
    fun similarityTransform(source: FloatArray, target: FloatArray): FloatArray? {
        require(source.size == target.size && source.size >= 4 && source.size % 2 == 0) {
            "Need the same number of points (at least two) on both sides"
        }
        val n = source.size / 2
        var meanSx = 0.0
        var meanSy = 0.0
        var meanTx = 0.0
        var meanTy = 0.0
        for (i in 0 until n) {
            meanSx += source[2 * i]
            meanSy += source[2 * i + 1]
            meanTx += target[2 * i]
            meanTy += target[2 * i + 1]
        }
        meanSx /= n
        meanSy /= n
        meanTx /= n
        meanTy /= n

        var dot = 0.0
        var cross = 0.0
        var spread = 0.0
        for (i in 0 until n) {
            val sx = source[2 * i] - meanSx
            val sy = source[2 * i + 1] - meanSy
            val tx = target[2 * i] - meanTx
            val ty = target[2 * i + 1] - meanTy
            dot += sx * tx + sy * ty
            cross += sx * ty - sy * tx
            spread += sx * sx + sy * sy
        }
        if (spread < 1e-9) return null

        val a = dot / spread
        val b = cross / spread
        val translateX = meanTx - (a * meanSx - b * meanSy)
        val translateY = meanTy - (b * meanSx + a * meanSy)
        return floatArrayOf(a.toFloat(), b.toFloat(), translateX.toFloat(), translateY.toFloat())
    }

    /** Scales a vector to unit length so embeddings can be compared and stored consistently. */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v * v
        val norm = Math.sqrt(sum)
        if (norm < 1e-12) return vector.copyOf()
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }
}
