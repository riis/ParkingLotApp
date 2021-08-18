/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.
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
package com.dji.droneparking.model

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import android.util.Log
import com.dji.droneparking.FlightPlanActivityViewModel
import org.tensorflow.lite.Interpreter
import com.dji.droneparking.model.Classifier.*
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.lang.Exception
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import android.R

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream


/**
 * Wrapper for frozen detection models trained using the Tensorflow Object Detection API:
 * - https://github.com/tensorflow/models/tree/master/research/object_detection
 * where you can find the training code.
 *
 *
 * To use pretrained models in the API or convert to TF Lite models, please see docs for details:
 * - https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/detection_model_zoo.md
 * - https://github.com/tensorflow/models/blob/master/research/object_detection/g3doc/running_on_mobile_tensorflowlite.md#running-our-model-on-android
 */
open class YoloV4Classifier private constructor() : Classifier {
    var processing = false

    override fun recognizeImage(bitmap: Bitmap?): List<Recognition?>? {
        processing = true
        val scalarX = bitmap!!.width / 416.0f
        val scalarY = bitmap.height / 416.0f
        val byteBuffer = convertBitmapToByteBuffer(bitmap)

        val detections: ArrayList<Recognition> = if (isTiny) {
            getDetectionsForTiny(byteBuffer, bitmap)
        } else {
            getDetectionsForFull(byteBuffer, bitmap)
        }
        val recognitions = nms(detections)
        processing = false

        for (element in recognitions) {
            val bottom = element.getLocation().bottom * scalarY
            val top = element.getLocation().top * scalarY
            val left = element.getLocation().left * scalarX
            val right = element.getLocation().right * scalarX
            val tempRect = RectF(left, top, right, bottom)
            element.setLocation(tempRect)
        }

        return recognitions
//
//        val detections: ArrayList<Recognition>? = if (isTiny) {
//            byteBuffer?.let {
//                getDetectionsForTiny(it, bitmap!!)
//            }
//        } else {
//            byteBuffer?.let {
//                getDetectionsForFull(it, bitmap!!)
//            }
//        }
//        return detections?.let { nms(it) }
    }

    override fun enableStatLogging(debug: Boolean) {}
    override val statString: String
        get() = ""

    override fun close() {}

//    override fun setNumThreads(num_threads: Int) {
//        tfLite?.setNumThreads(num_threads)
//    }
//
//    override fun setUseNNAPI(isChecked: Boolean) {
//        tfLite?.setUseNNAPI(isChecked)
//    }

    override val objThresh: Float
        get() = FlightPlanActivityViewModel().MINIMUM_CONFIDENCE_TF_OD_API

    private var isModelQuantized = false

    // Config values.
    // Pre-allocated buffers.
    private val labels = Vector<String>()
    private lateinit var intValues: IntArray
    private var imgData: ByteBuffer? = null
    private var tfLite: Interpreter? = null

    //non maximum suppression
    private fun nms(list: ArrayList<Recognition>): ArrayList<Recognition> {
        val nmsList: ArrayList<Recognition> = ArrayList<Recognition>()
        for (k in labels.indices) {
            //1.find max confidence per class

            //1.find max confidence per class
            val pq = PriorityQueue(
                50,
                Comparator<Recognition?> { p0, p1 -> // Intentionally reversed to put high confidence at the head of the queue.
                    compareValues(p0.confidence, p1.confidence)
//                    (p0.confidence!!).compareTo(p1.confidence!!)
                })
            for (i in list.indices) {
                if (list[i].detectedClass == k) {
                    pq.add(list[i])
                }
            }

            //2.do non maximum suppression
            while (pq.size > 0) {
                //insert detection with max confidence
                val a: Array<Recognition?> = arrayOfNulls<Recognition>(pq.size)
                val detections: Array<Recognition> = pq.toArray(a)
                val max: Recognition = detections[0]
                nmsList.add(max)
                pq.clear()
                for (j in 1 until detections.size) {
                    val detection: Recognition = detections[j]
                    val b: RectF = detection.getLocation()
                    if (box_iou(max.getLocation(), b) < mNmsThresh) {
                        pq.add(detection)
                    }
                }
            }
        }
        return nmsList
    }

