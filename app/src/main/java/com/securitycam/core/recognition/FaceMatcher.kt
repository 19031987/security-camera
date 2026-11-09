package com.securitycam.core.recognition

import kotlin.math.sqrt

/** A household member and the face samples enrolled for them. */
data class KnownPerson(
    val id: String,
    val name: String,
    val embeddings: List<FloatArray>
)

/** What one camera frame showed: which enrolled people, and how many faces nobody recognised. */
data class FaceVerdict(
    val knownNames: Set<String>,
    val unknownCount: Int
) {
    companion object {
        val NO_FACES = FaceVerdict(emptySet(), 0)
    }
}

/** Compares face embeddings. Pure maths, no Android dependencies. */
object FaceMatcher {

    /**
     * Minimum cosine similarity for two faces to count as the same person (0.5 = 60 degrees).
     * The model's own verification default is looser (70 degrees). This is deliberately
     * stricter because a wrong match here silences an alert, while a missed match only
     * sends one that wasn't needed.
     */
    const val DEFAULT_THRESHOLD = 0.5f

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0f || normB == 0f) return 0f
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /** The enrolled person whose closest sample is most similar, if it clears [threshold]. */
    fun bestMatch(
        embedding: FloatArray,
        people: List<KnownPerson>,
        threshold: Float = DEFAULT_THRESHOLD
    ): KnownPerson? {
        var best: KnownPerson? = null
        var bestScore = threshold
        for (person in people) {
            for (sample in person.embeddings) {
                val score = cosineSimilarity(embedding, sample)
                if (score >= bestScore) {
                    bestScore = score
                    best = person
                }
            }
        }
        return best
    }

    fun verdict(
        faceEmbeddings: List<FloatArray>,
        people: List<KnownPerson>,
        threshold: Float = DEFAULT_THRESHOLD
    ): FaceVerdict {
        val known = LinkedHashSet<String>()
        var unknown = 0
        for (embedding in faceEmbeddings) {
            val match = bestMatch(embedding, people, threshold)
            if (match != null) known.add(match.name) else unknown++
        }
        return FaceVerdict(known, unknown)
    }
}
