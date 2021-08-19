/* Copyright 2016 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/
package com.dji.droneparking.tracker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.util.Pair
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import com.dji.droneparking.tracker.ObjectTracker.*
import java.util.*
import com.dji.droneparking.model.Classifier.*
import com.dji.droneparking.model.ImageUtils
import com.dji.droneparking.tracker.ObjectTracker.Companion.clearInstance
import com.dji.droneparking.tracker.ObjectTracker.Companion.getInstance
import kotlin.math.roundToInt

/**
 * A tracker wrapping ObjectTracker that also handles non-max suppression and matching existing
 * objects to new detections.
 */
class MultiBoxTracker(private val context: Context) {
    private val availableColors: Queue<Int> = LinkedList()
    private val activity: Activity = Activity()
    private var numSheep = 0
    var objectTracker: ObjectTracker? = null
    val screenRects: MutableList<Pair<Float, RectF>> = LinkedList<Pair<Float, RectF>>()

    private class TrackedRecognition {
        var trackedObject: TrackedObject? = null
        var location: RectF? = null
        var detectionConfidence = 0f
        var color = 0
        var title: String? = null
    }

    private val trackedObjects: MutableList<TrackedRecognition> = LinkedList()
    private val boxPaint = Paint()
    private val textPaint: TextPaint = TextPaint()
    private val textSizePx: Float
    private val borderedText: BorderedText
    private var frameToCanvasMatrix: Matrix? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var sensorOrientation = 0
    @SuppressLint("DefaultLocale")
    @Synchronized
    fun drawDebug(canvas: Canvas) {
        val textPaint = Paint()
        textPaint.color = Color.WHITE
        textPaint.textSize = 60.0f
        val boxPaint = Paint()
        boxPaint.color = Color.RED
        boxPaint.alpha = 200
        boxPaint.style = Paint.Style.STROKE
        for (detection in screenRects) {
            val rect: RectF = detection.second
            canvas.drawRect(rect, boxPaint)
            canvas.drawText("" + detection.first, rect.left, rect.top, textPaint)
            borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first)
        }
        if (objectTracker == null) {
            return
        }

