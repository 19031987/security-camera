package com.securitycam.recognition

import com.securitycam.core.recognition.SceneLabel
import com.securitycam.core.recognition.SceneLabeler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneLabelerTest {

    private fun scores(vararg pairs: Pair<String, Float>): FloatArray {
        val result = FloatArray(SceneLabeler.LABELS.size)
        for ((name, score) in pairs) {
            val index = SceneLabeler.LABELS.indexOf(name)
            assertTrue("unknown label $name", index >= 0)
            result[index] = score
        }
        return result
    }

    @Test
    fun theModelHasNinetyClassesStartingWithPerson() {
        assertEquals(90, SceneLabeler.LABELS.size)
        assertEquals("person", SceneLabeler.LABELS[0])
    }

    @Test
    fun aPersonIsReportedAsPerson() {
        val label = SceneLabeler.summarize(scores("person" to 0.9f))
        assertEquals(SceneLabel.Kind.PERSON, label.kind)
        assertEquals("Person", label.headline)
    }

    @Test
    fun anAnimalIsNamed() {
        val label = SceneLabeler.summarize(scores("dog" to 0.88f, "cat" to 0.79f, "bear" to 0.08f))
        assertEquals(SceneLabel.Kind.ANIMAL, label.kind)
        assertEquals("dog", label.detail)
        assertEquals("Animal (dog)", label.headline)
    }

    @Test
    fun aVehicleIsNamed() {
        val label = SceneLabeler.summarize(scores("car" to 0.7f, "truck" to 0.6f))
        assertEquals("Vehicle (car)", label.headline)
    }

    @Test
    fun aPersonOutranksAnAnimalAndAVehicle() {
        val label = SceneLabeler.summarize(scores("dog" to 0.95f, "car" to 0.9f, "person" to 0.55f))
        assertEquals("Person", label.headline)
    }

    @Test
    fun anAnimalOutranksAVehicle() {
        assertEquals("Animal (cat)", SceneLabeler.summarize(scores("car" to 0.9f, "cat" to 0.6f)).headline)
    }

    @Test
    fun weakScoresAndOtherObjectsAreJustMovement() {
        assertEquals("Movement", SceneLabeler.summarize(scores("person" to 0.49f, "dog" to 0.3f)).headline)
        assertEquals("Movement", SceneLabeler.summarize(scores("chair" to 0.95f, "tv" to 0.9f)).headline)
        assertEquals(SceneLabel.UNKNOWN, SceneLabeler.summarize(FloatArray(90)))
        assertNull(SceneLabeler.summarize(FloatArray(90)).detail)
    }

    @Test
    fun oddInputDoesNotCrash() {
        assertEquals(SceneLabel.UNKNOWN, SceneLabeler.summarize(FloatArray(0)))
        assertEquals(SceneLabel.UNKNOWN, SceneLabeler.summarize(FloatArray(500)))
        assertEquals(SceneLabel.UNKNOWN, SceneLabeler.summarize(FloatArray(90) { Float.NaN }))
    }

    @Test
    fun thresholdCanBeChanged() {
        assertEquals("Person", SceneLabeler.summarize(scores("person" to 0.35f), threshold = 0.3f).headline)
    }

    @Test
    fun noMotionBoxesMeansLookAtTheWholePicture() {
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 1f), SceneLabeler.regionOfInterest(emptyList()), 0f)
    }

    @Test
    fun regionCoversAllBoxesWithPadding() {
        val region = SceneLabeler.regionOfInterest(
            listOf(floatArrayOf(0.4f, 0.4f, 0.5f, 0.6f), floatArrayOf(0.5f, 0.3f, 0.7f, 0.5f))
        )
        // Union is 0.4..0.7 by 0.3..0.6; padded by a quarter each side -> 0.45 wide and high.
        assertEquals(0.325f, region[0], 1e-4f)
        assertEquals(0.225f, region[1], 1e-4f)
        assertEquals(0.775f, region[2], 1e-4f)
        assertEquals(0.675f, region[3], 1e-4f)
    }

    @Test
    fun aTinyBoxIsWidenedSoThereIsEnoughToRecognise() {
        val region = SceneLabeler.regionOfInterest(listOf(floatArrayOf(0.50f, 0.50f, 0.52f, 0.52f)))
        assertEquals(0.3f, region[2] - region[0], 1e-4f)
        assertEquals(0.3f, region[3] - region[1], 1e-4f)
    }

    @Test
    fun regionStaysInsideThePicture() {
        for (box in listOf(
            floatArrayOf(0f, 0f, 0.1f, 0.1f),
            floatArrayOf(0.9f, 0.9f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f)
        )) {
            val r = SceneLabeler.regionOfInterest(listOf(box))
            assertTrue(r[0] >= 0f && r[1] >= 0f && r[2] <= 1.0001f && r[3] <= 1.0001f)
            assertTrue(r[2] > r[0] && r[3] > r[1])
        }
    }

    @Test
    fun malformedBoxesFallBackToTheWholePicture() {
        assertArrayEquals(
            floatArrayOf(0f, 0f, 1f, 1f),
            SceneLabeler.regionOfInterest(listOf(floatArrayOf(0.5f, 0.5f))), 0f
        )
    }
}