    private var mNmsThresh = 0.6f
    private fun box_iou(a: RectF, b: RectF): Float {
        return box_intersection(a, b) / box_union(a, b)
    }

    private fun box_intersection(a: RectF, b: RectF): Float {
        val w = overlap(
            (a.left + a.right) / 2, a.right - a.left,
            (b.left + b.right) / 2, b.right - b.left
        )
        val h = overlap(
            (a.top + a.bottom) / 2, a.bottom - a.top,
            (b.top + b.bottom) / 2, b.bottom - b.top
        )
        return if (w < 0 || h < 0) 0f else w * h
    }

    private fun box_union(a: RectF, b: RectF): Float {
        val i = box_intersection(a, b)
        return (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - i
    }

    private fun overlap(x1: Float, w1: Float, x2: Float, w2: Float): Float {
        val l1 = x1 - w1 / 2
        val l2 = x2 - w2 / 2
        val left = if (l1 > l2) l1 else l2
        val r1 = x1 + w1 / 2
        val r2 = x2 + w2 / 2
        val right = if (r1 < r2) r1 else r2
        return right - left
    }

    /**
     * Writes Image data into a `ByteBuffer`.
     */
    private fun convertBitmapToByteBuffer(bitmap_main: Bitmap): ByteBuffer {
//        val byteBuffer =
//            ByteBuffer.allocateDirect(4 * BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE)
//        byteBuffer.order(ByteOrder.nativeOrder())
//        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
//        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
//        var pixel = 0
//        for (i in 0 until INPUT_SIZE) {
//            for (j in 0 until INPUT_SIZE) {
//                val `val` = intValues[pixel++]
//                byteBuffer.putFloat((`val` shr 16 and 0xFF) / 255.0f)
//                byteBuffer.putFloat((`val` shr 8 and 0xFF) / 255.0f)
//                byteBuffer.putFloat((`val` and 0xFF) / 255.0f)
//            }
//        }
//        return byteBuffer
        val bitmap = Bitmap.createScaledBitmap(bitmap_main, INPUT_SIZE, INPUT_SIZE, false)
        val byteBuffer =
            ByteBuffer.allocateDirect(4 * BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val `val` = intValues[pixel++]
                byteBuffer.putFloat((`val` shr 16 and 0xFF) / 255.0f)
                byteBuffer.putFloat((`val` shr 8 and 0xFF) / 255.0f)
                byteBuffer.putFloat((`val` and 0xFF) / 255.0f)
            }
        }
        return byteBuffer
    }
    //    private ArrayList<Recognition> getDetections(ByteBuffer byteBuffer, Bitmap bitmap) {
    //        ArrayList<Recognition> detections = new ArrayList<Recognition>();
    //        Map<Integer, Object> outputMap = new HashMap<>();
    //        for (int i = 0; i < OUTPUT_WIDTH.length; i++) {
    //            float[][][][][] out = new float[1][OUTPUT_WIDTH[i]][OUTPUT_WIDTH[i]][3][5 + labels.size()];
    //            outputMap.put(i, out);
    //        }
    //
    //        Log.d("YoloV4Classifier", "mObjThresh: " + getObjThresh());
    //
    //        Object[] inputArray = {byteBuffer};
    //        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);
    //
    //        for (int i = 0; i < OUTPUT_WIDTH.length; i++) {
    //            int gridWidth = OUTPUT_WIDTH[i];
    //            float[][][][][] out = (float[][][][][]) outputMap.get(i);
    //
    //            Log.d("YoloV4Classifier", "out[" + i + "] detect start");
    //            for (int y = 0; y < gridWidth; ++y) {
    //                for (int x = 0; x < gridWidth; ++x) {
    //                    for (int b = 0; b < NUM_BOXES_PER_BLOCK; ++b) {
    //                        final int offset =
    //                                (gridWidth * (NUM_BOXES_PER_BLOCK * (labels.size() + 5))) * y
    //                                        + (NUM_BOXES_PER_BLOCK * (labels.size() + 5)) * x
    //                                        + (labels.size() + 5) * b;
    //
    //                        final float confidence = expit(out[0][y][x][b][4]);
    //                        int detectedClass = -1;
    //                        float maxClass = 0;
    //
    //                        final float[] classes = new float[labels.size()];
    //                        for (int c = 0; c < labels.size(); ++c) {
    //                            classes[c] = out[0][y][x][b][5 + c];
    //                        }
    //
    //                        for (int c = 0; c < labels.size(); ++c) {
    //                            if (classes[c] > maxClass) {
    //                                detectedClass = c;
    //                                maxClass = classes[c];
    //                            }
    //                        }
    //
    //                        final float confidenceInClass = maxClass * confidence;
    //                        if (confidenceInClass > getObjThresh()) {
    ////                            final float xPos = (x + (expit(out[0][y][x][b][0]) * XYSCALE[i]) - (0.5f * (XYSCALE[i] - 1))) * (INPUT_SIZE / gridWidth);
    ////                            final float yPos = (y + (expit(out[0][y][x][b][1]) * XYSCALE[i]) - (0.5f * (XYSCALE[i] - 1))) * (INPUT_SIZE / gridWidth);
    //
    //                            final float xPos = (x + expit(out[0][y][x][b][0])) * (1.0f * INPUT_SIZE / gridWidth);
    //                            final float yPos = (y + expit(out[0][y][x][b][1])) * (1.0f * INPUT_SIZE / gridWidth);
    //
    //                            final float w = (float) (Math.exp(out[0][y][x][b][2]) * ANCHORS[2 * MASKS[i][b]]);
    //                            final float h = (float) (Math.exp(out[0][y][x][b][3]) * ANCHORS[2 * MASKS[i][b] + 1]);
    //
    //                            final RectF rect =
    //                                    new RectF(
    //                                            Math.max(0, xPos - w / 2),
    //                                            Math.max(0, yPos - h / 2),
    //                                            Math.min(bitmap.getWidth() - 1, xPos + w / 2),
    //                                            Math.min(bitmap.getHeight() - 1, yPos + h / 2));
    //                            detections.add(new Recognition("" + offset, labels.get(detectedClass),
    //                                    confidenceInClass, rect, detectedClass));
    //                        }
    //                    }
    //                }
    //            }
    //            Log.d("YoloV4Classifier", "out[" + i + "] detect end");
    //        }
    //        return detections;
    //    }
    /**
     * For yolov4-tiny, the situation would be a little different from the yolov4, it only has two
     * output. Both has three dimenstion. The first one is a tensor with dimension [1, 2535,4], containing all the bounding boxes.
     * The second one is a tensor with dimension [1, 2535, class_num], containing all the classes score.
     * @param byteBuffer input ByteBuffer, which contains the image information
     * @param bitmap pixel disenty used to resize the output images
     * @return an array list containing the recognitions
     */
    private fun getDetectionsForFull(
        byteBuffer: ByteBuffer,
        bitmap: Bitmap
    ): ArrayList<Recognition> {
        val detections: ArrayList<Recognition> = ArrayList<Recognition>()
        val outputMap: MutableMap<Int, Any> = HashMap()
        outputMap[0] = Array(1) {
            Array(OUTPUT_WIDTH_FULL[0]) {
                FloatArray(
                    4
                )
            }
        }
        outputMap[1] = Array(1) {
            Array(OUTPUT_WIDTH_FULL[1]) {
                FloatArray(
                    labels.size
                )
            }
        }
        val inputArray = arrayOf<Any>(byteBuffer)
        tfLite!!.runForMultipleInputsOutputs(inputArray, outputMap)
        val gridWidth = OUTPUT_WIDTH_FULL[0]
        val bboxes = outputMap[0] as Array<Array<FloatArray>>?
        val out_score = outputMap[1] as Array<Array<FloatArray>>?
        for (i in 0 until gridWidth) {
            var maxClass = 0f
            var detectedClass = -1
            val classes = FloatArray(labels.size)
            for (c in labels.indices) {
                classes[c] = out_score!![0][i][c]
            }
            for (c in labels.indices) {
                if (classes[c] > maxClass) {
                    detectedClass = c
                    maxClass = classes[c]
                }
            }
            val score = maxClass
            if (score > objThresh) {
                val xPos = bboxes!![0][i][0]
                val yPos = bboxes[0][i][1]
                val w = bboxes[0][i][2]
                val h = bboxes[0][i][3]
                val rectF = RectF(
                    Math.max(0f, xPos - w / 2),
                    Math.max(0f, yPos - h / 2),
                    Math.min((bitmap.width - 1).toFloat(), xPos + w / 2),
                    Math.min((bitmap.height - 1).toFloat(), yPos + h / 2)
                )
                detections.add(
                    Recognition(
                        "" + i,
                        labels[detectedClass], score, rectF, detectedClass
                    )
                )
            }
        }
        return detections
    }

