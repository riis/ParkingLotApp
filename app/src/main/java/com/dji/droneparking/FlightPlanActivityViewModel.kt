package com.dji.droneparking

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.lifecycle.ViewModel
import com.dji.droneparking.environment.BorderedText
import com.dji.droneparking.tflite.Classifier
import com.dji.droneparking.tracking.MultiBoxTracker
import com.dji.droneparking.util.MavicMiniMissionOperator
import com.dji.droneparking.util.WaypointMissionManager
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.SupportMapFragment
import com.mapbox.mapboxsdk.plugins.annotation.*
import dji.common.flightcontroller.LocationCoordinate3D
import dji.common.model.LocationCoordinate2D
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.products.Aircraft
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.*

class FlightPlanActivityViewModel : ViewModel() {
    var lastProcessingTimeMs: Long = 0
    var sensorOrientation: Int = 0
    var previewHeight: Int = 0
    var previewWidth: Int = 0

    var croppedBitmap: Bitmap? = null
    var cropToFrameTransform: Matrix? = null

    lateinit var detector: Classifier
    var bitmap: Bitmap? = null
    var tracker: MultiBoxTracker? = null
    var borderedText: BorderedText? = null


    val TF_OD_API_INPUT_SIZE = 416
    val TF_OD_API_IS_QUANTIZED = true
    val TF_OD_API_MODEL_FILE = "model.tflite"
    val TF_OD_API_LABELS_FILE = "file:///android_asset/coco.txt"

//    val MODE: DetectorMode = TF_OD_API
    val MINIMUM_CONFIDENCE_TF_OD_API = 0.5f
    val MAINTAIN_ASPECT = false
    val DESIRED_PREVIEW_SIZE = Size(640, 480)
    val SAVE_PREVIEW_BITMAP = false
    val TEXT_SIZE_DIP = 6f





    var aircraft: Aircraft? = null

    //listener that receives video data coming from the connected DJI product
    var receivedVideoDataListener: VideoFeeder.VideoDataListener? = null

    //handles the encoding and decoding of video data
    var codecManager: DJICodecManager? = null
    var isCameraShowing = false

    var mbMap: MapboxMap? = null
    lateinit var mapFragment: SupportMapFragment
    var styleReady = false

    var polygonCoordinates: MutableList<LatLng> = ArrayList()

    val symbols: MutableList<Symbol> = ArrayList<Symbol>()
    val flightPathSymbols: MutableList<Symbol> = ArrayList<Symbol>()
    var droneSymbol: Symbol? = null
    lateinit var symbolManager: SymbolManager

    lateinit var fillManager: FillManager
    var fill: Fill? = null

    lateinit var lineManager: LineManager
    var line: Line? = null

    var flightPlanLine: Line? = null
    var flightPlan: List<LatLng> = ArrayList()
    val flightPlan2D: MutableList<LocationCoordinate2D> = ArrayList()

    var manager: WaypointMissionManager? = null
    var operator: MavicMiniMissionOperator? = null
    lateinit var droneLocation: LocationCoordinate3D
    var located = false

    private var operatorInstance: MavicMiniMissionOperator? = null

    fun getWaypointMissionOperator(context: Context): MavicMiniMissionOperator {
        if (operatorInstance == null) {
            operatorInstance = MavicMiniMissionOperator(context)
        }
        return operatorInstance!!
    }
}