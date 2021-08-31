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
import android.widget.*
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
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
import dji.ux.widget.ReturnHomeWidget
import dji.ux.widget.TakeOffWidget
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*
import kotlin.system.measureTimeMillis

/**
 * This is the third view the user will see in the app and is the most important activity. Its purpose is to
 * display the drone's location on a map, allow the user to create a custom flight plan and mission for the drone to
 * follow, and to allow the user to view the drone's camera video feed. The user can also navigate to PhotoStitcherActivity.kt
 * from this activity.
 */
class FlightPlanActivity : AppCompatActivity(), OnMapReadyCallback,
    MapboxMap.OnMapClickListener, View.OnClickListener, TextureView.SurfaceTextureListener {

    //Class variables
    private lateinit var cancelFlightBtn: Button
    private lateinit var cancelFlightPlanBtn: Button
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
    private lateinit var trackingOverlay: OverlayView
    private lateinit var statusCard: CardView
    private lateinit var mLoadingDialog: LoadingDialog
    private var mapTouch: Boolean = false
    private var mMediaManager: MediaManager? = null
    private lateinit var operator: MavicMiniMissionOperator

    private val vM: FlightPlanActivityViewModel by viewModels() //viewModel

    //Creating the activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_DroneParking)

        operator = vM.getWaypointMissionOperator(this) //getting an instance of the MavicMiniMissionOperator.kt class

        //activity layout view is created from activity_flight_plan_mapbox.xml
        setContentView(R.layout.activity_flight_plan_mapbox)

        initUI()

        //if the current build version is at least API level 30 (Android 11), hide the phone's status bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            //Otherwise, force the activity to show as full screen
            @Suppress("DEPRECATION")
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) //keeps the device's screen turned on and bright

        //Creating an instance of Mapbox, passing it the app's context and an API key
        mContext = applicationContext
        Mapbox.getInstance(mContext, getString(R.string.mapbox_api_key))

        //referencing the Mapbox view as a SupportMapFragment
        vM.mapFragment =
            supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        vM.mapFragment.onCreate(savedInstanceState) //initializing the map fragment from its last saved state (can be null)
        vM.mapFragment.getMapAsync(this) //setting a callback for when the map view is ready for use

        //setting up an observer to listen to changes to the drone's real-time location from the operator instance
        operator.droneLocationLiveData.observe(this, { location ->
            altitudeTV.text = "${"%.1f".format(location.altitude)}\nm" //display the drone's altitude in the UI

            if (vM.styleReady) { //if the Mapbox view already has its style set...

                vM.droneLocation = location //save the current location to the viewModel

                if (!vM.located) { //If the map does not have its camera view set to a location,
                    cameraUpdate() //... set it to the drone's current location.
                    vM.located = true
                }
                updateDroneLocation() //updates the drone's symbol position on the map
            }
        })

        //The viewModel's receivedVideoDataListener receives the raw video data from the DJI drone's camera
        //and then sends it to the viewModel's codec manager for decoding.
        vM.receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            vM.codecManager?.sendDataToDecoder(videoBuffer, size)
        }

        showClearMemoryDialog() //display dialog giving the user a choice to clear the drone's SD card
    }

    //Function used to initialize all UI elements
    private fun initUI() {

        //initializing a LoadingDialog to display a loading screen (used when clearing the SD card)
        mLoadingDialog = LoadingDialog("Clearing SD card...")
        mLoadingDialog.isCancelable = false

        //referencing the layout views using their resource ids
        statusCard = findViewById(R.id.status_card)
        altitudeTV = findViewById(R.id.altitudeTV)
        startFlightBtn = findViewById(R.id.start_flight_button)
        cancelFlightBtn = findViewById(R.id.cancel_flight_button)
        locateBtn = findViewById(R.id.locate_button)
        photosBtn = findViewById(R.id.photos_button)
        mapBtn = findViewById(R.id.map_button)
        cancelFlightPlanBtn = findViewById(R.id.cancel_flight_plan_button)
        takeoffWidget = findViewById(R.id.takeoff_widget_flight_plan)
        returnHomeWidget = findViewById(R.id.return_home_widget)
        layoutConfirmPlan = findViewById(R.id.ll_confirm_flight_plan)
        layoutCancelPlan = findViewById(R.id.ll_cancel_flight_plan)
        videoView = findViewById(R.id.video_view)
        videoSurface = findViewById(R.id.video_previewer_surface)
        cameraBtn = findViewById(R.id.camera_button)


        //The videoSurface uses a listener to check for when a surface texture is available in this activity.
        //The videoSurface will then display the surface texture, which in this case is the drone's camera video stream.
        videoSurface.surfaceTextureListener = this

        vM.aircraft = DJIDemoApplication.getProductInstance() as Aircraft? //getting an instance of the DJI drone

        //setting a click listener for each of these buttons
        locateBtn.setOnClickListener(this)
        photosBtn.setOnClickListener(this)
        startFlightBtn.setOnClickListener(this)
        cancelFlightPlanBtn.setOnClickListener(this)
        cancelFlightBtn.setOnClickListener(this)
        cameraBtn.setOnClickListener(this)
        mapBtn.setOnClickListener(this)

    }

    //Function called when a MapboxMap is ready to be used
    override fun onMapReady(mapboxMap: MapboxMap) {

        vM.mbMap = mapboxMap //saving the map to the viewModel

        //setting the style setting of the MapboxMap to always display the satellite view
        mapboxMap.setStyle(Style.SATELLITE_STREETS) { style: Style ->

            vM.styleReady = true

            //creating a bitmap to represent an "unvisited" waypoint marker and adding it to the MapboxMap's style
            var bm: Bitmap? =
                Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_waypoint_marker_unvisited)
            bm?.let { mapboxMap.style?.addImage("ic_waypoint_marker_unvisited", it) }

            //creating a bitmap to represent the drone and adding it to the MapboxMap's style
            bm = Tools.bitmapFromVectorDrawable(mContext, R.drawable.ic_drone)
            bm?.let { mapboxMap.style?.addImage("ic_drone", it) }

            //initializing the Mapbox UI managers using the mapboxMap and its style
            vM.fillManager =
                FillManager(vM.mapFragment.view as MapView, mapboxMap, style)
            vM.lineManager =
                LineManager(vM.mapFragment.view as MapView, mapboxMap, style)
            vM.symbolManager =
                SymbolManager(vM.mapFragment.view as MapView, mapboxMap, style)

            vM.symbolManager.iconAllowOverlap = true //allowing icon overlap
            vM.symbolManager.addDragListener(symbolDragListener)
        }

        //setting the zoom level of the map's camera view
        val position = CameraPosition.Builder()
            .zoom(18.0)
            .build()

        mapboxMap.animateCamera(CameraUpdateFactory.newCameraPosition(position))

        vM.mbMap?.addOnMapClickListener(this) //setting a click listener on the map
    }

    //listener for when a mapBox symbol is dragged on the screen
    private val symbolDragListener = object : OnSymbolDragListener {
        override fun onAnnotationDrag(symbol: Symbol) {}

        override fun onAnnotationDragFinished(symbol: Symbol) {
            //when a symbol is finished being dragged, recreate it on the map and
            // ... reconfigure the drone's flight plan using it.
            configureFlightPlan(symbol)
        }
        override fun onAnnotationDragStarted(symbol: Symbol) {
            //if a symbol is dragged on the map, remove it's symbol
            vM.symbols.remove(symbol)
            vM.polygonCoordinates.remove(symbol.latLng)
        }
    }

    //Function used to center the mapbox map view around the DJI drone's current latLng position
    private fun cameraUpdate() {
        if (vM.droneLocation.latitude.isNaN() || vM.droneLocation.longitude.isNaN()) {
            return //if the drone's location is unknown, do nothing
        }
        val pos = LatLng(vM.droneLocation.latitude, vM.droneLocation.longitude)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        runOnUiThread {
            vM.mbMap?.moveCamera(cameraUpdate)
        }
    }

    //Function called when the Mapbox map is clicked
    override fun onMapClick(point: LatLng): Boolean {

        return if (mapTouch){ //If the user is allowed to touch the map...
            //create an "unvisited" waypoint marker at the point the user touched.
            val symbol = vM.symbolManager.create(
                SymbolOptions()
                    .withLatLng(LatLng(point))
                    .withIconImage("ic_waypoint_marker_unvisited")
                    .withIconHaloWidth(2.0f)
                    .withDraggable(true)
            )
            //Add the new "unvisited" waypoint to the map and use it (along with any other ones created before) to
            // ... configure a flight plan for the drone
            configureFlightPlan(symbol)
            true
        } else{
            false
        }

    }

    //Function used to update the drone's symbol position on the Mapbox map
    private fun updateDroneLocation() {
        runOnUiThread {

            //if the drone's location is unknown, do nothing
            if (vM.droneLocation.latitude.isNaN() || vM.droneLocation.longitude.isNaN()) {
                return@runOnUiThread
            }

            //if the drone's current location is valid...
            if (checkGpsCoordination(
                    vM.droneLocation.latitude,
                    vM.droneLocation.longitude
                )
            ) {
                //get the drone's current LatLng coordinate
                val pos =
                    LatLng(vM.droneLocation.latitude, vM.droneLocation.longitude)

                //delete the old drone symbol from the map
                vM.droneSymbol?.let { drone ->
                    vM.symbolManager.delete(drone)
                }

                //recreate the drone symbol at the drone's new position
                vM.droneSymbol = vM.symbolManager.create(
                    SymbolOptions()
                        .withLatLng(pos)
                        .withIconImage("ic_drone")
                        .withDraggable(false)
                )
            }

        }
    }

    //Function that takes in a LatLng coordinate and returns true if it is a numerically valid coordinate
    private fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
        return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
    }

    //Function used to add "unvisited" waypoint markers to the map and use them to configure the drone's flight plan
    private fun configureFlightPlan(symbol: Symbol) {

        vM.symbols.add(symbol) //adding the marker to the viewModel's symbol list

        val point = symbol.latLng //get the marker's location coordinate
        var minDistanceIndex = -1
        var nextIndex: Int

        startFlightBtn.visibility = View.GONE //hide the the startFlightBtn button

        //NOTE: The viewModel's polygonCoordinates list contains coordinates which when connected together, define the outer perimeter
        // of an enclosed polygonal shape. This shape represents the total amount of area the drone will be able to cover with its camera
        // while taking pictures along its flight plan.

        //If the number of location coordinates in the viewModel's polygonCoordinates list is less than 3, add the marker's location
        // coordinate to the list, and draw the filled polygonal area of the updated flight plan.
        if (vM.polygonCoordinates.size < 3) {
            vM.polygonCoordinates.add(point)
            updatePoly()
        } else { // Draw the filled polygonal area and configure flight plan
            var minDistance = Double.MAX_VALUE

            //Place new coordinate in list such that the distance between it and the line segment
            // ... created by its neighbouring coordinates are minimized.
            for (i in vM.polygonCoordinates.indices) {

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

            updatePoly() //draw the filled polygonal area of the updated flight plan

            try {
                drawFlightPlan() //draw the drone's flight path within the filled polygon
            } catch (ignored: Exception) {
            }
        }

        layoutConfirmPlan.visibility = View.VISIBLE //show the cancelFlightPlanBtn and startFlightBtn buttons

    }

    //Function used to create a filled polygon on the map using the coordinates in vM.polygonCoordinates
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

    //Function used to create the drone's flight plan within the filled polygon. This will generate
    // ... the necessary waypoints the drone will need to stop at and take pictures in order to capture
    // ... the whole area with its camera. The coordinates of these waypoints will be stored in vM.flightPlan2D.
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

    //Function used to handle click events for each button
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onClick(v: View?) {
        when (v?.id) {
            //If the locate button (has the location marker symbol) is pressed...
            R.id.locate_button -> {
                updateDroneLocation() //update the drone's symbol location on the map
                cameraUpdate() //update the map's camera view to center around the drone's current location
            }
            //If the photos button (has the image gallery symbol) is pressed...
            R.id.photos_button -> {
                getPhotoStitcher() //navigate to PhotoStitcherActivity.kt
            }
            //If the "Start Flight" button is pressed...
            R.id.start_flight_button -> {
                when (vM.aircraft) {
                    null -> { //if the drone is not connected, prompt user to connect
                        Toast.makeText(mContext, "Please connect to a drone", Toast.LENGTH_SHORT)
                            .show()
                    }
                    else -> { //if the drone is connected, create a djiMission using the waypoint location coordinates in vM.flightPlan2D
                        val djiMission: WaypointMission =
                            FlightPlanner.createFlightMissionFromCoordinates(vM.flightPlan2D)

                        //create an instance of the WaypointMissionManager class using the new mission, the operator instance, a textview
                        // ... which will be used to display the mission status, and this activity. The manager is then saved to the viewModel.
                        vM.manager = WaypointMissionManager(
                            djiMission,
                            operator,
                            findViewById(R.id.label_flight_plan),
                            this@FlightPlanActivity
                        )

                        /*
                        NOTE: The WaypointMissionManager (vM.manager) and the MavicMiniMissionOperator (operator) work closely together
                        ... to monitor and control all aspects of flight as the drone undergoes its mission. The manager can be considered
                        ... to be the brain of operations. It sends commands to the operator which then interacts with the drone to
                        ... complete the task given to it by the manager.
                         */

                        vM.manager?.startMission() //starts the mission
                        mapTouch = false //the user is no longer able to add waypoints to the map

                        //hide the cancelFlightPlanBtn and startFlightBtn buttons, and show the cancelFlightBtn button.
                        layoutConfirmPlan.visibility = View.GONE
                        layoutCancelPlan.visibility = View.VISIBLE
                    }
                }
            }
            //If the "Cancel Flight" button is pressed...
            R.id.cancel_flight_button -> {
                vM.manager?.stopFlight() ?: return //stop the drone's flight (also stops the djiMission)
                clearMapViews() //clear the flight plan from the map
                mapTouch = true //allow the user to add waypoints to the map
                layoutCancelPlan.visibility = View.GONE //hide the cancelFlightBtn button
            }
            //If the "Cancel/Clear Mission" button is pressed...
            R.id.cancel_flight_plan_button -> {
                layoutConfirmPlan.visibility = View.GONE //hide the cancelFlightPlanBtn and startFlightBtn buttons
                clearMapViews() //clear the flight plan from the map
            }
            //if the camera button (has the video play button symbol) is pressed, show only the drone's camera
            // ... livestream and the map button.
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
            //if the map button (has the map symbol and is only visible if the drone's camera livestream is visible) is pressed,
            // ... show everything but the drone's camera livestream and the mapBtn button.
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

    //When the MainActivity is created or resumed, initialize the drone's video feed display
    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    //When the MainActivity is paused, clear the drone's video feed display
    override fun onPause() {
        uninitPreviewer()
        super.onPause()
    }

    //When the MainActivity is destroyed, clear the drone's video feed display
    override fun onDestroy() {
        uninitPreviewer()
        super.onDestroy()
    }

    //Function used to uninitialize the display for the videoSurface TextureView
    private fun uninitPreviewer() {
        DJIDemoApplication.getCameraInstance() ?: return
        VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(null)

    }

    //Function used to display a dialog giving the user a choice to clear the drone's SD card
    private fun showClearMemoryDialog() {
        val dialog = AlertDialog.Builder(this)
            .setMessage(R.string.ensure_clear_sd)
            .setCancelable(false)
            .setTitle(R.string.title_clear_sd)
            .setNegativeButton(R.string.no) { _, _ -> mapTouch = true  } //if the user selects "no", allow user to add waypoints to the map
            .setPositiveButton(R.string.yes) { _, _ ->

                val t  = measureTimeMillis {
                    clearSDCard() //if the user selects "yes", clear the drone's SD card
                }
                Log.d("BANANAPIE", "SD card cleared in $t ms")
            }
            .create()

        dialog.show()
        dialog.getButton(BUTTON_POSITIVE).setTextColor(resources.getColor(R.color.green))
        dialog.getButton(BUTTON_NEGATIVE).setTextColor(resources.getColor(R.color.red))
    }

    //Function used to clear the drone's SD card
    private fun clearSDCard() {
        Log.d("BANANAPIE", "attempting to update the media file list from the SD card")

        mLoadingDialog.show(this.supportFragmentManager, "tagCanBeWhatever") //display the loading dialog

        DJIDemoApplication.getCameraInstance()
            ?.let { camera ->
                mMediaManager = camera.mediaManager
                mMediaManager?.let { mediaManager -> //accessing the drone's camera's mediaManager

                    //refreshing the drone's mediaManager's file list from its SD card
                    mediaManager.refreshFileListOfStorageLocation(
                        SettingsDefinitions.StorageLocation.SDCARD
                    ) { djiError -> //checking the callback error

                        if (djiError == null) { //if the refresh was successful...
                            Log.d(
                                "BANANAPIE",
                                "obtained media data from SD card (FlightPlanActivity) - 1"
                            )
                            //completely clear the drone's SD card
                            camera.formatSDCard { DJIError ->
                                //if clearing was successful, dismiss the LoadingDialog and allow the user
                                // ... to add waypoints to the map.
                                if (DJIError == null) {
                                    Log.d(
                                        "BANANAPIE",
                                        "successfully cleared SD card"
                                    )
                                    mLoadingDialog.dismiss()
                                    mapTouch = true

                                } else { //if clearing was unsuccessful, alert the user and dismiss the LoadingDialog
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
                        } else { //if the refresh was unsuccessful, alert the user
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

    //Function used to navigate from this activity to PhotoStitcherActivity.kt
    private fun getPhotoStitcher() {
        val intent = Intent(this, PhotoStitcherActivity::class.java)
        this.startActivity(intent)
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

    //Function used to clear the flight plan from the map
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

    //Function used to initialize the display for the videoSurface TextureView
    private fun initPreviewer() {
        //gets an instance of the connected DJI product (null if nonexistent)
        val product: BaseProduct = DJIDemoApplication.getProductInstance()
            ?: return
        //if DJI product is disconnected, alert the user
        if (!product.isConnected) {
            showToast(this, getString(R.string.disconnected))
        } else {
            //if the DJI product is connected and the aircraft model is not unknown, add the receivedVideoDataListener
            // ... from the viewModel to the primary video feed.
            videoSurface.surfaceTextureListener = this
            if (product.model != Model.UNKNOWN_AIRCRAFT) {
                VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(
                    vM.receivedVideoDataListener
                )
            }
        }
    }

    //When a TextureView's SurfaceTexture is ready for use (drone video feed in this case), use it to initialize the codecManager.
    //Also we are setting up a machine learning classifier to track every frame sent to the SurfaceTexture
    // ... for car object detection. The results of the classifier will produce colored boxes around the cars
    // ... found in each frame along with percentages corresponding to the prediction for each car.
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
    //Function used to run the car object detection
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun runObjectDetection(bitmap: Bitmap) {//change this to bmp to run the code below

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