    private fun getDetectionsForTiny(
        byteBuffer: ByteBuffer,
        bitmap: Bitmap
    ): ArrayList<Recognition> {
        val detections: ArrayList<Recognition> = ArrayList<Recognition>()
        val outputMap: MutableMap<Int, Any> = HashMap()
        outputMap[0] = Array(1) {
            Array(OUTPUT_WIDTH_TINY[0]) {
                FloatArray(
                    4
                )
            }
        }
        outputMap[1] = Array(1) {
            Array(OUTPUT_WIDTH_TINY[1]) {
                FloatArray(
                    labels.size
                )
            }
        }
        val inputArray = arrayOf<Any>(byteBuffer)
        tfLite!!.runForMultipleInputsOutputs(inputArray, outputMap)
        val gridWidth = OUTPUT_WIDTH_TINY[0]
        val bboxes = outputMap[0] as Array<Array<FloatArray>>?
        val out_score = outputMap[1] as Array<Array<FloatArray>>?
        for (i in 0 until gridWidth) {
            var maxClass = 0f
            var detectedClass = -1
            val classes = FloatArray(labels.size)
            for (c in labels.indices) {
                classes[c] = out_score!![0][i][c]
            }
            for (c in labels.indices) {
                if (classes[c] > maxClass) {
                    detectedClass = c
                    maxClass = classes[c]
                }
            }
            val score = maxClass
            if (score > objThresh) {
                val xPos = bboxes!![0][i][0]
                val yPos = bboxes[0][i][1]
                val w = bboxes[0][i][2]
                val h = bboxes[0][i][3]
                val rectF = RectF(
                    Math.max(0f, xPos - w / 2),
                    Math.max(0f, yPos - h / 2),
                    Math.min((bitmap.width - 1).toFloat(), xPos + w / 2),
                    Math.min((bitmap.height - 1).toFloat(), yPos + h / 2)
                )
                detections.add(
                    Recognition(
                        "" + i,
                        labels[detectedClass], score, rectF, detectedClass
                    )
                )
            }
        }
        return detections
    }

