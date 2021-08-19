package com.dji.droneparking.tracker

import android.graphics.*
import android.util.Size
import com.dji.droneparking.model.Util
import com.dji.droneparking.model.Utils
import com.dji.droneparking.tracker.FrameChange
import java.lang.RuntimeException
import java.util.*
import javax.microedition.khronos.opengles.GL10

class ObjectTracker private constructor(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val rowStride: Int,
    private val alwaysTrack: Boolean
) {
    companion object {
        private var libraryFound = false
        private const val DRAW_TEXT = false

        /**
         * How many history points to keep track of and draw in the red history line.
         */
        private const val MAX_DEBUG_HISTORY_SIZE = 30

        /**
         * How many frames of optical flow deltas to record.
         * TODO(andrewharp): Push this down to the native level so it can be polled
         * efficiently into a an array for upload, instead of keeping a duplicate
         * copy in Java.
         */
        private const val MAX_FRAME_HISTORY_SIZE = 200
        private const val DOWNSAMPLE_FACTOR = 2
        private var instance: ObjectTracker? = null
        @Synchronized
        fun getInstance(
            frameWidth: Int, frameHeight: Int, rowStride: Int, alwaysTrack: Boolean
        ): ObjectTracker? {
            if (!libraryFound) {
                return null
            }
            if (instance == null) {
                instance = ObjectTracker(frameWidth, frameHeight, rowStride, alwaysTrack)
                instance!!.init()
            } else {
                throw RuntimeException(
                    "Tried to create a new objectracker before releasing the old one!"
                )
            }
            return instance
        }

        @Synchronized
        fun clearInstance() {
            if (instance != null) {
                instance!!.release()
            }
        }

        private fun floatToChar(value: Float): Int {
            return Math.max(0, Math.min((value * 255.999f).toInt(), 255))
        }

        protected external fun downsampleImageNative(
            width: Int,
            height: Int,
            rowStride: Int,
            input: ByteArray?,
            factor: Int,
            output: ByteArray?
        )

        init {
            try {
                System.loadLibrary("tensorflow_demo")
                libraryFound = true
            } catch (e: UnsatisfiedLinkError) {
            }
        }
    }

    private val downsampledFrame: ByteArray
    private val trackedObjects: MutableMap<String, TrackedObject>
    private var lastTimestamp: Long = 0
    private var lastKeypoints: FrameChange? = null
    private val debugHistory: Vector<PointF>
    private val timestampedDeltas: LinkedList<TimestampedDeltas> = LinkedList<TimestampedDeltas>()

    inner class TrackedObject internal constructor(
        position: RectF,
        timestamp: Long,
        data: ByteArray
    ) {
        private val id: String
        private var lastExternalPositionTime: Long
        private var lastTrackedPosition: RectF? = null
        private var isDead = false
        fun stopTracking() {
            checkValidObject()
            synchronized(this@ObjectTracker) {
                isDead = true
                forgetNative(id)
                trackedObjects.remove(id)
            }
        }

        val currentCorrelation: Float
            get() {
                checkValidObject()
                return getCurrentCorrelation(id)
            }

        private fun registerInitialAppearance(position: RectF, data: ByteArray) {
            val externalPosition: RectF = Util.downscaleRect(position)
            registerNewObjectWithAppearanceNative(
                id,
                externalPosition.left, externalPosition.top,
                externalPosition.right, externalPosition.bottom,
                data
            )
        }

        @Synchronized
        private fun setPreviousPosition(position: RectF, timestamp: Long) {
            checkValidObject()
            synchronized(this@ObjectTracker) {
                if (lastExternalPositionTime > timestamp) {
                    return
                }
                val externalPosition: RectF = Util.downscaleRect(position)
                lastExternalPositionTime = timestamp
                setPreviousPositionNative(
                    id,
                    externalPosition.left, externalPosition.top,
                    externalPosition.right, externalPosition.bottom,
                    lastExternalPositionTime
                )
                updateTrackedPosition()
            }
        }

        @Synchronized
        fun updateTrackedPosition() {
            checkValidObject()
            val delta = FloatArray(4)
            getTrackedPositionNative(id, delta)
            lastTrackedPosition = RectF(delta[0], delta[1], delta[2], delta[3])
        }

        @get:Synchronized
        val trackedPositionInPreviewFrame: RectF?
            get() {
                checkValidObject()
                return if (lastTrackedPosition == null) {
                    null
                } else Util.upscaleRect(lastTrackedPosition!!)
            }

        private fun checkValidObject() {
            if (isDead) {
                throw RuntimeException("TrackedObject already removed from tracking!")
            } else if (this@ObjectTracker !== instance) {
                throw RuntimeException("TrackedObject created with another ObjectTracker!")
            }
        }

        init {
            id = Integer.toString(this.hashCode())
            lastExternalPositionTime = timestamp
            synchronized(this@ObjectTracker) {
                registerInitialAppearance(position, data)
                setPreviousPosition(position, timestamp)
                trackedObjects.put(id, this)
            }
        }
    }

    private fun init() {
        // The native tracker never sees the full frame, so pre-scale dimensions
        // by the downsample factor.
        initNative(frameWidth / DOWNSAMPLE_FACTOR, frameHeight / DOWNSAMPLE_FACTOR, alwaysTrack)
    }

    private val matrixValues = FloatArray(9)
    private var downsampledTimestamp: Long = 0
    @Synchronized
    fun drawOverlay(gl: GL10?, cameraViewSize: Size, matrix: Matrix?) {
        val tempMatrix = Matrix(matrix)
        tempMatrix.preScale(DOWNSAMPLE_FACTOR.toFloat(), DOWNSAMPLE_FACTOR.toFloat())
        tempMatrix.getValues(matrixValues)
        drawNative(cameraViewSize.width, cameraViewSize.height, matrixValues)
    }

    @Synchronized
    fun nextFrame(
        frameData: ByteArray?, uvData: ByteArray?,
        timestamp: Long, transformationMatrix: FloatArray?,
        updateDebugInfo: Boolean
    ) {
        if (downsampledTimestamp != timestamp) {
            downsampleImageNative(
                frameWidth, frameHeight, rowStride, frameData, DOWNSAMPLE_FACTOR, downsampledFrame
            )
            downsampledTimestamp = timestamp
        }

        // Do Lucas Kanade using the fullframe initializer.
        nextFrameNative(downsampledFrame, uvData, timestamp, transformationMatrix)
        getKeypointsPacked(DOWNSAMPLE_FACTOR.toFloat())?.let {
            TimestampedDeltas(
                timestamp,
                it
            )
        }?.let {
            timestampedDeltas.add(
                it
            )
        }
        while (timestampedDeltas.size > MAX_FRAME_HISTORY_SIZE) {
            timestampedDeltas.removeFirst()
        }
        for (trackedObject in trackedObjects.values) {
            trackedObject.updateTrackedPosition()
        }
        if (updateDebugInfo) {
            updateDebugHistory()
        }
        lastTimestamp = timestamp
    }

    @Synchronized
    private fun release() {
        releaseMemoryNative()
        synchronized(ObjectTracker::class.java) { instance = null }
    }

    private fun drawHistoryDebug(canvas: Canvas) {
        drawHistoryPoint(
            canvas,
            (frameWidth * DOWNSAMPLE_FACTOR / 2).toFloat(),
            (frameHeight * DOWNSAMPLE_FACTOR / 2).toFloat()
        )
    }

    private fun drawHistoryPoint(canvas: Canvas, startX: Float, startY: Float) {
        val p = Paint()
        p.isAntiAlias = false
        p.typeface = Typeface.SERIF
        p.color = Color.RED
        p.strokeWidth = 2.0f

        // Draw the center circle.
        p.color = Color.GREEN
        canvas.drawCircle(startX, startY, 3.0f, p)
        p.color = Color.RED

        // Iterate through in backwards order.
        synchronized(debugHistory) {
            val numPoints = debugHistory.size
            var lastX = startX
            var lastY = startY
            for (keypointNum in 0 until numPoints) {
                val delta: PointF = debugHistory[numPoints - keypointNum - 1]
                val newX: Float = lastX + delta.x
                val newY: Float = lastY + delta.y
                canvas.drawLine(lastX, lastY, newX, newY, p)
                lastX = newX
                lastY = newY
            }
        }
    }

    private fun drawKeypointsDebug(canvas: Canvas) {
        val p = Paint()
        if (lastKeypoints == null) {
            return
        }
        val keypointSize = 3
        val minScore: Float = lastKeypoints!!.minScore
        val maxScore: Float = lastKeypoints!!.maxScore
        for (keypoint in lastKeypoints!!.pointDeltas) {
            if (keypoint.wasFound) {
                val r = floatToChar((keypoint.keypointA.score - minScore) / (maxScore - minScore))
                val b =
                    floatToChar(1.0f - (keypoint.keypointA.score - minScore) / (maxScore - minScore))
                val color = -0x1000000 or (r shl 16) or b
                p.color = color
                val screenPoints = floatArrayOf(
                    keypoint.keypointA.x, keypoint.keypointA.y,
                    keypoint.keypointB.x, keypoint.keypointB.y
                )
                canvas.drawRect(
                    screenPoints[2] - keypointSize,
                    screenPoints[3] - keypointSize,
                    screenPoints[2] + keypointSize,
                    screenPoints[3] + keypointSize, p
                )
                p.color = Color.CYAN
                canvas.drawLine(
                    screenPoints[2], screenPoints[3],
                    screenPoints[0], screenPoints[1], p
                )
                if (DRAW_TEXT) {
                    p.color = Color.WHITE
                    canvas.drawText(
                        keypoint.keypointA.type.toString() + ": " + keypoint.keypointA.score,
                        keypoint.keypointA.x, keypoint.keypointA.y, p
                    )
                }
            } else {
                p.color = Color.YELLOW
                val screenPoint = floatArrayOf(keypoint.keypointA.x, keypoint.keypointA.y)
                canvas.drawCircle(screenPoint[0], screenPoint[1], 5.0f, p)
            }
        }
    }

    @Synchronized
    private fun getAccumulatedDelta(
        timestamp: Long, positionX: Float,
        positionY: Float, radius: Float
    ): PointF {
        val currPosition: RectF = getCurrentPosition(
            timestamp,
            RectF(positionX - radius, positionY - radius, positionX + radius, positionY + radius)
        )
        return PointF(currPosition.centerX() - positionX, currPosition.centerY() - positionY)
    }

    @Synchronized
    private fun getCurrentPosition(timestamp: Long, oldPosition: RectF): RectF {
        val downscaledFrameRect: RectF = Util.downscaleRect(oldPosition)
        val delta = FloatArray(4)
        getCurrentPositionNative(
            timestamp, downscaledFrameRect.left, downscaledFrameRect.top,
            downscaledFrameRect.right, downscaledFrameRect.bottom, delta
        )
        val newPosition = RectF(delta[0], delta[1], delta[2], delta[3])
        return Util.upscaleRect(newPosition)
    }

    private fun updateDebugHistory() {
        lastKeypoints = getKeypointsNative(false)?.let { FrameChange(it) }
        if (lastTimestamp == 0L) {
            return
        }
        val delta: PointF = getAccumulatedDelta(
            lastTimestamp,
            (frameWidth / DOWNSAMPLE_FACTOR).toFloat(),
            (frameHeight / DOWNSAMPLE_FACTOR).toFloat(),
            100f
        )
        synchronized(debugHistory) {
            debugHistory.add(delta)
            while (debugHistory.size > MAX_DEBUG_HISTORY_SIZE) {
                debugHistory.removeAt(0)
            }
        }
    }

    @Synchronized
    fun drawDebug(canvas: Canvas, frameToCanvas: Matrix?) {
        canvas.save()
        canvas.setMatrix(frameToCanvas)
        drawHistoryDebug(canvas)
        drawKeypointsDebug(canvas)
        canvas.restore()
    }

    @Synchronized
    fun trackObject(
        position: RectF, timestamp: Long, frameData: ByteArray?
    ): TrackedObject {
        if (downsampledTimestamp != timestamp) {
            downsampleImageNative(
                frameWidth, frameHeight, rowStride, frameData, DOWNSAMPLE_FACTOR, downsampledFrame
            )
            downsampledTimestamp = timestamp
        }
        return TrackedObject(position, timestamp, downsampledFrame)
    }

    @Synchronized
    fun trackObject(position: RectF, frameData: ByteArray): TrackedObject {
        return TrackedObject(position, lastTimestamp, frameData)
    }
    /* ********************* NATIVE CODE ************************************ */
    /**
     * This will contain an opaque pointer to the native ObjectTracker
     */
    private val nativeObjectTracker: Long = 0
    private external fun initNative(imageWidth: Int, imageHeight: Int, alwaysTrack: Boolean)
    protected external fun registerNewObjectWithAppearanceNative(
        objectId: String?, x1: Float, y1: Float, x2: Float, y2: Float, data: ByteArray?
    )

    protected external fun setPreviousPositionNative(
        objectId: String?, x1: Float, y1: Float, x2: Float, y2: Float, timestamp: Long
    )

    protected external fun setCurrentPositionNative(
        objectId: String?, x1: Float, y1: Float, x2: Float, y2: Float
    )

    protected external fun forgetNative(key: String?)
    protected external fun getModelIdNative(key: String?): String?
    protected external fun haveObject(key: String?): Boolean
    protected external fun isObjectVisible(key: String?): Boolean
    protected external fun getCurrentCorrelation(key: String?): Float
    protected external fun getMatchScore(key: String?): Float
    protected external fun getTrackedPositionNative(key: String?, points: FloatArray?)
    protected external fun nextFrameNative(
        frameData: ByteArray?, uvData: ByteArray?, timestamp: Long, frameAlignMatrix: FloatArray?
    )

    protected external fun releaseMemoryNative()
    protected external fun getCurrentPositionNative(
        timestamp: Long,
        positionX1: Float, positionY1: Float,
        positionX2: Float, positionY2: Float,
        delta: FloatArray?
    )

    protected external fun getKeypointsPacked(scaleFactor: Float): ByteArray?
    protected external fun getKeypointsNative(onlyReturnCorrespondingKeypoints: Boolean): FloatArray?
    protected external fun drawNative(viewWidth: Int, viewHeight: Int, frameToCanvas: FloatArray?)

    init {
        trackedObjects = HashMap()
        debugHistory = Vector<PointF>(MAX_DEBUG_HISTORY_SIZE)
        downsampledFrame = ByteArray(
            (frameWidth + DOWNSAMPLE_FACTOR - 1)
                    / DOWNSAMPLE_FACTOR
                    * (frameWidth + DOWNSAMPLE_FACTOR - 1)
                    / DOWNSAMPLE_FACTOR
        )
    }
}