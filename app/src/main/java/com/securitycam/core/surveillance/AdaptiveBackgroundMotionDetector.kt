package com.securitycam.core.surveillance

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-performance, zero-allocation, thread-safe adaptive background motion detector.
 *
 * Implements:
 * 1. Exponential Moving Average (EMA) background accumulation on 160x120 Y-plane luminance frames:
 *    B_t(x, y) = (1 - alpha) * B_{t-1}(x, y) + alpha * I_t(x, y) [default alpha = 0.03]
 * 2. Selective updating with slow alpha (alpha_fg = 0.001) on detected foreground pixels to
 *    prevent ghosting and moving target absorption.
 * 3. Spatial 3x3 morphological opening (erosion followed by dilation) to eradicate isolated
 *    single-pixel camera sensor noise without OpenCV or native C++ dependencies.
 * 4. Grid block-based bounding box clustering: divides frame into 8x8 blocks, clusters active
 *    motion blocks via 8-connected BFS component analysis, and normalizes coordinates to [0.0, 1.0].
 * 5. Dynamic low-light noise floor thresholding and temporal hysteresis debouncing.
 * 6. Strict thread safety via monitor synchronization and instant state reset.
 */
class AdaptiveBackgroundMotionDetector(
    initialConfig: MotionConfig = MotionConfig()
) : MotionDetector {

    companion object {
        const val DEFAULT_ALPHA_BACKGROUND = 0.03f
        const val DEFAULT_ALPHA_FOREGROUND = 0.001f
        const val LOW_LIGHT_LUMINANCE_THRESHOLD = 40.0f
        const val LOW_LIGHT_NOISE_DIVISOR = 3.0f
        const val GRID_BLOCK_SIZE = 8
        const val BLOCK_ACTIVE_DENSITY_THRESHOLD = 0.20f
        const val MAX_BOUNDING_BOXES = 4
        const val DEBOUNCE_MAX_BURST = 0
    }

    private val lock = Any()

    @Volatile
    private var config: MotionConfig = initialConfig

    // Pre-allocated reusable buffers for zero allocation in the per-frame hot path
    private var backgroundModel: FloatArray? = null
    private var rawMask: ByteArray? = null
    private var erodedMask: ByteArray? = null
    private var dilatedMask: ByteArray? = null
    private var bufferWidth: Int = 0
    private var bufferHeight: Int = 0
    private var bufferTotalPixels: Int = 0

    // Reusable grid block clustering buffers
    private var gridCols: Int = 0
    private var gridRows: Int = 0
    private var blockActive: BooleanArray? = null
    private var blockVisited: BooleanArray? = null
    private var bfsQueue: IntArray? = null

    // Temporal debouncing state
    private var consecutiveTriggerCount: Int = 0
    private var lastAverageLuminance: Float = 128.0f

    override fun configure(config: MotionConfig) = synchronized(lock) {
        this.config = config
    }

    override fun reset() = synchronized(lock) {
        backgroundModel = null
        consecutiveTriggerCount = 0
    }

    /**
     * Ensures all internal working buffers are allocated and match current frame dimensions.
     * Buffers are re-used across frames to eliminate heap allocations per frame.
     */
    private fun ensureBuffers(width: Int, height: Int) {
        val totalPixels = width * height
        if (bufferWidth != width || bufferHeight != height || backgroundModel == null) {
            backgroundModel = FloatArray(totalPixels)
            rawMask = ByteArray(totalPixels)
            erodedMask = ByteArray(totalPixels)
            dilatedMask = ByteArray(totalPixels)

            gridCols = (width + GRID_BLOCK_SIZE - 1) / GRID_BLOCK_SIZE
            gridRows = (height + GRID_BLOCK_SIZE - 1) / GRID_BLOCK_SIZE
            val totalBlocks = gridCols * gridRows

            blockActive = BooleanArray(totalBlocks)
            blockVisited = BooleanArray(totalBlocks)
            bfsQueue = IntArray(totalBlocks)

            bufferWidth = width
            bufferHeight = height
            bufferTotalPixels = totalPixels
        }
    }

    override fun processFrame(frame: LuminanceFrame): MotionResult = synchronized(lock) {
        val width = frame.width
        val height = frame.height
        val totalPixels = width * height
        val data = frame.data

        // 1. Edge-case and corrupt input validation
        if (width <= 0 || height <= 0 || totalPixels <= 0 || data.size < totalPixels) {
            return MotionResult(
                isMotionDetected = false,
                motionScore = 0f,
                activePixels = 0,
                totalPixels = max(0, totalPixels),
                averageLuminance = 0f,
                isLowLight = false,
                boundingBoxes = emptyList()
            )
        }

        // 2. Average luminance & low-light assessment
        var sumLuminance = 0L
        for (i in 0 until totalPixels) {
            sumLuminance += (data[i].toInt() and 0xFF)
        }
        val avgLuminance = sumLuminance.toFloat() / totalPixels
        lastAverageLuminance = avgLuminance
        val isLowLight = avgLuminance < LOW_LIGHT_LUMINANCE_THRESHOLD

        // 3. Dynamic low-light noise compensation
        val currentConfig = config
        val effectiveThreshold = if (currentConfig.lowLightCompensation && isLowLight) {
            val noiseOffset = ((LOW_LIGHT_LUMINANCE_THRESHOLD - avgLuminance) / LOW_LIGHT_NOISE_DIVISOR).roundToInt()
            currentConfig.pixelThreshold + max(0, noiseOffset)
        } else {
            currentConfig.pixelThreshold
        }

        // 4. Buffer acquisition & first-frame background model initialization
        val isFirstFrame = (backgroundModel == null || bufferWidth != width || bufferHeight != height)
        ensureBuffers(width, height)
        val bg = backgroundModel!!
        val raw = rawMask!!
        val eroded = erodedMask!!
        val dilated = dilatedMask!!

        if (isFirstFrame) {
            for (i in 0 until totalPixels) {
                bg[i] = (data[i].toInt() and 0xFF).toFloat()
            }
            consecutiveTriggerCount = 0
            return MotionResult(
                isMotionDetected = false,
                motionScore = 0f,
                activePixels = 0,
                totalPixels = totalPixels,
                averageLuminance = avgLuminance,
                isLowLight = isLowLight,
                boundingBoxes = emptyList()
            )
        }

        // 5. Foreground differencing & selective EMA background accumulation
        var rawActiveCount = 0
        for (i in 0 until totalPixels) {
            val curY = data[i].toInt() and 0xFF
            val bgY = bg[i]
            val delta = abs(curY - bgY)
            if (delta > effectiveThreshold) {
                raw[i] = 1
                rawActiveCount++
                // Slow selective update on moving foreground to prevent ghosting
                bg[i] = bgY * (1f - DEFAULT_ALPHA_FOREGROUND) + curY * DEFAULT_ALPHA_FOREGROUND
            } else {
                raw[i] = 0
                // Standard EMA update on stationary background
                bg[i] = bgY * (1f - DEFAULT_ALPHA_BACKGROUND) + curY * DEFAULT_ALPHA_BACKGROUND
            }
        }

        // 6. Spatial 3x3 Morphological Opening (Erosion followed by Dilation)
        var activePixels = 0
        if (rawActiveCount > 0 && width >= 3 && height >= 3) {
            // --- EROSION: 3x3 min / AND structuring element ---
            // Clear outer borders
            for (x in 0 until width) {
                eroded[x] = 0
                eroded[(height - 1) * width + x] = 0
            }
            for (y in 0 until height) {
                eroded[y * width] = 0
                eroded[y * width + (width - 1)] = 0
            }

            var erodedActiveCount = 0
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val idx = row + x
                    if (raw[idx] == 0.toByte()) {
                        eroded[idx] = 0
                        continue
                    }
                    var allSurrounded = true
                    neighborLoop@ for (dy in -1..1) {
                        val nRow = (y + dy) * width
                        for (dx in -1..1) {
                            if (raw[nRow + x + dx] == 0.toByte()) {
                                allSurrounded = false
                                break@neighborLoop
                            }
                        }
                    }
                    if (allSurrounded) {
                        eroded[idx] = 1
                        erodedActiveCount++
                    } else {
                        eroded[idx] = 0
                    }
                }
            }

            // --- DILATION: 3x3 max / OR structuring element ---
            dilated.fill(0)
            if (erodedActiveCount > 0) {
                for (y in 1 until height - 1) {
                    val row = y * width
                    for (x in 1 until width - 1) {
                        if (eroded[row + x] == 1.toByte()) {
                            // Stamp 3x3 footprint into dilated mask
                            for (dy in -1..1) {
                                val dRow = (y + dy) * width
                                for (dx in -1..1) {
                                    val dIdx = dRow + x + dx
                                    if (dilated[dIdx] == 0.toByte()) {
                                        dilated[dIdx] = 1
                                        activePixels++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            eroded.fill(0)
            dilated.fill(0)
            activePixels = 0
        }

        // 7. Motion score & area ratio check
        val motionScore = activePixels.toFloat() / totalPixels
        val isRawMotionDetected = motionScore >= currentConfig.minAreaRatio

        // 8. Temporal debouncing (hysteresis)
        if (isRawMotionDetected) {
            consecutiveTriggerCount = min(
                consecutiveTriggerCount + 1,
                currentConfig.consecutiveTriggerFrames + DEBOUNCE_MAX_BURST
            )
        } else {
            consecutiveTriggerCount = max(0, consecutiveTriggerCount - 1)
        }
        val isMotionTriggered = consecutiveTriggerCount >= currentConfig.consecutiveTriggerFrames

        // 9. Grid block clustering and bounding box extraction
        val boundingBoxes = if (isMotionTriggered && activePixels > 0) {
            extractBoundingBoxes(width, height, dilated)
        } else {
            emptyList()
        }

        return MotionResult(
            isMotionDetected = isMotionTriggered,
            motionScore = motionScore,
            activePixels = activePixels,
            totalPixels = totalPixels,
            averageLuminance = avgLuminance,
            isLowLight = isLowLight,
            boundingBoxes = boundingBoxes
        )
    }

    /**
     * Clusters active motion regions into bounding boxes normalized to [0.0, 1.0].
     * Divides frame into 8x8 blocks, marks blocks active based on pixel density,
     * and performs 8-connectivity BFS to extract connected components.
     */
    private fun extractBoundingBoxes(
        width: Int,
        height: Int,
        dilated: ByteArray
    ): List<NormalizedRect> {
        val cols = gridCols
        val rows = gridRows
        val totalBlocks = cols * rows
        val activeBlocks = blockActive ?: return emptyList()
        val visited = blockVisited ?: return emptyList()
        val queue = bfsQueue ?: return emptyList()

        activeBlocks.fill(false)
        visited.fill(false)

        var activeBlockCount = 0
        for (by in 0 until rows) {
            val y0 = by * GRID_BLOCK_SIZE
            val y1 = min(height, y0 + GRID_BLOCK_SIZE)
            val blockH = y1 - y0

            for (bx in 0 until cols) {
                val x0 = bx * GRID_BLOCK_SIZE
                val x1 = min(width, x0 + GRID_BLOCK_SIZE)
                val blockW = x1 - x0
                val blockArea = blockW * blockH

                var countInBlock = 0
                for (y in y0 until y1) {
                    val r = y * width
                    for (x in x0 until x1) {
                        if (dilated[r + x] == 1.toByte()) {
                            countInBlock++
                        }
                    }
                }

                val density = countInBlock.toFloat() / blockArea
                val blockIdx = by * cols + bx
                if (density >= BLOCK_ACTIVE_DENSITY_THRESHOLD) {
                    activeBlocks[blockIdx] = true
                    activeBlockCount++
                }
            }
        }

        // Fallback: If no single 8x8 block reached 20% density, but overall motion triggered
        if (activeBlockCount == 0) {
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            for (y in 0 until height) {
                val r = y * width
                for (x in 0 until width) {
                    if (dilated[r + x] == 1.toByte()) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (minX <= maxX && minY <= maxY) {
                val left = (minX.toFloat() / width).coerceIn(0f, 1f)
                val top = (minY.toFloat() / height).coerceIn(0f, 1f)
                val right = ((maxX + 1).toFloat() / width).coerceIn(left, 1f)
                val bottom = ((maxY + 1).toFloat() / height).coerceIn(top, 1f)
                return listOf(NormalizedRect(left, top, right, bottom))
            }
            return emptyList()
        }

        // BFS 8-connectivity clustering across active blocks
        val clusterRects = mutableListOf<NormalizedRect>()
        for (startIdx in 0 until totalBlocks) {
            if (!activeBlocks[startIdx] || visited[startIdx]) continue

            var qHead = 0
            var qTail = 0
            queue[qTail++] = startIdx
            visited[startIdx] = true

            var minBx = startIdx % cols
            var maxBx = minBx
            var minBy = startIdx / cols
            var maxBy = minBy

            while (qHead < qTail) {
                val currIdx = queue[qHead++]
                val cbx = currIdx % cols
                val cby = currIdx / cols

                if (cbx < minBx) minBx = cbx
                if (cbx > maxBx) maxBx = cbx
                if (cby < minBy) minBy = cby
                if (cby > maxBy) maxBy = cby

                for (dy in -1..1) {
                    val nby = cby + dy
                    if (nby !in 0 until rows) continue
                    for (dx in -1..1) {
                        val nbx = cbx + dx
                        if (nbx !in 0 until cols) continue
                        val nIdx = nby * cols + nbx
                        if (activeBlocks[nIdx] && !visited[nIdx]) {
                            visited[nIdx] = true
                            queue[qTail++] = nIdx
                        }
                    }
                }
            }

            val pxLeft = minBx * GRID_BLOCK_SIZE
            val pxTop = minBy * GRID_BLOCK_SIZE
            val pxRight = min(width, (maxBx + 1) * GRID_BLOCK_SIZE)
            val pxBottom = min(height, (maxBy + 1) * GRID_BLOCK_SIZE)

            val normLeft = (pxLeft.toFloat() / width).coerceIn(0f, 1f)
            val normTop = (pxTop.toFloat() / height).coerceIn(0f, 1f)
            val normRight = (pxRight.toFloat() / width).coerceIn(normLeft, 1f)
            val normBottom = (pxBottom.toFloat() / height).coerceIn(normTop, 1f)

            clusterRects.add(NormalizedRect(normLeft, normTop, normRight, normBottom))
        }

        return clusterRects.sortedByDescending { it.area }.take(MAX_BOUNDING_BOXES)
    }
}