    fun checkInvalidateBox(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        oriW: Float,
        oriH: Float,
        intputSize: Int
    ): Boolean {
        // (1) (x, y, w, h) --> (xmin, ymin, xmax, ymax)
        val halfHeight = height / 2.0f
        val halfWidth = width / 2.0f
        val pred_coor = floatArrayOf(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)

        // (2) (xmin, ymin, xmax, ymax) -> (xmin_org, ymin_org, xmax_org, ymax_org)
        val resize_ratioW = 1.0f * intputSize / oriW
        val resize_ratioH = 1.0f * intputSize / oriH
        val resize_ratio = if (resize_ratioW > resize_ratioH) resize_ratioH else resize_ratioW //min
        val dw = (intputSize - resize_ratio * oriW) / 2
        val dh = (intputSize - resize_ratio * oriH) / 2
        pred_coor[0] = 1.0f * (pred_coor[0] - dw) / resize_ratio
        pred_coor[2] = 1.0f * (pred_coor[2] - dw) / resize_ratio
        pred_coor[1] = 1.0f * (pred_coor[1] - dh) / resize_ratio
        pred_coor[3] = 1.0f * (pred_coor[3] - dh) / resize_ratio

        // (3) clip some boxes those are out of range
        pred_coor[0] = if (pred_coor[0] > 0) (pred_coor[0]) else 0f
        pred_coor[1] = if (pred_coor[1] > 0) (pred_coor[1]) else 0f
        pred_coor[2] = if (pred_coor[2] < oriW - 1) pred_coor[2] else oriW - 1
        pred_coor[3] = if (pred_coor[3] < oriH - 1) pred_coor[3] else oriH - 1
        if (pred_coor[0] > pred_coor[2] || pred_coor[1] > pred_coor[3]) {
            pred_coor[0] = 0f
            pred_coor[1] = 0f
            pred_coor[2] = 0f
            pred_coor[3] = 0f
        }

        // (4) discard some invalid boxes
        val temp1 = pred_coor[2] - pred_coor[0]
        val temp2 = pred_coor[3] - pred_coor[1]
        val temp = temp1 * temp2
        if (temp < 0) {
            Log.e("checkInvalidateBox", "temp < 0")
            return false
        }
        if (Math.sqrt(temp.toDouble()) > Float.MAX_VALUE) {
            Log.e("checkInvalidateBox", "temp max")
            return false
        }
        return true
    }

