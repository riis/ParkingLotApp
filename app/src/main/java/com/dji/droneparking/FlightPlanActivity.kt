package com.dji.droneparking


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.dji.droneparking.util.*
import com.dji.droneparking.util.Tools.showToast
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.*
import com.mapbox.mapboxsdk.plugins.annotation.*
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.riis.cattlecounter.util.distanceToSegment
import dji.common.camera.SettingsDefinitions
import dji.common.mission.waypoint.WaypointMission
import dji.common.model.LocationCoordinate2D
import dji.common.product.Model
import dji.sdk.base.BaseProduct
import dji.common.gimbal.*
import dji.sdk.gimbal.Gimbal
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import dji.ux.widget.TakeOffWidget
//import org.tensorflow.lite.support.image.TensorImage
//import org.tensorflow.lite.task.vision.detector.Detection
//import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.*

class FlightPlanActivity : AppCompatActivity(), OnMapReadyCallback,
    MapboxMap.OnMapClickListener, View.OnClickListener, TextureView.SurfaceTextureListener
     { //,OnImageAvailableListener

    private val viewModel: FlightPlanActivityViewModel by viewModels()


    private lateinit var cancelFlightBtn: Button
    private lateinit var overlayView: LinearLayout
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


    private val symbolDragListener = object : OnSymbolDragListener {
        override fun onAnnotationDrag(symbol: Symbol) {}

        override fun onAnnotationDragFinished(symbol: Symbol) {
            configureFlightPlan(symbol)
        }

        override fun onAnnotationDragStarted(symbol: Symbol) {

            viewModel.symbols.remove(symbol)
            viewModel.polygonCoordinates.remove(symbol.latLng)

        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.operator = MavicMiniMissionOperator(this)

        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
        try {
            gimbal = DJISDKManager.getInstance().product.gimbal

            gimbal.rotate(
                rotation
            ) { djiError ->
                if (djiError == null) {
                    Log.d("BANANAPIE", "rotate gimbal success")
                    Toast.makeText(applicationContext, "rotate gimbal success", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Log.d("BANANAPIE", "rotate gimbal error " + djiError.description)
                    Toast.makeText(applicationContext, djiError.description, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        } catch (e: Exception) {
            Log.d("BANANAPIE", "Drone is likely not connected")
        }



        setContentView(R.layout.activity_flight_plan_mapbox)

        initUI()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mContext = applicationContext
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))

        viewModel.mapFragment =
            supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        viewModel.mapFragment.onCreate(savedInstanceState)
        viewModel.mapFragment.getMapAsync(this)

        //TODO implement the DJI Image Downloader Class

        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.ensure_clear_sd)
            .setTitle(R.string.title_clear_sd)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ -> /*downloader.cleanDrone()*/ }
            // TODO: yes will crash app since downloader not initialized yet
            .create()

        dialog.show()

        viewModel.operator!!.droneLocationLiveData.observe(this, { location ->

            if (viewModel.styleReady) {

                viewModel.droneLocation = location

                if (!viewModel.located) {
                    cameraUpdate()
                    viewModel.located = true
                }

                updateDroneLocation()

            }
        })

        // The receivedVideoDataListener receives the raw video data and the size of the data from the DJI product.
        // It then sends this data to the codec manager for decoding.
        viewModel.receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            viewModel.codecManager?.sendDataToDecoder(videoBuffer, size)
        }
    }

    override fun onMapReady(mapboxMap: MapboxMap) {

        viewModel.mbMap = mapboxMap

        mapboxMap.setStyle(Style.SATELLITE_STREETS) { style: Style ->

            viewModel.styleReady = true
            var bm: Bitmap? =
                Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_waypoint_marker_unvisited)
            bm?.let { mapboxMap.style?.addImage("ic_waypoint_marker_unvisited", it) }

            bm = Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_drone)
            bm?.let { mapboxMap.style?.addImage("ic_drone", it) }

            viewModel.fillManager =
                FillManager(viewModel.mapFragment.view as MapView, mapboxMap, style)
            viewModel.lineManager =
                LineManager(viewModel.mapFragment.view as MapView, mapboxMap, style)

            viewModel.symbolManager =
                SymbolManager(viewModel.mapFragment.view as MapView, mapboxMap, style)
            viewModel.symbolManager.iconAllowOverlap = true
            viewModel.symbolManager.addDragListener(symbolDragListener)


        }

        val position = CameraPosition.Builder()
            .zoom(18.0)
            .build()

        mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(position))

        viewModel.mbMap?.addOnMapClickListener(this)
    }

    override fun onMapClick(point: LatLng): Boolean {

        val symbol = viewModel.symbolManager.create(
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

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate_button -> {
                updateDroneLocation()
                cameraUpdate()
            }
            R.id.get_started_button -> {
                overlayView.animate()
                    .alpha(0.0f)
                    .translationXBy(1000f)
                    .setDuration(500)
                    .setListener(null)
                    .start()

                locateBtn.visibility = View.VISIBLE
                cameraBtn.visibility = View.VISIBLE
            }
            R.id.start_flight_button -> {
                when (viewModel.aircraft) {
                    null -> {
                        Toast.makeText(mContext, "Please connect to a drone", Toast.LENGTH_SHORT)
                            .show()
                    }
                    else -> {

                        val djiMission: WaypointMission =
                            FlightPlanner.createFlightMissionFromCoordinates(viewModel.flightPlan2D)

                        viewModel.manager = viewModel.operator?.let {
                            WaypointMissionManager(
                                djiMission,
                                it,
                                findViewById(R.id.label_flight_plan),
                                this@FlightPlanActivity
                            )
                        }

                        viewModel.manager?.startMission()
                        layoutConfirmPlan.visibility = View.GONE
                        layoutCancelPlan.visibility = View.VISIBLE
                    }
                }
            }
            R.id.cancel_flight_button -> {
                viewModel.manager?.stopFlight() ?: return
                clearMapViews()
                layoutCancelPlan.visibility = View.GONE
            }
            R.id.cancel_flight_plan_button -> {
                layoutConfirmPlan.visibility = View.GONE
                clearMapViews()
            }
            R.id.camera_button -> {
                viewModel.isCameraShowing = !viewModel.isCameraShowing

                if (viewModel.isCameraShowing) {
                    videoView.visibility = View.VISIBLE
                    locateBtn.visibility = View.GONE
                    cameraBtn.text = "map"

                } else {
                    videoView.visibility = View.GONE
                    locateBtn.visibility = View.VISIBLE
                    cameraBtn.text = "camera"
                }
            }
        }
    }

    //When the MainActivity is created or resumed, initialize the video feed display
    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    //When a TextureView's SurfaceTexture is ready for use, use it to initialize the codecManager
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (viewModel.codecManager == null) {
            viewModel.codecManager = DJICodecManager(this, surface, width, height)
        }
    }

    //When a SurfaceTexture is updated
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    //when a SurfaceTexture's size changes
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    //when a SurfaceTexture is about to be destroyed, uninitialized the codedManager
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        viewModel.codecManager?.cleanSurface()
        viewModel.codecManager = null

        return false
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

    private fun switchCameraMode(cameraMode: SettingsDefinitions.CameraMode) {
        val camera: Camera = DJIDemoApplication.getCameraInstance() ?: return

        camera.setMode(cameraMode) { error ->
            if (error == null) {
                showToast(this, "Switch Camera Mode Succeeded")
            } else {
                showToast(this, "Switch Camera Error: ${error.description}")
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
                    viewModel.receivedVideoDataListener
                )
            }
        }
    }

    private fun initUI() {

        startFlightBtn = findViewById(R.id.start_flight_button)
        cancelFlightBtn = findViewById(R.id.cancel_flight_button)
        locateBtn = findViewById(R.id.locate_button)
        val cancelFlightPlanBtn: Button = findViewById(R.id.cancel_flight_plan_button)
        val getStartedBtn: Button = findViewById(R.id.get_started_button)

        takeoffWidget = findViewById(R.id.takeoff_widget_flight_plan)
        layoutConfirmPlan = findViewById(R.id.ll_confirm_flight_plan)
        layoutCancelPlan = findViewById(R.id.ll_cancel_flight_plan)

        overlayView = findViewById(R.id.map_help_overlay)
        videoView = findViewById(R.id.video_view)

        videoSurface = findViewById(R.id.video_previewer_surface)
        cameraBtn = findViewById(R.id.camera_button)

        //switchCameraMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO)

        //Giving videoSurface a listener that checks for when a surface texture is available.
        //The videoSurface will then display the surface texture, which in this case is a camera video stream.
        videoSurface.surfaceTextureListener = this
        //TODO change this to implement SDKManager or something better
        viewModel.aircraft = DJIDemoApplication.getProductInstance() as Aircraft

        locateBtn.setOnClickListener(this)
        getStartedBtn.setOnClickListener(this)
        startFlightBtn.setOnClickListener(this)
        cancelFlightPlanBtn.setOnClickListener(this)
        cancelFlightBtn.setOnClickListener(this)
        cameraBtn.setOnClickListener(this)

    }

    private fun cameraUpdate() {
        if (viewModel.droneLocation.latitude.isNaN() || viewModel.droneLocation.longitude.isNaN()) {
            return
        }
        val pos = LatLng(viewModel.droneLocation.latitude, viewModel.droneLocation.longitude)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        runOnUiThread {
            viewModel.mbMap?.moveCamera(cameraUpdate)
        }
    }

    private fun configureFlightPlan(symbol: Symbol) {

        viewModel.symbols.add(symbol)

        val point = symbol.latLng
        var minDistanceIndex = -1
        var nextIndex: Int

        startFlightBtn.visibility = View.GONE

        if (viewModel.polygonCoordinates.size < 3) { // Only Draw Area of Flight Plan
            viewModel.polygonCoordinates.add(point)
            updatePoly()
        } else { // Draw Area and Configure Flight Plan
            var minDistance = Double.MAX_VALUE

            for (i in viewModel.polygonCoordinates.indices) {
                // place new coordinate in list such that the distance between it and the line segment
                // created by its neighbouring coordinates are minimized

                nextIndex = if (i == viewModel.polygonCoordinates.size - 1) {
                    0
                } else {
                    i + 1
                }

                val distance: Double =
                    distanceToSegment(
                        viewModel.polygonCoordinates[i],
                        viewModel.polygonCoordinates[nextIndex],
                        point
                    )

                if (distance < minDistance) {
                    minDistance = distance
                    minDistanceIndex = i + 1
                }
            }

            viewModel.polygonCoordinates.add(minDistanceIndex, point)
            updatePoly()

            try {
                drawFlightPlan()
            } catch (ignored: Exception) {
            }
        }

        layoutConfirmPlan.visibility = View.VISIBLE
    }

    private fun drawFlightPlan() {

        viewModel.symbolManager.removeDragListener(symbolDragListener) // prevents infinite loop


        if (viewModel.polygonCoordinates.size < 3) return

        startFlightBtn.visibility = View.VISIBLE

        var minLat = Int.MAX_VALUE.toDouble()
        var minLon = Int.MAX_VALUE.toDouble()
        var maxLat = Int.MIN_VALUE.toDouble()
        var maxLon = Int.MIN_VALUE.toDouble()

        for (c in viewModel.polygonCoordinates) {
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

            viewModel.flightPlan =
                FlightPlanner.createFlightPlan(
                    newPoints,
                    95.0f,
                    viewModel.polygonCoordinates
                ) // get plan

            viewModel.flightPlanLine?.let { line -> // delete on plan on map and reset arrays
                viewModel.lineManager.delete(line)
                viewModel.symbolManager.delete(viewModel.flightPathSymbols)
                viewModel.flightPlan2D.clear()
                viewModel.flightPathSymbols.clear()
            }

            for (coordinate in viewModel.flightPlan) { // populate new plan and place markers on map
                viewModel.flightPlan2D.add(
                    LocationCoordinate2D(
                        coordinate.latitude,
                        coordinate.longitude
                    )
                )

                val flightPathSymbol: Symbol = viewModel.symbolManager.create(
                    SymbolOptions()
                        .withLatLng(LatLng(coordinate))
                        .withIconImage("ic_waypoint_marker_unvisited")
                        .withIconSize(0.5f)
                )

                viewModel.flightPathSymbols.add(flightPathSymbol)
            }


            val lineOptions: LineOptions = LineOptions()
                .withLatLngs(viewModel.flightPlan)
                .withLineWidth(2.0f)
                .withLineColor(PropertyFactory.fillColor(Color.parseColor("#FFFFFF")).value)

            viewModel.flightPlanLine = viewModel.lineManager.create(lineOptions)

        } catch (e: FlightPlanner.NotEnoughPointsException) {

            showToast(
                this@FlightPlanActivity,
                "Could not create flight plan! Try a larger area."
            )

            clearMapViews()
            showOriginalControls()
            e.printStackTrace()
        }

        viewModel.symbolManager.addDragListener(symbolDragListener)
    }

    private fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
        return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
    }

    // Redraw polygons fill after having changed the container values.
    private fun updatePoly() {

        val polygonCoordinatesCopy: MutableList<LatLng> = ArrayList(viewModel.polygonCoordinates)
        polygonCoordinatesCopy.add(viewModel.polygonCoordinates[0])

        viewModel.line?.let { viewModel.lineManager.delete(it) }

        val lineOpts: LineOptions = LineOptions()
            .withLineColor(PropertyFactory.lineColor("#FFFFFF").value)
            .withLatLngs(polygonCoordinatesCopy)

        viewModel.line = viewModel.lineManager.create(lineOpts)

        val fillCoordinates: MutableList<List<LatLng>> = ArrayList()
        fillCoordinates.add(polygonCoordinatesCopy)

        viewModel.fill?.let { viewModel.fillManager.delete(it) }

        val fillOpts: FillOptions = FillOptions()
            .withFillColor(PropertyFactory.fillColor(Color.parseColor("#81FFFFFF")).value)
            .withLatLngs(fillCoordinates)

        viewModel.fill = viewModel.fillManager.create(fillOpts)
    }

    private fun updateDroneLocation() {
        runOnUiThread {

            if (viewModel.droneLocation.latitude.isNaN() || viewModel.droneLocation.longitude.isNaN()) {
                return@runOnUiThread
            }

            if (checkGpsCoordination(
                    viewModel.droneLocation.latitude,
                    viewModel.droneLocation.longitude
                )
            ) {

                val pos =
                    LatLng(viewModel.droneLocation.latitude, viewModel.droneLocation.longitude)

                viewModel.droneSymbol?.let { drone ->
                    viewModel.symbolManager.delete(drone)
                }

                viewModel.droneSymbol = viewModel.symbolManager.create(
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
        if (viewModel.symbols.size > 0) {
            viewModel.symbolManager.delete(viewModel.symbols)
        }

        if (viewModel.flightPathSymbols.size > 0) {
            viewModel.symbolManager.delete(viewModel.flightPathSymbols)
        }

        if (viewModel.flightPlanLine != null) {
            viewModel.lineManager.delete(viewModel.flightPlanLine)
        }

        viewModel.fillManager.delete(viewModel.fill)
        viewModel.lineManager.delete(viewModel.line)

        viewModel.symbols.clear()
        viewModel.polygonCoordinates.clear()
        viewModel.flightPlan2D.clear()
    }

    /*
    override fun onImageAvailable(reader: ImageReader?) {
        videoSurface.bitmap
    }

    /**
     * runObjectDetection(bitmap: Bitmap)
     *      TFLite Object Detection function
     */
    private fun runObjectDetection(bitmap: Bitmap) {
        // Step 1: create TFLite's TensorImage object
        val image = TensorImage.fromBitmap(bitmap)

        // Step 2: Initialize the detector object
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setMaxResults(5)
            .setScoreThreshold(0.5f)
            .build()
        val detector = ObjectDetector.createFromFileAndOptions(
            this, // the application context
            "model.tflite", // must be same as the filename in assets folder
            options
        )

        // Step 3: feed given image to the model and print the detection result
        val results = detector.detect(image)

        // Step 4: Parse the detection result and show it
        debugPrint(results)
//        val resultToDisplay = results.map {
//            // Get the top-1 category and craft the display text
//            val category = it.categories.first()
//            val text = "${category.label}, ${category.score.times(100).toInt()}%"
//
//            // Create a data object to display the detection result
//            DetectionResult(it.boundingBox, text)
//        }
//        // Draw the detection result on the bitmap and show it.
//        val imgWithResult = drawDetectionResult(bitmap, resultToDisplay)
//        runOnUiThread {
//            inputImageView.setImageBitmap(imgWithResult)
//        }
    }


    private fun debugPrint(results: List<Detection>) {
        for ((i, obj) in results.withIndex()) {
            val box = obj.boundingBox

            Log.d("BANANAPIE", "Detected object: ${i} ")
            Log.d("BANANAPIE", "  boundingBox: (${box.left}, ${box.top}) - (${box.right},${box.bottom})")

            for ((j, category) in obj.categories.withIndex()) {
                Log.d("BANANAPIE", "    Label $j: ${category.label}")
                val confidence: Int = category.score.times(100).toInt()
                Log.d("BANANAPIE", "    Confidence: ${confidence}%")
            }
        }
    }
    */


}