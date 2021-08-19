package com.dji.droneparking.tracker

import com.dji.droneparking.tracker.FrameChange
import java.util.*

/**
 * A class that records a timestamped frame translation delta for optical flow.
 */
class FrameChange(framePoints: FloatArray) {
    val pointDeltas: Vector<PointChange>
    val minScore: Float
    val maxScore: Float

    companion object {
        private const val DOWNSAMPLE_FACTOR = 2
        private const val KEYPOINT_STEP = 7
    }

    init {
        var minScore = 100.0f
        var maxScore = -100.0f
        pointDeltas = Vector<PointChange>(framePoints.size / KEYPOINT_STEP)
        var i = 0
        while (i < framePoints.size) {
            val x1 = framePoints[i] * DOWNSAMPLE_FACTOR
            val y1 = framePoints[i + 1] * DOWNSAMPLE_FACTOR
            val wasFound = framePoints[i + 2] > 0.0f
            val x2 = framePoints[i + 3] * DOWNSAMPLE_FACTOR
            val y2 = framePoints[i + 4] * DOWNSAMPLE_FACTOR
            val score = framePoints[i + 5]
            val type = framePoints[i + 6].toInt()
            minScore = Math.min(minScore, score)
            maxScore = Math.max(maxScore, score)
            pointDeltas.add(PointChange(x1, y1, x2, y2, score, type, wasFound))
            i += KEYPOINT_STEP
        }
        this.minScore = minScore
        this.maxScore = maxScore
    }
}