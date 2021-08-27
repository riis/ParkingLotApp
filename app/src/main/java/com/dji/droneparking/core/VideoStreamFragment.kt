package com.dji.droneparking.core

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.dji.droneparking.R
import com.dji.droneparking.customview.OverlayView
import com.dji.droneparking.environment.BorderedText
import com.dji.droneparking.environment.ImageUtils
import com.dji.droneparking.tflite.Classifier
import com.dji.droneparking.tflite.YoloV4Classifier
import com.dji.droneparking.tracking.MultiBoxTracker
import com.dji.droneparking.util.DJIDemoApplication
import com.dji.droneparking.util.MavicMiniMissionOperator
import com.dji.droneparking.util.Tools
import com.dji.droneparking.viewmodel.FlightPlanActivityViewModel
import dji.common.product.Model
import dji.sdk.base.BaseProduct
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*

class VideoStreamFragment : Fragment(), TextureView.SurfaceTextureListener, View.OnClickListener {

    private lateinit var trackingOverlay: OverlayView
    private lateinit var videoSurface: TextureView
    private val vM: FlightPlanActivityViewModel by viewModels()
    private lateinit var videoView: FrameLayout
    private lateinit var mapView: FrameLayout
    private lateinit var backButton: Button


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_video_stream, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        videoSurface = view.findViewById(R.id.video_previewer_surface)
        backButton = view.findViewById(R.id.videoToMapButton)
        backButton.setOnClickListener(this)
        videoView = activity?.findViewById(R.id.video_view_layout)!!
        mapView = activity?.findViewById(R.id.map_view_layout)!!
        trackingOverlay = view.findViewById(R.id.tracking_overlay)
        //Giving videoSurface a listener that checks for when a surface texture is available.
        //The videoSurface will then display the surface texture, which in this case is a camera video stream.
        videoSurface.surfaceTextureListener = this
        // The receivedVideoDataListener receives the raw video data and the size of the data from the DJI product.
        // It then sends this data to the codec manager for decoding.
        vM.receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            vM.codecManager?.sendDataToDecoder(videoBuffer, size)
        }
    }

    //When the Fragment is created or resumed, initialize the video feed display
    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    //When the Fragment is paused, clear the video feed display
    override fun onPause() {
        uninitPreviewer()
        super.onPause()
    }

    //When the Fragment is destroyed, clear the video feed display
    override fun onDestroy() {
        uninitPreviewer()
        super.onDestroy()
    }

    //When a TextureView's SurfaceTexture is ready for use, use it to initialize the codecManager
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (vM.codecManager == null) {
            vM.codecManager = DJICodecManager(context, surface, width, height)
        }

        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            vM.TEXT_SIZE_DIP,
            resources.displayMetrics
        )
        vM.borderedText = BorderedText(textSizePx)
        vM.borderedText!!.setTypeface(Typeface.MONOSPACE)

        vM.tracker = context?.let { MultiBoxTracker(it) }

        val cropSize: Int =
            vM.TF_OD_API_INPUT_SIZE

        try {
            vM.detector = YoloV4Classifier.create(
                context?.assets,
                vM.TF_OD_API_MODEL_FILE,
                vM.TF_OD_API_LABELS_FILE,
                vM.TF_OD_API_IS_QUANTIZED
            )
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e(
                "BANANAPIE",
                "Exception initializing classifier!"
            )
            Toast.makeText(
                context, "Classifier could not be initialized", Toast.LENGTH_SHORT
            ).show()
//            finish()
        }

        vM.previewWidth = width
        vM.previewHeight = height
        Log.d("BANANAPIE", "RGB FRAME BITMAP DIMENSIONS: ${vM.previewWidth} x ${vM.previewHeight}")

        vM.sensorOrientation = 0
        vM.croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)

        vM.cropToFrameTransform = ImageUtils.getTransformationMatrix(
            cropSize,
            cropSize,
            vM.previewWidth,
            vM.previewHeight,
            vM.sensorOrientation,
            vM.MAINTAIN_ASPECT
        )

        trackingOverlay.addCallback(
            object : OverlayView.DrawCallback {
                override fun drawCallback(canvas: Canvas?) {
                    if (canvas != null) {
                        vM.tracker!!.draw(canvas)
                    }
                }
            })

        vM.tracker!!.setFrameConfiguration(
            vM.previewWidth,
            vM.previewHeight,
            vM.sensorOrientation
        )

        lifecycleScope.launch(Dispatchers.Default){
            while (true){
                videoSurface.bitmap?.let {
                    runObjectDetection(it)
                }

//                withContext(Dispatchers.Main){
//                    altitudeTV.text = "${"%.1f".format(vM.droneLocation.altitude)}\nm"
//                }

            }
        }
    }

    //When a SurfaceTexture is updated
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    //when a SurfaceTexture's size changes
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    //when a SurfaceTexture is about to be destroyed, uninitialized the codedManager
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        vM.codecManager?.cleanSurface()
        vM.codecManager = null
        return false
    }

    /**
     * runObjectDetection(bitmap: Bitmap)
     *      TFLite Object Detection function
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun runObjectDetection(bitmap: Bitmap) {//change this to bmp to run the code below

//        var bitmap = Utils.getBitmapFromAsset(applicationContext, "bmp.jpg")
        //invalidate the overlay so that it is immediately ready to be drawn on
        activity?.runOnUiThread{
            trackingOverlay.postInvalidate()

            vM.croppedBitmap = Bitmap.createScaledBitmap(bitmap, 416, 416, false)

            // For examining the actual TF input.
            if (vM.SAVE_PREVIEW_BITMAP) {
                vM.croppedBitmap?.let { context?.let { it1 ->
                    ImageUtils.saveBitmap(it, "photo",
                        it1
                    )
                } }
            }
        }

        val startTime = SystemClock.uptimeMillis()
        vM.results = vM.detector.recognizeImage(vM.croppedBitmap) as MutableList<Classifier.Recognition>?
        Log.e("BANANAPIE", "run: " + (vM.results)?.size)
        vM.lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime

        activity?.runOnUiThread{
            val paint = Paint()
            paint.color = Color.RED
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.0f

            val canvas = Canvas(bitmap)

            val minimumConfidence: Float = vM.MINIMUM_CONFIDENCE_TF_OD_API

            val mappedRecognitions: MutableList<Classifier.Recognition> =
                LinkedList<Classifier.Recognition>()
            for (result in vM.results!!) {
                val location: RectF = result.location
                if (result.confidence!! >= minimumConfidence) {
                    canvas.drawRect(location, paint)
                    vM.cropToFrameTransform?.mapRect(location)
                    result.location = location
                    mappedRecognitions.add(result)
                }
            }
            vM.tracker?.trackResults(mappedRecognitions, vM.results!!.size.toLong())
            trackingOverlay.postInvalidate()
            Log.d("BANANAPIE", "${vM.lastProcessingTimeMs} ms")
        }

    }

    //Function that initializes the display for the videoSurface TextureView
    private fun initPreviewer() {
        val product: BaseProduct = DJIDemoApplication.getProductInstance()
            ?: return //gets an instance of the connected DJI product (null if nonexistent)
        //if DJI product is disconnected, alert the user
        if (!product.isConnected) {
            Tools.showToast(activity as Activity, getString(R.string.disconnected))
        } else {
            //if the DJI product is connected and the aircraft model is not unknown, add the received VideoDataListener
            // ... to the primary video feed.
            videoSurface.surfaceTextureListener = this
            if (product.model != Model.UNKNOWN_AIRCRAFT) {
                VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(
                    vM.receivedVideoDataListener
                )
            }
        }
    }

    //Function that uninitializes the display for the videoSurface TextureView
    private fun uninitPreviewer() {
        DJIDemoApplication.getCameraInstance() ?: return
        VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(null)

    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.videoToMapButton -> {
                videoView.visibility = View.INVISIBLE
                mapView.visibility = View.VISIBLE
            }
        }
    }
}