    companion object {
        /**
         * Initializes a native TensorFlow session for classifying images.
         *
         * @param assetManager  The asset manager to be used to load assets.
         * @param modelFilename The filepath of the model GraphDef protocol buffer.
         * @param labelFilename The filepath of label file for classes.
         * @param isQuantized   Boolean representing model is quantized or not
         */
        @Throws(IOException::class)
        fun create(
            assetManager: AssetManager,
            modelFilename: String?,
            labelFilename: String,
            isQuantized: Boolean
        ): Classifier {
            val d = YoloV4Classifier()
            val actualFilename =
                labelFilename.split("file:///android_asset/".toRegex()).toTypedArray()[1]
            val labelsInput = assetManager.open(actualFilename)
            val br = BufferedReader(InputStreamReader(labelsInput))
            var line: String
//            while (br.readLine().also { line = it } != null) {
////                LOGGER.w(line)
//                d.labels.add(line)
//            }
//            br.close()
            d.labels.add("Car")
            try {
                val options = Interpreter.Options()
                options.setNumThreads(NUM_THREADS)
                if (isNNAPI) {
                    var nnApiDelegate: NnApiDelegate? = null
                    // Initialize interpreter with NNAPI delegate for Android Pie or above
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        nnApiDelegate = NnApiDelegate()
                        options.addDelegate(nnApiDelegate)
                        options.setNumThreads(NUM_THREADS)
                        options.setUseNNAPI(false)
                        options.setAllowFp16PrecisionForFp32(true)
                        options.setAllowBufferHandleOutput(true)
                        options.setUseNNAPI(true)
                    }
                }
                if (isGPU) {
                    val gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                }
                d.tfLite = Interpreter(Utils.loadModelFile(assetManager, modelFilename), options)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
            d.isModelQuantized = isQuantized
            // Pre-allocate buffers.
            val numBytesPerChannel: Int = if (isQuantized) {
                1 // Quantized
            } else {
                4 // Floating point
            }
            d.imgData =
                ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * numBytesPerChannel)
            d.imgData?.order(ByteOrder.nativeOrder())
            d.intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
            return d
        }

//        private val LOGGER: Logger = Logger()

        // Float model
        private const val IMAGE_MEAN = 0f
        private const val IMAGE_STD = 255.0f

        //config yolov4
        private const val INPUT_SIZE = 416
        private val OUTPUT_WIDTH = intArrayOf(52, 26, 13)
        private val MASKS = arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8))
        private val ANCHORS = intArrayOf(
            12, 16, 19, 36, 40, 28, 36, 75, 76, 55, 72, 146, 142, 110, 192, 243, 459, 401
        )
        private val XYSCALE = floatArrayOf(1.2f, 1.1f, 1.05f)
        private const val NUM_BOXES_PER_BLOCK = 3

        // Number of threads in the java app
        private const val NUM_THREADS = 4
        private const val isNNAPI = false
        private const val isGPU = true

        // tiny or not
        private const val isTiny = true

        // config yolov4 tiny
        private val OUTPUT_WIDTH_TINY = intArrayOf(2535, 2535)
        private val OUTPUT_WIDTH_FULL = intArrayOf(2535, 2535)
        private val MASKS_TINY = arrayOf(intArrayOf(3, 4, 5), intArrayOf(1, 2, 3))
        private val ANCHORS_TINY = intArrayOf(
            23, 27, 37, 58, 81, 82, 81, 82, 135, 169, 344, 319
        )
        private val XYSCALE_TINY = floatArrayOf(1.05f, 1.05f)
        private const val BATCH_SIZE = 1
        private const val PIXEL_SIZE = 3
    }
}