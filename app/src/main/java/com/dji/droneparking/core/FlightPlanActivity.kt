package com.dji.droneparking.core


import android.content.Context
import android.graphics.*
import android.os.*
import android.util.Log
import android.util.TypedValue
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.dji.droneparking.R
import com.dji.droneparking.customview.OverlayView
import com.dji.droneparking.dialog.DownloadDialog
import com.dji.droneparking.environment.BorderedText
import com.dji.droneparking.environment.ImageUtils
import com.dji.droneparking.tflite.Classifier
import com.dji.droneparking.tflite.YoloV4Classifier
import com.dji.droneparking.tracking.MultiBoxTracker
import com.dji.droneparking.util.*
import com.dji.droneparking.util.Tools.showToast
import com.dji.droneparking.viewmodel.FlightPlanActivityViewModel
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.*
import com.mapbox.mapboxsdk.plugins.annotation.*
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.riis.cattlecounter.util.distanceToSegment
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJICameraError
import dji.common.error.DJIError
import dji.common.gimbal.*
import dji.common.mission.waypoint.WaypointMission
import dji.common.model.LocationCoordinate2D
import dji.common.product.Model
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseProduct
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.gimbal.Gimbal
import dji.sdk.media.MediaFile
import dji.sdk.media.MediaManager
import dji.sdk.products.Aircraft
import dji.ux.widget.TakeOffWidget
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*