        // Draw correlations.
        for (recognition in trackedObjects) {
            val trackedObject: TrackedObject? = recognition.trackedObject
            val trackedPos: RectF? = trackedObject?.trackedPositionInPreviewFrame
            if (frameToCanvasMatrix!!.mapRect(trackedPos)) {
                val labelString =
                    java.lang.String.format("%.2f", trackedObject?.currentCorrelation)
                if (trackedPos != null) {
                    borderedText.drawText(canvas, trackedPos.right, trackedPos.bottom, labelString)
                }
            }
        }
        val matrix = frameToCanvasMatrix
        objectTracker!!.drawDebug(canvas, matrix)
    }

    @Synchronized
    fun trackResults(
        results: List<Recognition>, frame: ByteArray, timestamp: Long
    ) {
        processResults(timestamp, results, frame)
    }

    @Synchronized
    fun draw(canvas: Canvas) {
        val rotated = sensorOrientation % 180 == 90
        val multiplier = Math.min(
            canvas.height / (if (rotated) frameWidth else frameHeight).toFloat(),
            canvas.width / (if (rotated) frameHeight else frameWidth).toFloat()
        )
        frameToCanvasMatrix = ImageUtils.getTransformationMatrix(
            frameWidth,
            frameHeight,
            (multiplier * if (rotated) frameHeight else frameWidth).toInt(),
            (multiplier * if (rotated) frameWidth else frameHeight).toInt(),
            sensorOrientation,
            false
        )
        for (recognition in trackedObjects) {
            if (recognition.detectionConfidence < 0.55f) continue
            val trackedPos: RectF? =
                if (objectTracker != null) recognition.trackedObject?.trackedPositionInPreviewFrame else RectF(
                    recognition.location
                )
            frameToCanvasMatrix!!.mapRect(trackedPos)
            boxPaint.color = Color.YELLOW
            val labelString = (recognition.detectionConfidence * 100).roundToInt().toString() + "%"
            boxPaint.style = Paint.Style.STROKE
            if (trackedPos != null) {
                canvas.drawRect(trackedPos, boxPaint)
            }
            boxPaint.style = Paint.Style.FILL_AND_STROKE
            if (trackedPos != null) {
                canvas.drawRect(
                    trackedPos.left,
                    trackedPos.top - 50,
                    trackedPos.left + 60,
                    trackedPos.top,
                    boxPaint
                )
            }
            if (trackedPos != null) {
                canvas.drawText(labelString, trackedPos.left + 5, trackedPos.top - 15, textPaint)
            }
        }
//        activity.runOnUiThread(Runnable { numberTV.text = numSheep.toString() })

        // TODO: Make this nicer
        //borderedText.drawText(canvas, 30, canvas.getHeight() - 50, "SHEEP: " + numSheep);
    }

    private var initialized = false
    @Synchronized
    fun onFrame(
        w: Int,
        h: Int,
        rowStride: Int,
        sensorOrientation: Int,
        frame: ByteArray?,
        timestamp: Long
    ) {
        if (objectTracker == null && !initialized) {
            clearInstance()
            objectTracker =
                getInstance(w, h, rowStride, true)
            frameWidth = w
            frameHeight = h
            this.sensorOrientation = sensorOrientation
            initialized = true
            if (objectTracker == null) {
                val message = ("Object tracking support not found. "
                        + "See tensorflow/examples/android/README.md for details.")
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        if (objectTracker == null) {
            return
        }
        objectTracker!!.nextFrame(frame, null, timestamp, null, true)

        // Clean up any objects not worth tracking any more.
        val copyList = LinkedList(trackedObjects)
        for (recognition in copyList) {
            val trackedObject: TrackedObject? = recognition.trackedObject
            val correlation: Float? = trackedObject?.currentCorrelation
            if (correlation != null) {
                if (correlation < MIN_CORRELATION) {
                    trackedObject.stopTracking()
                    trackedObjects.remove(recognition)
                    availableColors.add(recognition.color)
                }
            }
        }
    }

    private fun processResults(
        timestamp: Long, results: List<Recognition>, originalFrame: ByteArray
    ) {
        val rectsToTrack: MutableList<Pair<Float, Recognition>> =
            LinkedList<Pair<Float, Recognition>>()
        screenRects.clear()
        val rgbFrameToScreen = Matrix(
            frameToCanvasMatrix
        )
        numSheep = 0
        for (result in results) {
            val detectionFrameRect = RectF(result.getLocation())
            val detectionScreenRect = RectF()
            rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect)
            if (result.confidence!! > 0.55f) {
                numSheep++
            }
            screenRects.add(Pair<Float, RectF>(result.confidence, detectionScreenRect))
            if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
                continue
            }
            rectsToTrack.add(Pair<Float, Recognition>(result.confidence, result))
        }
        if (rectsToTrack.isEmpty()) {
            return
        }
        if (objectTracker == null) {
            trackedObjects.clear()
            for (potential in rectsToTrack) {
                val trackedRecognition = TrackedRecognition()
                trackedRecognition.detectionConfidence = potential.first
                trackedRecognition.location = RectF(potential.second.getLocation())
                trackedRecognition.trackedObject = null
                trackedRecognition.title = potential.second.title
                trackedRecognition.color = COLORS[trackedObjects.size]
                trackedObjects.add(trackedRecognition)
                if (trackedObjects.size >= COLORS.size) {
                    break
                }
            }
            return
        }
        for (potential in rectsToTrack) {
            handleDetection(originalFrame, timestamp, potential)
        }
    }

    private fun handleDetection(
        frameCopy: ByteArray, timestamp: Long, potential: Pair<Float, Recognition>
    ) {
        val potentialObject: TrackedObject =
            objectTracker!!.trackObject(potential.second.getLocation(), timestamp, frameCopy)
        val potentialCorrelation: Float = potentialObject.currentCorrelation
        if (potentialCorrelation < MARGINAL_CORRELATION) {
            potentialObject.stopTracking()
            return
        }
        val removeList: MutableList<TrackedRecognition> = LinkedList()
        var maxIntersect = 0.0f

        // This is the current tracked object whose color we will take. If left null we'll take the
        // first one from the color queue.
        var recogToReplace: TrackedRecognition? = null

        // Look for intersections that will be overridden by this object or an intersection that would
        // prevent this one from being placed.
        for (trackedRecognition in trackedObjects) {
            val a: RectF? = trackedRecognition.trackedObject?.trackedPositionInPreviewFrame
            val b: RectF? = potentialObject.trackedPositionInPreviewFrame
            val intersection = RectF()
            val intersects: Boolean = a?.let { intersection.setIntersect(it, b!!) } == true
            val intersectArea: Float = intersection.width() * intersection.height()
            val totalArea: Float = a!!.width() * a.height() + b!!.width() * b.height() - intersectArea
            val intersectOverUnion = intersectArea / totalArea

            // If there is an intersection with this currently tracked box above the maximum overlap
            // percentage allowed, either the new recognition needs to be dismissed or the old
            // recognition needs to be removed and possibly replaced with the new one.
            if (intersects && intersectOverUnion > MAX_OVERLAP) {
                if (potential.first < trackedRecognition.detectionConfidence
                    && trackedRecognition.trackedObject?.currentCorrelation!! > MARGINAL_CORRELATION
                ) {
                    // If track for the existing object is still going strong and the detection score was
                    // good, reject this new object.
                    potentialObject.stopTracking()
                    return
                } else {
                    removeList.add(trackedRecognition)

                    // Let the previously tracked object with max intersection amount donate its color to
                    // the new object.
                    if (intersectOverUnion > maxIntersect) {
                        maxIntersect = intersectOverUnion
                        recogToReplace = trackedRecognition
                    }
                }
            }
        }

        // If we're already tracking the max object and no intersections were found to bump off,
        // pick the worst current tracked object to remove, if it's also worse than this candidate
        // object.
        if (availableColors.isEmpty() && removeList.isEmpty()) {
            for (candidate in trackedObjects) {
                if (candidate.detectionConfidence < potential.first) {
                    if (recogToReplace == null
                        || candidate.detectionConfidence < recogToReplace.detectionConfidence
                    ) {
                        // Save it so that we use this color for the new object.
                        recogToReplace = candidate
                    }
                }
            }
            if (recogToReplace != null) {
                removeList.add(recogToReplace)
            }
        }

        // Remove everything that got intersected.
        for (trackedRecognition in removeList) {
            trackedRecognition.trackedObject?.stopTracking()
            trackedObjects.remove(trackedRecognition)
            if (trackedRecognition !== recogToReplace) {
                availableColors.add(trackedRecognition.color)
            }
        }
        if (recogToReplace == null && availableColors.isEmpty()) {
            potentialObject.stopTracking()
            return
        }

        // Finally safe to say we can track this object.
        val trackedRecognition = TrackedRecognition()
        trackedRecognition.detectionConfidence = potential.first
        trackedRecognition.trackedObject = potentialObject
        trackedRecognition.title = potential.second.title

        // Use the color from a replaced object before taking one from the color queue.
        trackedRecognition.color = recogToReplace?.color ?: availableColors.poll()!!
        trackedObjects.add(trackedRecognition)
    }

    companion object {
        private const val TEXT_SIZE_DIP = 18f

        // Maximum percentage of a box that can be overlapped by another box at detection time. Otherwise
        // the lower scored box (new or old) will be removed.
        private const val MAX_OVERLAP = 0.1f
        private const val MIN_SIZE = 16.0f

        // Allow replacement of the tracked box with new results if
        // correlation has dropped below this level.
        private const val MARGINAL_CORRELATION = 0.75f

        // Consider object to be lost if correlation falls below this threshold.
        private const val MIN_CORRELATION = 0.3f
        private val COLORS = intArrayOf(
            Color.BLUE,
            Color.RED,
            Color.GREEN,
            Color.YELLOW,
            Color.CYAN,
            Color.MAGENTA,
            Color.WHITE,
            Color.parseColor("#55FF55"),
            Color.parseColor("#FFA500"),
            Color.parseColor("#FF8888"),
            Color.parseColor("#AAAAFF"),
            Color.parseColor("#FFFFAA"),
            Color.parseColor("#55AAAA"),
            Color.parseColor("#AA33AA"),
            Color.parseColor("#0D0068")
        )
    }

    init {
        for (color in COLORS) {
            availableColors.add(color)
        }
        boxPaint.color = Color.RED
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 12.0f
        boxPaint.strokeCap = Paint.Cap.ROUND
        boxPaint.strokeJoin = Paint.Join.ROUND
        boxPaint.strokeMiter = 100f
        textPaint.color = Color.BLACK
        textPaint.textSize = 28f
        textPaint.isFakeBoldText = true
        textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.resources.displayMetrics
        )
        borderedText = BorderedText(textSizePx)
    }
}