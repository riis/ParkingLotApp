package com.dji.droneparking.tracker

/**
 * A simple class that could calculate Keypoint delta.
 * This class will be used in calculating frame translation delta
 * for optical flow.
 */
class PointChange internal constructor(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    score: Float, type: Int,
    val wasFound: Boolean
) {
    val keypointA: Keypoint = Keypoint(x1, y1, score, type)
    val keypointB: Keypoint = Keypoint(x2, y2)

}