class FlightPlanActivity : AppCompatActivity(), OnMapReadyCallback,
    MapboxMap.OnMapClickListener, View.OnClickListener, TextureView.SurfaceTextureListener {

    private val vM: FlightPlanActivityViewModel by viewModels()

    private var mapTouch: Boolean = false

    private lateinit var cancelFlightBtn: Button
//    private lateinit var overlayView: LinearLayout
    private lateinit var layoutConfirmPlan: LinearLayout
    private lateinit var layoutCancelPlan: LinearLayout
    private lateinit var startFlightBtn: Button
    private lateinit var locateBtn: Button
    private lateinit var takeoffWidget: TakeOffWidget
    private lateinit var mContext: Context
    private lateinit var cameraBtn: Button
    private lateinit var videoSurface: TextureView
    private lateinit var videoView: CardView
    private lateinit var gimbal: Gimbal
    private lateinit var trackingOverlay: OverlayView

    private lateinit var mLoadingDialog: DownloadDialog

    private var currentDeleteIndex = 0
    private var numOfOldPhotos = 0

    private var mMediaManager: MediaManager? = null //uninitialized media manager
    private var mediaFileList: MutableList<MediaFile> =
        mutableListOf() //empty list of MediaFile objects
    private lateinit var operator: MavicMiniMissionOperator

    private val symbolDragListener = object : OnSymbolDragListener {
        override fun onAnnotationDrag(symbol: Symbol) {}

        override fun onAnnotationDragFinished(symbol: Symbol) {
            configureFlightPlan(symbol)
        }

        override fun onAnnotationDragStarted(symbol: Symbol) {

            vM.symbols.remove(symbol)
            vM.polygonCoordinates.remove(symbol.latLng)

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        operator = vM.getWaypointMissionOperator(this)

//        vM.options = ObjectDetector.ObjectDetectorOptions
//            .builder()
//            .setNumThreads(2)
//            .setMaxResults(5)
//            .setScoreThreshold(0.5f)
//            .build()

//        vM.detector = ObjectDetector.createFromFileAndOptions(
//            this, // the application context
//            "model.tflite", // must be same as the filename in assets folder
//            vM.options
//        )


//        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
//        try {
//            gimbal = DJISDKManager.getInstance().product.gimbal
//
//            gimbal.rotate(
//                rotation
//            ) { djiError ->
//                if (djiError == null) {
//                    Log.d("BANANAPIE", "rotate gimbal success")
//                    Toast.makeText(applicationContext, "rotate gimbal success", Toast.LENGTH_SHORT)
//                        .show()
//                } else {
//                    Log.d("BANANAPIE", "rotate gimbal error " + djiError.description)
//                    Toast.makeText(applicationContext, djiError.description, Toast.LENGTH_SHORT)
//                        .show()
//                }
//            }
//        } catch (e: Exception) {
//            Log.d("BANANAPIE", "Drone is likely not connected")
//        }


        setContentView(R.layout.activity_flight_plan_mapbox)

        initUI()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mContext = applicationContext
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))

        vM.mapFragment =
            supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        vM.mapFragment.onCreate(savedInstanceState)
        vM.mapFragment.getMapAsync(this)



        operator.droneLocationLiveData.observe(this, { location ->

            if (vM.styleReady) {

                vM.droneLocation = location

                if (!vM.located) {
                    cameraUpdate()
                    vM.located = true
                }

                updateDroneLocation()
            }
        })

        // The receivedVideoDataListener receives the raw video data and the size of the data from the DJI product.
        // It then sends this data to the codec manager for decoding.
        vM.receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            vM.codecManager?.sendDataToDecoder(videoBuffer, size)
//            lifecycleScope.async(Dispatchers.Default){runObjectDetection(BitmapFactory.decodeByteArray(videoBuffer, 0, videoBuffer.size))}
//            Log.d("BANANAPIE", "new frame")
        }
    }

    override fun onMapReady(mapboxMap: MapboxMap) {

        vM.mbMap = mapboxMap

        mapboxMap.setStyle(Style.SATELLITE_STREETS) { style: Style ->

            vM.styleReady = true
            var bm: Bitmap? =
                Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_waypoint_marker_unvisited)
            bm?.let { mapboxMap.style?.addImage("ic_waypoint_marker_unvisited", it) }

            bm = Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_drone)
            bm?.let { mapboxMap.style?.addImage("ic_drone", it) }

            vM.fillManager =
                FillManager(vM.mapFragment.view as MapView, mapboxMap, style)
            vM.lineManager =
                LineManager(vM.mapFragment.view as MapView, mapboxMap, style)

            vM.symbolManager =
                SymbolManager(vM.mapFragment.view as MapView, mapboxMap, style)
            vM.symbolManager.iconAllowOverlap = true
            vM.symbolManager.addDragListener(symbolDragListener)


        }

        val position = CameraPosition.Builder()
            .zoom(18.0)
            .build()

        mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(position))

        vM.mbMap?.addOnMapClickListener(this)
    }

    override fun onMapClick(point: LatLng): Boolean {

        if (mapTouch){
            val symbol = vM.symbolManager.create(
                SymbolOptions()
                    .withLatLng(LatLng(point))
                    .withIconImage("ic_waypoint_marker_unvisited")
                    .withIconHaloWidth(2.0f)
                    .withDraggable(true)
            )
            //      Add new point to the list
            configureFlightPlan(symbol)
            return true
        }
        else{
            return false
        }

    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate_button -> {
                updateDroneLocation()
                cameraUpdate()
            }
//            R.id.get_started_button -> {
////                overlayView.animate()
////                    .alpha(0.0f)
////                    .translationXBy(1000f)
////                    .setDuration(500)
////                    .setListener(null)
////                    .start()
//
//                locateBtn.visibility = View.VISIBLE
//                cameraBtn.visibility = View.VISIBLE
//                showClearMemoryDialog()
//            }
            R.id.start_flight_button -> {
                when (vM.aircraft) {
                    null -> {
                        Toast.makeText(mContext, "Please connect to a drone", Toast.LENGTH_SHORT)
                            .show()
                    }
                    else -> {

                        val djiMission: WaypointMission =
                            FlightPlanner.createFlightMissionFromCoordinates(vM.flightPlan2D)

                        vM.manager = WaypointMissionManager(
                            djiMission,
                            operator,
                            findViewById(R.id.label_flight_plan),
                            this@FlightPlanActivity
                        )

                        vM.manager?.startMission()
                        mapTouch = false
                        layoutConfirmPlan.visibility = View.GONE
                        layoutCancelPlan.visibility = View.VISIBLE
                    }
                }
            }
            R.id.cancel_flight_button -> {
                vM.manager?.stopFlight() ?: return
                clearMapViews()
                layoutCancelPlan.visibility = View.GONE
            }
            R.id.cancel_flight_plan_button -> {
                layoutConfirmPlan.visibility = View.GONE
                clearMapViews()
            }
            R.id.camera_button -> {
                vM.isCameraShowing = !vM.isCameraShowing

                if (vM.isCameraShowing) {
                    videoView.visibility = View.VISIBLE
                    locateBtn.visibility = View.GONE
                    cameraBtn.text = R.string.map.toString()

                } else {
                    videoView.visibility = View.GONE
                    locateBtn.visibility = View.VISIBLE
                    cameraBtn.text = R.string.camera.toString()
                }
            }
        }
    }

    //When the MainActivity is created or resumed, initialize the video feed display
    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    //When the MainActivity is paused, clear the video feed display
    override fun onPause() {
        uninitPreviewer()
        super.onPause()
    }

    //When the MainActivity is destroyed, clear the video feed display
    override fun onDestroy() {
        uninitPreviewer()
        super.onDestroy()
    }

    //Function that uninitializes the display for the videoSurface TextureView
    private fun uninitPreviewer() {
        DJIDemoApplication.getCameraInstance() ?: return
        VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(null)

    }

    private fun showClearMemoryDialog() {
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.ensure_clear_sd)
            .setCancelable(false)
            .setTitle(R.string.title_clear_sd)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->

                clearSDCard()
            }
            .create()

        dialog.show()
    }

    private fun deleteOneFileFromList(){

        val deleteList: List<MediaFile> = listOf(mediaFileList[0])

        DJIDemoApplication.getCameraInstance()
            ?.let { camera -> //Get an instance of the connected DJI product's camera
                mMediaManager = camera.mediaManager //Get the camera's MediaManager
                mMediaManager?.let { mediaManager ->

                    mediaManager.deleteFiles(
                        deleteList,
                        object :
                            CommonCallbacks.CompletionCallbackWithTwoParam<List<MediaFile?>?, DJICameraError?> {
                            //if the deletion from the SD card is successful...
                            override fun onSuccess(
                                x: List<MediaFile?>?,
                                y: DJICameraError?
                            ) {
                                currentDeleteIndex++

                                Log.d(
                                    "BANANAPIE",
                                    "successfully deleted file $currentDeleteIndex/$numOfOldPhotos"
                                )
                                runOnUiThread {
                                    mLoadingDialog.setText("Deleted image $currentDeleteIndex/$numOfOldPhotos")
                                }
                                val tmpProgress = (1.0 * currentDeleteIndex / numOfOldPhotos * 100).toInt()
                                mLoadingDialog.setProgress(tmpProgress)
                                if (tmpProgress == 100){
                                    val tempHandler = Handler(Looper.getMainLooper())
                                    tempHandler.postDelayed({
                                        Log.d("BANANAPIE", "last 2 sec")
                                    }, 2000)
                                }


                            }

                            //if the deletion from the SD card failed, alert the user
                            override fun onFailure(error: DJIError) {
                                Log.d("BANANAPIE", "failed to clear SD card")
                            }
                        })
                }
            }

        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            Log.d("BANANAPIE", "2 sec")
            //mLoadingDialog.dismiss()
            updateMediaFileList()
        }, 2000)
    }

    private fun updateMediaFileList() {

        DJIDemoApplication.getCameraInstance()
            ?.let { camera ->
                mMediaManager = camera.mediaManager
                mMediaManager?.let { mediaManager ->

                    //refreshing the MediaManager's file list using the connected DJI product's SD card
                    mediaManager.refreshFileListOfStorageLocation(
                        SettingsDefinitions.StorageLocation.SDCARD //file storage location
                    ) { djiError -> //checking the callback error

                        if (djiError == null) {
                            Log.d(
                                "BANANAPIE",
                                "obtained media data from SD card (FlightPlanActivity)"
                            )
                            //updating the mediaFileList using the now refreshed MediaManager's file list
                            mediaManager.sdCardFileListSnapshot?.let { listOfMedia ->
                                mediaFileList = listOfMedia
                            }
                            if (mediaFileList.isEmpty()) {
                                Log.d("BANANAPIE", "SD card is empty, there's nothing to clear")
                                mLoadingDialog.dismiss()
                                mapTouch = true
                            } else {
                                deleteOneFileFromList()
                            }
                        } else {
                            Log.d(
                                "BANANAPIE",
                                "could not obtain media data from SD card (FlightPlanActivity)"
                            )
                            updateMediaFileList()
                        }

                    }
                }
            }
    }

    private fun clearSDCard() {
        Log.d("BANANAPIE", "attempting to clear SD card")

        DJIDemoApplication.getCameraInstance()
            ?.let { camera ->
                mMediaManager = camera.mediaManager
                mMediaManager?.let { mediaManager ->

                    //refreshing the MediaManager's file list using the connected DJI product's SD card
                    mediaManager.refreshFileListOfStorageLocation(
                        SettingsDefinitions.StorageLocation.SDCARD //file storage location
                    ) { djiError -> //checking the callback error

                        if (djiError == null) {
                            Log.d(
                                "BANANAPIE",
                                "obtained media data from SD card (FlightPlanActivity) - 1"
                            )
                            mLoadingDialog.show(this.supportFragmentManager, "tagCanBeWhatever")

                            //updating the mediaFileList using the now refreshed MediaManager's file list
                            mediaManager.sdCardFileListSnapshot?.let { listOfMedia ->
                                numOfOldPhotos = listOfMedia.size
                            }
                            if (numOfOldPhotos != 0){
                                updateMediaFileList()
                            }
                            else{
                                Log.d("BANANAPIE", "SD card is empty, there's nothing to clear")
                                mLoadingDialog.dismiss()
                                mapTouch = true
                            }

                        } else {
                            Log.d(
                                "BANANAPIE",
                                "could not obtain media data from SD card (FlightPlanActivity)"
                            )
                            showToast(this, "could not obtain media data from SD card")
                            mapTouch = true
                        }
                    }
                }
            }

    }

    //Function that initializes the display for the videoSurface TextureView
    private fun initPreviewer() {
        val product: BaseProduct = DJIDemoApplication.getProductInstance()
            ?: return //gets an instance of the connected DJI product (null if nonexistent)
        //if DJI product is disconnected, alert the user
        if (!product.isConnected) {
            showToast(this, getString(R.string.disconnected))
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

    private fun initUI() {

        mLoadingDialog = DownloadDialog("Clearing SD card...")
        mLoadingDialog.isCancelable = false

        startFlightBtn = findViewById(R.id.start_flight_button)
        cancelFlightBtn = findViewById(R.id.cancel_flight_button)
        locateBtn = findViewById(R.id.locate_button)
        val cancelFlightPlanBtn: Button = findViewById(R.id.cancel_flight_plan_button)
//        val getStartedBtn: Button = findViewById(R.id.get_started_button)

        takeoffWidget = findViewById(R.id.takeoff_widget_flight_plan)
        layoutConfirmPlan = findViewById(R.id.ll_confirm_flight_plan)
        layoutCancelPlan = findViewById(R.id.ll_cancel_flight_plan)

//        overlayView = findViewById(R.id.map_help_overlay)
        videoView = findViewById(R.id.video_view)

        videoSurface = findViewById(R.id.video_previewer_surface)
        cameraBtn = findViewById(R.id.camera_button)


        //Giving videoSurface a listener that checks for when a surface texture is available.
        //The videoSurface will then display the surface texture, which in this case is a camera video stream.
        videoSurface.surfaceTextureListener = this
        //TODO change this to implement SDKManager or something better
        vM.aircraft = DJIDemoApplication.getProductInstance() as Aircraft

        locateBtn.setOnClickListener(this)
//        getStartedBtn.setOnClickListener(this)
        startFlightBtn.setOnClickListener(this)
        cancelFlightPlanBtn.setOnClickListener(this)
        cancelFlightBtn.setOnClickListener(this)
        cameraBtn.setOnClickListener(this)

    }

    private fun cameraUpdate() {
        if (vM.droneLocation.latitude.isNaN() || vM.droneLocation.longitude.isNaN()) {
            return
        }
        val pos = LatLng(vM.droneLocation.latitude, vM.droneLocation.longitude)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        runOnUiThread {
            vM.mbMap?.moveCamera(cameraUpdate)
        }
    }

    private fun configureFlightPlan(symbol: Symbol) {

        vM.symbols.add(symbol)

        val point = symbol.latLng
        var minDistanceIndex = -1
        var nextIndex: Int

        startFlightBtn.visibility = View.GONE

        if (vM.polygonCoordinates.size < 3) { // Only Draw Area of Flight Plan
            vM.polygonCoordinates.add(point)
            updatePoly()
        } else { // Draw Area and Configure Flight Plan
            var minDistance = Double.MAX_VALUE

            for (i in vM.polygonCoordinates.indices) {
                // place new coordinate in list such that the distance between it and the line segment
                // created by its neighbouring coordinates are minimized

                nextIndex = if (i == vM.polygonCoordinates.size - 1) {
                    0
                } else {
                    i + 1
                }

                val distance: Double =
                    distanceToSegment(
                        vM.polygonCoordinates[i],
                        vM.polygonCoordinates[nextIndex],
                        point
                    )

                if (distance < minDistance) {
                    minDistance = distance
                    minDistanceIndex = i + 1
                }
            }

            vM.polygonCoordinates.add(minDistanceIndex, point)
            updatePoly()

            try {
                drawFlightPlan()
            } catch (ignored: Exception) {
            }
        }

        layoutConfirmPlan.visibility = View.VISIBLE
    }

    private fun drawFlightPlan() {

        vM.symbolManager.removeDragListener(symbolDragListener) // prevents infinite loop


        if (vM.polygonCoordinates.size < 3) return

        startFlightBtn.visibility = View.VISIBLE

        var minLat = Int.MAX_VALUE.toDouble()
        var minLon = Int.MAX_VALUE.toDouble()
        var maxLat = Int.MIN_VALUE.toDouble()
        var maxLon = Int.MIN_VALUE.toDouble()

        for (c in vM.polygonCoordinates) {
            if (c.latitude < minLat) minLat = c.latitude
            if (c.latitude > maxLat) maxLat = c.latitude
            if (c.longitude < minLon) minLon = c.longitude
            if (c.longitude > maxLon) maxLon = c.longitude
        }

        val newPoints: MutableList<LatLng> = ArrayList()

        newPoints.add(LatLng(minLat, minLon))
        newPoints.add(LatLng(minLat, maxLon))
        newPoints.add(LatLng(maxLat, maxLon))
        newPoints.add(LatLng(maxLat, minLon))

        try {

            vM.flightPlan =
                FlightPlanner.createFlightPlan(
                    newPoints,
                    35.0f,
                    vM.polygonCoordinates
                ) // get plan

            vM.flightPlanLine?.let { line -> // delete on plan on map and reset arrays
                vM.lineManager.delete(line)
                vM.symbolManager.delete(vM.flightPathSymbols)
                vM.flightPlan2D.clear()
                vM.flightPathSymbols.clear()
            }

            for (coordinate in vM.flightPlan) { // populate new plan and place markers on map
                vM.flightPlan2D.add(
                    LocationCoordinate2D(
                        coordinate.latitude,
                        coordinate.longitude
                    )
                )

                val flightPathSymbol: Symbol = vM.symbolManager.create(
                    SymbolOptions()
                        .withLatLng(LatLng(coordinate))
                        .withIconImage("ic_waypoint_marker_unvisited")
                        .withIconSize(0.5f)
                )

                vM.flightPathSymbols.add(flightPathSymbol)
            }


            val lineOptions: LineOptions = LineOptions()
                .withLatLngs(vM.flightPlan)
                .withLineWidth(2.0f)
                .withLineColor(PropertyFactory.fillColor(Color.parseColor("#FFFFFF")).value)

            vM.flightPlanLine = vM.lineManager.create(lineOptions)

        } catch (e: FlightPlanner.NotEnoughPointsException) {

            showToast(
                this@FlightPlanActivity,
                "Could not create flight plan! Try a larger area."
            )

            clearMapViews()
            showOriginalControls()
            e.printStackTrace()
        }

        vM.symbolManager.addDragListener(symbolDragListener)
    }

    private fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
        return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
    }

    // Redraw polygons fill after having changed the container values.
    private fun updatePoly() {

        val polygonCoordinatesCopy: MutableList<LatLng> = ArrayList(vM.polygonCoordinates)
        polygonCoordinatesCopy.add(vM.polygonCoordinates[0])

        vM.line?.let { vM.lineManager.delete(it) }

        val lineOpts: LineOptions = LineOptions()
            .withLineColor(PropertyFactory.lineColor("#FFFFFF").value)
            .withLatLngs(polygonCoordinatesCopy)

        vM.line = vM.lineManager.create(lineOpts)

        val fillCoordinates: MutableList<List<LatLng>> = ArrayList()
        fillCoordinates.add(polygonCoordinatesCopy)

        vM.fill?.let { vM.fillManager.delete(it) }

        val fillOpts: FillOptions = FillOptions()
            .withFillColor(PropertyFactory.fillColor(Color.parseColor("#81FFFFFF")).value)
            .withLatLngs(fillCoordinates)

        vM.fill = vM.fillManager.create(fillOpts)
    }

    private fun updateDroneLocation() {
        runOnUiThread {

            if (vM.droneLocation.latitude.isNaN() || vM.droneLocation.longitude.isNaN()) {
                return@runOnUiThread
            }

            if (checkGpsCoordination(
                    vM.droneLocation.latitude,
                    vM.droneLocation.longitude
                )
            ) {

                val pos =
                    LatLng(vM.droneLocation.latitude, vM.droneLocation.longitude)

                vM.droneSymbol?.let { drone ->
                    vM.symbolManager.delete(drone)
                }

                vM.droneSymbol = vM.symbolManager.create(
                    SymbolOptions()
                        .withLatLng(pos)
                        .withIconImage("ic_drone")
                        .withDraggable(false)
                )
            }

        }
    }

    fun animateNextButton() {
        runOnUiThread {
            takeoffWidget.visibility = View.VISIBLE
            cancelFlightBtn.visibility = View.INVISIBLE
            clearMapViews()
        }
    }

    private fun showOriginalControls() {
        layoutCancelPlan.visibility = View.GONE
        layoutConfirmPlan.visibility = View.GONE
    }

    private fun clearMapViews() {
        if (vM.symbols.size > 0) {
            vM.symbolManager.delete(vM.symbols)
        }

        if (vM.flightPathSymbols.size > 0) {
            vM.symbolManager.delete(vM.flightPathSymbols)
        }

        if (vM.flightPlanLine != null) {
            vM.lineManager.delete(vM.flightPlanLine)
        }

        vM.fillManager.delete(vM.fill)
        vM.lineManager.delete(vM.line)

        vM.symbols.clear()
        vM.polygonCoordinates.clear()
        vM.flightPlan2D.clear()
    }

    //When a TextureView's SurfaceTexture is ready for use, use it to initialize the codecManager
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (vM.codecManager == null) {
            vM.codecManager = DJICodecManager(this, surface, width, height)
        }

        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            vM.TEXT_SIZE_DIP,
            resources.displayMetrics
        )
        vM.borderedText = BorderedText(textSizePx)
        vM.borderedText!!.setTypeface(Typeface.MONOSPACE)

        vM.tracker = MultiBoxTracker(this)

        val cropSize: Int =
            vM.TF_OD_API_INPUT_SIZE

        try {
            vM.detector = YoloV4Classifier.create(
                assets,
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
                applicationContext, "Classifier could not be initialized", Toast.LENGTH_SHORT
            ).show()
            finish()
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

        trackingOverlay = findViewById(R.id.tracking_overlay)
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
        runOnUiThread{
            trackingOverlay.postInvalidate()

            vM.croppedBitmap = Bitmap.createScaledBitmap(bitmap, 416, 416, false)

            // For examining the actual TF input.
            if (vM.SAVE_PREVIEW_BITMAP) {
                vM.croppedBitmap?.let { ImageUtils.saveBitmap(it, "photo", applicationContext) }
            }
        }

        val startTime = SystemClock.uptimeMillis()
        vM.results = vM.detector.recognizeImage(vM.croppedBitmap) as MutableList<Classifier.Recognition>?
        Log.e("BANANAPIE", "run: " + (vM.results)?.size)
        vM.lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime

        runOnUiThread{
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


}