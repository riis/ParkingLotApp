package com.dji.droneparking.tracker

/**
 * A simple class that records keypoint information, which includes
 * local location, score and type. This will be used in calculating
 * FrameChange.
 */
class Keypoint {
    val x: Float
    val y: Float
    val score: Float
    val type: Int

    internal constructor(x: Float, y: Float) {
        this.x = x
        this.y = y
        score = 0f
        type = -1
    }

    internal constructor(x: Float, y: Float, score: Float, type: Int) {
        this.x = x
        this.y = y
        this.score = score
        this.type = type
    }
}