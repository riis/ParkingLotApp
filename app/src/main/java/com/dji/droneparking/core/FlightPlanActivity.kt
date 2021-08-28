package com.dji.droneparking.core


import android.content.Context
import android.content.DialogInterface.BUTTON_NEGATIVE
import android.content.DialogInterface.BUTTON_POSITIVE
import android.content.Intent
import android.graphics.*
import android.os.*
import android.util.Log
import android.util.TypedValue
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.dji.droneparking.R
import com.dji.droneparking.customview.OverlayView
import com.dji.droneparking.dialog.LoadingDialog
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
import com.mapbox.mapboxsdk.plugins.annotation.*New
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
import dji.ux.widget.ReturnHomeWidget
import dji.ux.widget.TakeOffWidget
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*
import kotlin.system.measureTimeMillis


class FlightPlanActivity : AppCompatActivity(), OnMapReadyCallback,
    MapboxMap.OnMapClickListener, View.OnClickListener, TextureView.SurfaceTextureListener {

    private val vM: FlightPlanActivityViewModel by viewModels()

    private var mapTouch: Boolean = false

    private lateinit var cancelFlightBtn: Button
    private lateinit var altitudeTV: TextView
    private lateinit var layoutConfirmPlan: LinearLayout
    private lateinit var layoutCancelPlan: LinearLayout
    private lateinit var startFlightBtn: Button
    private lateinit var locateBtn: Button
    private lateinit var photosBtn: Button
    private lateinit var takeoffWidget: TakeOffWidget
    private lateinit var returnHomeWidget: ReturnHomeWidget
    private lateinit var mContext: Context
    private lateinit var cameraBtn: Button
    private lateinit var mapBtn: Button
    private lateinit var videoSurface: TextureView
    private lateinit var videoView: CardView
    private lateinit var gimbal: Gimbal
    private lateinit var trackingOverlay: OverlayView
    private lateinit var statusCard: CardView

    private lateinit var mLoadingDialog: LoadingDialog

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
        setTheme(R.style.Theme_DroneParking)
        operator = vM.getWaypointMissionOperator(this)

        setContentView(R.layout.activity_flight_plan_mapbox)

        initUI()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mContext = applicationContext
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))

        vM.mapFragment =
            supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        vM.mapFragment.onCreate(savedInstanceState)
        vM.mapFragment.getMapAsync(this)



        operator.droneLocationLiveData.observe(this, { location ->
            altitudeTV.text = "${"%.1f".format(location.altitude)}\nm"

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
        showClearMemoryDialog()
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

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate_button -> {
                updateDroneLocation()
                cameraUpdate()
            }

            R.id.photos_button -> {
                getPhotoStitcher()
            }

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
                mapTouch = true
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
                    photosBtn.visibility = View.GONE
                    cameraBtn.visibility = View.GONE
                    mapBtn.visibility = View.VISIBLE
                    statusCard.visibility = View.GONE
                    takeoffWidget.visibility = View.GONE
                    returnHomeWidget.visibility = View.GONE


                } else {
                    videoView.visibility = View.GONE
                    locateBtn.visibility = View.VISIBLE
                    photosBtn.visibility = View.VISIBLE
                    cameraBtn.visibility = View.VISIBLE
                    mapBtn.visibility = View.GONE
                    statusCard.visibility = View.VISIBLE
                    takeoffWidget.visibility = View.VISIBLE
                    returnHomeWidget.visibility = View.VISIBLE

                }
            }
            R.id.map_button -> {
                vM.isCameraShowing = !vM.isCameraShowing

                if (vM.isCameraShowing) {
                    videoView.visibility = View.VISIBLE
                    locateBtn.visibility = View.GONE
                    photosBtn.visibility = View.GONE
                    cameraBtn.visibility = View.GONE
                    mapBtn.visibility = View.VISIBLE
                    statusCard.visibility = View.GONE
                    takeoffWidget.visibility = View.GONE
                    returnHomeWidget.visibility = View.GONE


                } else {
                    videoView.visibility = View.GONE
                    locateBtn.visibility = View.VISIBLE
                    photosBtn.visibility = View.VISIBLE
                    cameraBtn.visibility = View.VISIBLE
                    mapBtn.visibility = View.GONE
                    statusCard.visibility = View.VISIBLE
                    takeoffWidget.visibility = View.VISIBLE
                    returnHomeWidget.visibility = View.VISIBLE
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
            .setNegativeButton(R.string.no) { _, _ -> mapTouch = true  }
            .setPositiveButton(R.string.yes) { _, _ ->

                val t  = measureTimeMillis {
                    clearSDCard()
                }
                Log.d("BANANAPIE", "SD card cleared in $t ms")

            }
            .create()

        dialog.show()
        dialog.getButton(BUTTON_POSITIVE).setTextColor(resources.getColor(R.color.green))
        dialog.getButton(BUTTON_NEGATIVE).setTextColor(resources.getColor(R.color.red))
    }

    private fun clearSDCard() {
        Log.d("BANANAPIE", "attempting to update the media file list from the SD card")
        mLoadingDialog.show(this.supportFragmentManager, "tagCanBeWhatever")
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
                            camera.formatSDCard { DJIError ->
                                if (DJIError == null) {
                                    Log.d(
                                        "BANANAPIE",
                                        "successfully cleared SD card"
                                    )
                                    mLoadingDialog.dismiss()
                                    mapTouch = true

                                } else {
                                    Log.d(
                                        "BANANAPIE",
                                        "SD Card clear error: $DJIError"
                                    )
                                    if (DJIError.toString() == "Not supported(255)"){
                                        showToast(this, "SD Card clear error: $DJIError. Restart Drone.")
                                    }
                                    else{
                                        showToast(this, "SD Card clear error: $DJIError")
                                    }

                                    mLoadingDialog.dismiss()
                                }
                            }
                        } else {
                            Log.d(
                                "BANANAPIE",
                                "could not obtain media data from SD card (FlightPlanActivity)"
                            )
                            showToast(this, "could not obtain media data from SD card.\n.Check to see if SD card is available.")
                        }
                    }
                }
            }
    }

    private fun getPhotoStitcher() {
        val intent = Intent(this, PhotoStitcherActivity::class.java)
        this.startActivity(intent)
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

        mLoadingDialog = LoadingDialog("Clearing SD card...")
        mLoadingDialog.isCancelable = false

        statusCard = findViewById(R.id.status_card)
        altitudeTV = findViewById(R.id.altitudeTV)

        startFlightBtn = findViewById(R.id.start_flight_button)
        cancelFlightBtn = findViewById(R.id.cancel_flight_button)
        locateBtn = findViewById(R.id.locate_button)
        photosBtn = findViewById(R.id.photos_button)
        mapBtn = findViewById(R.id.map_button)
        val cancelFlightPlanBtn: Button = findViewById(R.id.cancel_flight_plan_button)

        takeoffWidget = findViewById(R.id.takeoff_widget_flight_plan)
        returnHomeWidget = findViewById(R.id.return_home_widget)
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
        vM.aircraft = DJIDemoApplication.getProductInstance() as Aircraft?

        locateBtn.setOnClickListener(this)
        photosBtn.setOnClickListener(this)

        startFlightBtn.setOnClickListener(this)
        cancelFlightPlanBtn.setOnClickListener(this)
        cancelFlightBtn.setOnClickListener(this)
        cameraBtn.setOnClickListener(this)
        mapBtn.setOnClickListener(this)

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
        }

    }

}