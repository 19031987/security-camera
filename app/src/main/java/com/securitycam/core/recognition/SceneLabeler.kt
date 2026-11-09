package com.securitycam.core.recognition

/**
 * What an on-device object detector saw in the part of the picture that moved.
 * Pure logic: turns the detector's per-class scores into a short description for alerts.
 */
data class SceneLabel(val kind: Kind, val detail: String?) {

    enum class Kind { PERSON, ANIMAL, VEHICLE, OTHER }

    /** For message text: "Person", "Animal (dog)", "Vehicle (car)" or "Movement". */
    val headline: String
        get() = when (kind) {
            Kind.PERSON -> "Person"
            Kind.ANIMAL -> if (detail != null) "Animal ($detail)" else "Animal"
            Kind.VEHICLE -> if (detail != null) "Vehicle ($detail)" else "Vehicle"
            Kind.OTHER -> "Movement"
        }

    companion object {
        val UNKNOWN = SceneLabel(Kind.OTHER, null)
    }
}

object SceneLabeler {

    /** A class must score at least this (0..1) to be reported. */
    const val DEFAULT_THRESHOLD = 0.5f

    /** The detector's class names, in output order ("???" marks unused slots). */
    val LABELS: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus",
        "train", "truck", "boat", "traffic light", "fire hydrant", "???",
        "stop sign", "parking meter", "bench", "bird", "cat", "dog",
        "horse", "sheep", "cow", "elephant", "bear", "zebra",
        "giraffe", "???", "backpack", "umbrella", "???", "???",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
        "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "???", "wine glass", "cup", "fork",
        "knife", "spoon", "bowl", "banana", "apple", "sandwich",
        "orange", "broccoli", "carrot", "hot dog", "pizza", "donut",
        "cake", "chair", "couch", "potted plant", "bed", "???",
        "dining table", "???", "???", "toilet", "???", "tv",
        "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave",
        "oven", "toaster", "sink", "refrigerator", "???", "book",
        "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )

    private val ANIMALS = setOf(
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe"
    )
    private val VEHICLES = setOf("bicycle", "car", "motorcycle", "bus", "train", "truck")

    /**
     * [classScores] holds the best score for each class anywhere in the picture.
     * A person outranks an animal, which outranks a vehicle: when a person and a dog are both
     * there, the alert should say "Person". Anything else is plain "Movement".
     */
    fun summarize(classScores: FloatArray, threshold: Float = DEFAULT_THRESHOLD): SceneLabel {
        var bestAnimal: String? = null
        var bestAnimalScore = threshold
        var bestVehicle: String? = null
        var bestVehicleScore = threshold
        var person = false

        for (i in 0 until minOf(classScores.size, LABELS.size)) {
            val score = classScores[i]
            if (score.isNaN() || score < threshold) continue
            val name = LABELS[i]
            when {
                name == "person" -> person = true
                name in ANIMALS && score >= bestAnimalScore -> {
                    bestAnimal = name
                    bestAnimalScore = score
                }
                name in VEHICLES && score >= bestVehicleScore -> {
                    bestVehicle = name
                    bestVehicleScore = score
                }
            }
        }
        return when {
            person -> SceneLabel(SceneLabel.Kind.PERSON, null)
            bestAnimal != null -> SceneLabel(SceneLabel.Kind.ANIMAL, bestAnimal)
            bestVehicle != null -> SceneLabel(SceneLabel.Kind.VEHICLE, bestVehicle)
            else -> SceneLabel.UNKNOWN
        }
    }

    /**
     * The region to examine: the union of the motion boxes, padded, as fractions of the frame
     * [left, top, right, bottom]. Looking only where something moved avoids naming a parked car
     * or a picture on the wall. Falls back to the whole frame when there are no boxes or the
     * region would be too small to recognise anything in.
     */
    fun regionOfInterest(boxes: List<FloatArray>, padding: Float = 0.25f, minSide: Float = 0.3f): FloatArray {
        val whole = floatArrayOf(0f, 0f, 1f, 1f)
        if (boxes.isEmpty()) return whole
        var left = 1f
        var top = 1f
        var right = 0f
        var bottom = 0f
        for (b in boxes) {
            if (b.size < 4) continue
            left = minOf(left, b[0])
            top = minOf(top, b[1])
            right = maxOf(right, b[2])
            bottom = maxOf(bottom, b[3])
        }
        if (right <= left || bottom <= top) return whole

        var width = (right - left) * (1 + 2 * padding)
        var height = (bottom - top) * (1 + 2 * padding)
        width = width.coerceIn(minSide, 1f)
        height = height.coerceIn(minSide, 1f)
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        val l = (centerX - width / 2).coerceIn(0f, 1f - width)
        val t = (centerY - height / 2).coerceIn(0f, 1f - height)
        return floatArrayOf(l, t, l + width, t + height)
    }
}
