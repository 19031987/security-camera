package com.securitycam.recognition

import com.securitycam.core.recognition.FaceAlignment
import com.securitycam.core.recognition.FaceMatcher
import com.securitycam.core.recognition.KnownPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMatcherTest {

    private val asha = KnownPerson("1", "Asha", listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0.9f, 0.1f, 0f)))
    private val ravi = KnownPerson("2", "Ravi", listOf(floatArrayOf(0f, 1f, 0f)))
    private val people = listOf(asha, ravi)

    @Test
    fun cosineSimilarityOfKnownVectors() {
        assertEquals(1f, FaceMatcher.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-5f)
        assertEquals(0f, FaceMatcher.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
        assertEquals(-1f, FaceMatcher.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(-3f, 0f)), 1e-5f)
    }

    @Test
    fun mismatchedOrEmptyVectorsNeverMatch() {
        assertEquals(0f, FaceMatcher.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 0f)), 0f)
        assertEquals(0f, FaceMatcher.cosineSimilarity(floatArrayOf(), floatArrayOf()), 0f)
        assertEquals(0f, FaceMatcher.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0f)
    }

    @Test
    fun bestMatchPicksTheMostSimilarPerson() {
        assertEquals("Asha", FaceMatcher.bestMatch(floatArrayOf(0.95f, 0.05f, 0f), people)?.name)
        assertEquals("Ravi", FaceMatcher.bestMatch(floatArrayOf(0.1f, 0.9f, 0f), people)?.name)
    }

    @Test
    fun faceBelowThresholdMatchesNobody() {
        // Equally unlike everyone: cosine 0 to both.
        assertNull(FaceMatcher.bestMatch(floatArrayOf(0f, 0f, 1f), people))
        // Similarity about 0.45 to Asha, under the 0.5 default.
        assertNull(FaceMatcher.bestMatch(floatArrayOf(0.5f, 0f, 1f), people))
        // The same face passes a looser threshold.
        assertNotNull(FaceMatcher.bestMatch(floatArrayOf(0.5f, 0f, 1f), people, threshold = 0.4f))
    }

    @Test
    fun nobodyEnrolledMeansNoMatch() {
        assertNull(FaceMatcher.bestMatch(floatArrayOf(1f, 0f, 0f), emptyList()))
    }

    @Test
    fun verdictSeparatesKnownFromUnknownFaces() {
        val verdict = FaceMatcher.verdict(
            listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0.9f, 0.1f, 0f)),
            people
        )
        assertEquals(setOf("Asha"), verdict.knownNames)
        assertEquals(1, verdict.unknownCount)
    }

    @Test
    fun verdictWithNoFacesIsEmpty() {
        val verdict = FaceMatcher.verdict(emptyList(), people)
        assertTrue(verdict.knownNames.isEmpty())
        assertEquals(0, verdict.unknownCount)
    }

    @Test
    fun similarityTransformRecoversRotationScaleAndShift() {
        // Target = source rotated 90 degrees, doubled, then moved by (10, 5).
        val source = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val target = floatArrayOf(10f, 5f, 10f, 7f, 8f, 5f, 8f, 7f)
        val t = FaceAlignment.similarityTransform(source, target)!!
        assertEquals(0f, t[0], 1e-4f)
        assertEquals(2f, t[1], 1e-4f)
        assertEquals(10f, t[2], 1e-4f)
        assertEquals(5f, t[3], 1e-4f)
    }

    @Test
    fun templatePointsMapOntoThemselves() {
        val t = FaceAlignment.similarityTransform(FaceAlignment.TEMPLATE, FaceAlignment.TEMPLATE)!!
        assertEquals(1f, t[0], 1e-4f)
        assertEquals(0f, t[1], 1e-4f)
        assertEquals(0f, t[2], 1e-3f)
        assertEquals(0f, t[3], 1e-3f)
    }

    @Test
    fun aFaceTwiceAsBigIsScaledDownOntoTheTemplate() {
        val source = FloatArray(FaceAlignment.TEMPLATE.size) { FaceAlignment.TEMPLATE[it] * 2f + 100f }
        val t = FaceAlignment.similarityTransform(source, FaceAlignment.TEMPLATE)!!
        for (i in 0 until source.size / 2) {
            val x = source[2 * i]
            val y = source[2 * i + 1]
            assertEquals(FaceAlignment.TEMPLATE[2 * i], t[0] * x - t[1] * y + t[2], 1e-2f)
            assertEquals(FaceAlignment.TEMPLATE[2 * i + 1], t[1] * x + t[0] * y + t[3], 1e-2f)
        }
    }

    @Test
    fun coincidentPointsGiveNoTransform() {
        assertNull(FaceAlignment.similarityTransform(floatArrayOf(3f, 3f, 3f, 3f), floatArrayOf(0f, 0f, 1f, 1f)))
    }

    @Test
    fun l2NormalizeGivesUnitLength() {
        val v = FaceAlignment.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, v[0], 1e-5f)
        assertEquals(0.8f, v[1], 1e-5f)
        assertEquals(0f, FaceAlignment.l2Normalize(floatArrayOf(0f, 0f))[0], 0f)
    }
}
