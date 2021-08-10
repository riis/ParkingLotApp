package com.dji.droneparking

import android.app.AlertDialog
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.dji.droneparking.util.DJIDemoApplication
import com.dji.droneparking.util.MavicMiniMissionOperator
import com.dji.droneparking.util.PhotoStitcher
import com.dji.droneparking.util.Tools.showToast
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dji.common.gimbal.*
import dji.common.mission.waypoint.*
import dji.common.product.Model
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.gimbal.Gimbal
import dji.sdk.sdkmanager.DJISDKManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt


/**
 * The purpose of this app is to fly a DJI Mavic Mini drone over a parking lot in an automated fashion, have
 * ... its camera capture images of the parking spaces below, and use a trained machine learning model to
 * ... interpret the images and count the number of cars and empty spaces.
 */

class MainActivity : AppCompatActivity(), GoogleMap.OnMapClickListener, OnMapReadyCallback,
    View.OnClickListener, TextureView.SurfaceTextureListener {

    //UI views
    private lateinit var locate: Button
    private lateinit var add: Button
    private lateinit var clear: Button
    private lateinit var config: Button
    private lateinit var upload: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var flightPlanner: Button
    private lateinit var videoSurface: TextureView
    private lateinit var toggleButton: FloatingActionButton
    private lateinit var cardView: CardView
    private lateinit var compassTextView: TextView

    private lateinit var autoStitch: Button
    private lateinit var alignButton: Button

    private var isCameraShowing = false
    private var receivedVideoDataListener: VideoFeeder.VideoDataListener? =
        null //listener that recieves video data coming from the connected DJI product
    private var codecManager: DJICodecManager? =
        null //handles the encoding and decoding of video data

    //drone flight variables
    private var droneLocationLat: Double = 15.0
    private var droneLocationLng: Double = 15.0
    private var altitude = 100f
    private var speed = 10f

    //map and waypoint variables
    private var gMap: GoogleMap? = null
    private lateinit var mapFragment: SupportMapFragment
    private var droneMarker: Marker? = null
    private var isAdd = false
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>()

    //waypoint mission variables
    private var instance: MavicMiniMissionOperator? = null
    private var finishedAction = WaypointMissionFinishedAction.NO_ACTION
    private var headingMode = WaypointMissionHeadingMode.AUTO

    private var screenHeight = 0
    private var screenWidth = 0

    private lateinit var gimbal: Gimbal


    companion object {
        const val TAG = "GSDemoActivity"
        private var waypointMissionBuilder: WaypointMission.Builder? =
            null //used to build waypoint missions

        fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }
    }


    //Creating the activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) //setting the activity's content from layout

        //getting the mobile device screen dimensions
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        initUi() //initializing the UI elements

        //setting up the map fragment (configured to host Google Maps in the layout view xml)
        mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this) //callback when onMapReady() is called

        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
        gimbal = DJISDKManager.getInstance().product.gimbal
        gimbal.rotate(
            rotation
        ) { djiError ->
            if (djiError == null) {
                Log.d("STATUS", "rotate gimbal success")
                Toast.makeText(applicationContext, "rotate gimbal success", Toast.LENGTH_SHORT)
                    .show()
            } else {
                Log.d("STATUS", "rotate gimbal error " + djiError.description)
                Toast.makeText(applicationContext, djiError.description, Toast.LENGTH_SHORT)
                    .show()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            //get an instance of MavicMiniOperationOperator and set up a listener
            getWaypointMissionOperator()?.droneLocationLiveData?.observe(this, { location ->
                droneLocationLat = location.latitude
                droneLocationLng = location.longitude
                updateDroneLocation()
            })
        }

        getWaypointMissionOperator()?.setCompassListener { sensorVal ->
            runOnUiThread {
                compassTextView.text = sensorVal.toString()
            }
        }

        // The receivedVideoDataListener receives the raw video data and the size of the data from the DJI product.
        // It then sends this data to the codec manager for decoding.
        receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }

    }

    override fun onStart() {
        super.onStart()

        //getPhotoStitcher()



        toggleButton.setOnClickListener {
            isCameraShowing = !isCameraShowing

            if (isCameraShowing) {
                cardView.visibility = View.VISIBLE
                toggleButton.setImageResource(R.drawable.ic_map_icon)
            } else {
                cardView.visibility = View.GONE
                toggleButton.setImageResource(R.drawable.ic_linked_camera)
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.gMap = googleMap
        gMap?.setOnMapClickListener(this)
        gMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
    }

    override fun onMapClick(point: LatLng) {
        if (isAdd) {
            markWaypoint(point)
            val waypoint = Waypoint(point.latitude, point.longitude, altitude)

            if (waypointMissionBuilder == null) {
                waypointMissionBuilder =
                    WaypointMission.Builder()
                        .also { builder ->
                            builder.addWaypoint(waypoint)
                        }
            } else {
                waypointMissionBuilder?.addWaypoint(waypoint)
            }
        } else {
            showToast(this, "Cannot Add Waypoint")
        }
    }

    private fun markWaypoint(point: LatLng) {
        val markerOptions = MarkerOptions()
            .position(point)

        gMap?.let {
            val marker = it.addMarker(markerOptions)
            markers.put(markers.size, marker)
        }
        Log.d("TESTING", "$point")
    }

    private fun initUi() {
        locate = findViewById(R.id.locate)
        add = findViewById(R.id.add)
        clear = findViewById(R.id.clear)
        config = findViewById(R.id.config)
        upload = findViewById(R.id.upload)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        autoStitch = findViewById(R.id.autoStitchButton)
        compassTextView = findViewById(R.id.compass_text_view)
        alignButton = findViewById(R.id.align_button)
        flightPlanner = findViewById(R.id.flight_planner)

        videoSurface = findViewById(R.id.video_previewer_surface)
        toggleButton = findViewById(R.id.toggle_button)
        cardView = findViewById(R.id.card_view)

        //setting the video feed to show a 16:9 aspect ratio
        val temp = (screenWidth / 1.77779).roundToInt()
        showToast(temp.toString())
        val videoParams = videoSurface.layoutParams
        videoParams.height = temp
        videoSurface.layoutParams = videoParams

        //Giving videoSurface a listener that checks for when a surface texture is available.
        //The videoSurface will then display the surface texture, which in this case is a camera video stream.
        videoSurface.surfaceTextureListener = this

        locate.setOnClickListener(this)
        add.setOnClickListener(this)
        clear.setOnClickListener(this)
        config.setOnClickListener(this)
        start.setOnClickListener(this)
        stop.setOnClickListener(this)
        upload.setOnClickListener(this)
        flightPlanner.setOnClickListener(this)
        autoStitch.setOnClickListener(this)
        alignButton.setOnClickListener(this)
    }

    //Function that initializes the display for the videoSurface TextureView
    private fun initPreviewer() {
        val product: BaseProduct = DJIDemoApplication.getProductInstance()
            ?: return //gets an instance of the connected DJI product (null if nonexistent)
        //if DJI product is disconnected, alert the user
        if (!product.isConnected) {
            showToast(getString(R.string.disconnected))
        } else {
            //if the DJI product is connected and the aircraft model is not unknown, add the recievedVideoDataListener
            // ... to the primary video feed.
            videoSurface.surfaceTextureListener = this
            if (product.model != Model.UNKNOWN_AIRCRAFT) {
                VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(
                    receivedVideoDataListener
                )
            }
        }
    }

    //Function that displays toast messages to the user
    private fun showToast(msg: String?) {
        runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    //Function that unitializes the display for the videoSurface TextureView
    private fun uninitPreviewer() {
        val camera: Camera = DJIDemoApplication.getCameraInstance() ?: return
        VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(null)
    }

    //When the MainActivity is created or resumed, initalize the video feed display
    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    //When a TextureView's SurfaceTexture is ready for use, use it to initialize the codecManager
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
    }

    //When a SurfaceTexture is updated
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    //when a SurfaceTexture's size changes
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    //when a SurfaceTexture is about to be destroyed, uninitialize the codedManager
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        codecManager?.cleanSurface()
        codecManager = null
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

    private fun updateDroneLocation() {
        runOnUiThread {
            if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) {
                return@runOnUiThread
            }

            val pos = LatLng(droneLocationLat, droneLocationLng)

            val markerOptions = MarkerOptions()
                .position(pos)
                .icon(BitmapDescriptorFactory.fromResource(R.drawable.aircraft))
            runOnUiThread {
                droneMarker?.remove()
                if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                    droneMarker = gMap?.addMarker(markerOptions)
                }
            }
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate -> {
                updateDroneLocation()
                cameraUpdate()
            }
            R.id.add -> {
                enableDisableAdd()
            }
            R.id.clear -> {
                waypointMissionBuilder = null
                runOnUiThread {
                    gMap?.clear()
                }
            }
            R.id.config -> {
                showSettingsDialog()
            }
            R.id.upload -> {
                uploadWaypointMission()
            }
            R.id.start -> {
                startWaypointMission()
            }
            R.id.stop -> {
                stopWaypointMission()
            }
            R.id.flight_planner -> {
                val intent = Intent(v.context, FlightPlanActivity::class.java)
                v.context.startActivity(intent)
            }
            R.id.autoStitchButton -> {
                autoStitchMission()
            }
            R.id.align_button -> {
                alignDroneHeading()
            }
            else -> {
            }
        }
    }

    private fun alignDroneHeading() {
        getWaypointMissionOperator()?.alignHeading()
    }

    private fun autoStitchMission() {
        val home = LatLng(droneLocationLat, droneLocationLng)
        markWaypoint(home)

        val point = LatLng(droneLocationLat, droneLocationLng + 0.00054)
        markWaypoint(point)

        val homePoint = Waypoint(home.latitude, home.longitude, altitude)
        val waypoint = Waypoint(point.latitude, point.longitude, altitude)

        if (waypointMissionBuilder == null) {
            waypointMissionBuilder =
                WaypointMission.Builder()
                    .also { builder ->
                        builder.addWaypoint(homePoint)
                        builder.addWaypoint(waypoint)
                    }
        } else {
            waypointMissionBuilder?.addWaypoint(homePoint)
            waypointMissionBuilder?.addWaypoint(waypoint)
        }
    }

    private fun startWaypointMission() {
        getWaypointMissionOperator()?.startMission { error ->
            showToast(
                this,
                "Mission Start: " + if (error == null) "Successfully" else error.description
            )
        }
    }

    private fun stopWaypointMission() {
        getWaypointMissionOperator()?.stopMission { error ->
            showToast(
                this,
                "Mission Stop: " + if (error == null) "Successfully" else error.description
            )
        }
    }

    private fun uploadWaypointMission() {
        getWaypointMissionOperator()?.uploadMission { error ->
            if (error == null) {
                showToast(this, "Mission upload successfully!")
            } else {
                showToast(
                    this,
                    "Mission upload failed, error: " + error.description + " retrying..."
                )
                getWaypointMissionOperator()?.retryUploadMission(null)
            }
        }
    }

    private fun showSettingsDialog() {
        val settingsView = layoutInflater.inflate(R.layout.dialog_settings, null) as LinearLayout

        val wpAltitudeTV = settingsView.findViewById<View>(R.id.altitude) as TextView
        val speedRG = settingsView.findViewById<View>(R.id.speed) as RadioGroup
        val actionAfterFinishedRG =
            settingsView.findViewById<View>(R.id.actionAfterFinished) as RadioGroup
        val headingRG = settingsView.findViewById<View>(R.id.heading) as RadioGroup

        speedRG.setOnCheckedChangeListener { _, checkedId ->
            Log.d(TAG, "Select speed")
            when (checkedId) {
                R.id.lowSpeed -> {
                    speed = 3.0f
                }
                R.id.MidSpeed -> {
                    speed = 7.0f
                }
                R.id.HighSpeed -> {
                    speed = 10.0f
                }
            }
        }

        actionAfterFinishedRG.setOnCheckedChangeListener { _, checkedId ->
            Log.d(TAG, "Select finish action")

            when (checkedId) {
                R.id.finishNone -> {
                    finishedAction = WaypointMissionFinishedAction.NO_ACTION
                }
                R.id.finishGoHome -> {
                    finishedAction = WaypointMissionFinishedAction.GO_HOME
                }
                R.id.finishAutoLanding -> {
                    finishedAction = WaypointMissionFinishedAction.AUTO_LAND
                }
                R.id.finishToFirst -> {
                    finishedAction = WaypointMissionFinishedAction.GO_FIRST_WAYPOINT
                }
            }
        }

        headingRG.setOnCheckedChangeListener { _, checkedId ->

            Log.d(TAG, "Select heading")
            when (checkedId) {
                R.id.headingNext -> {
                    headingMode = WaypointMissionHeadingMode.AUTO
                }
                R.id.headingInitDirec -> {
                    headingMode = WaypointMissionHeadingMode.USING_INITIAL_DIRECTION
                }
                R.id.headingRC -> {
                    headingMode = WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER
                }
                R.id.headingWP -> {
                    headingMode = WaypointMissionHeadingMode.USING_WAYPOINT_HEADING
                }

            }
        }

        AlertDialog.Builder(this)
            .setTitle("")
            .setView(settingsView)
            .setPositiveButton("Finish") { _, _ ->
                val altitudeString = wpAltitudeTV.text.toString()
                altitude = nullToIntegerDefault(altitudeString).toInt().toFloat()
                Log.e(TAG, "altitude $altitude")
                Log.e(TAG, "speed $speed")
                Log.e(TAG, "mFinishedAction $finishedAction")
                Log.e(TAG, "mHeadingMode $headingMode")
                configWayPointMission()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            .create()
            .show()
    }

    private fun configWayPointMission() {
        if (waypointMissionBuilder == null) {
            waypointMissionBuilder = WaypointMission.Builder().finishedAction(finishedAction)
                .headingMode(headingMode)
                .autoFlightSpeed(speed)
                .maxFlightSpeed(speed)
                .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
        }

        waypointMissionBuilder?.let { builder ->
            builder.finishedAction(finishedAction)
                .headingMode(headingMode)
                .autoFlightSpeed(speed)
                .maxFlightSpeed(speed)
                .flightPathMode(WaypointMissionFlightPathMode.NORMAL)

            if (builder.waypointList.size > 0) {
                for (i in builder.waypointList.indices) {
                    builder.waypointList[i].altitude = altitude
                }
                showToast(this, "Set Waypoint attitude successfully")
            }
            getWaypointMissionOperator()?.let { operator ->
                val error = operator.loadMission(builder.build())
                if (error == null) {
                    showToast(this, "loadWaypoint succeeded")
                } else {
                    showToast(this, "loadWaypoint failed " + error.description)
                }
            }
        }
    }

    private fun nullToIntegerDefault(value: String): String {
        var newValue = value
        if (!isIntValue(newValue)) newValue = "0"
        return newValue
    }

    private fun isIntValue(value: String): Boolean {
        try {
            val newValue = value.replace(" ", "")
            newValue.toInt()
        } catch (e: Exception) {
            return false
        }
        return true
    }

    private fun enableDisableAdd() {
        if (!isAdd) {
            isAdd = true
            add.text = getString(R.string.exit)
        } else {
            isAdd = false
            add.text = getString(R.string.add)
        }
    }

    private fun cameraUpdate() {
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) {
            return
        }
        val pos = LatLng(droneLocationLat, droneLocationLng)
        val zoomLevel = 18f
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        gMap?.moveCamera(cameraUpdate)
    }

    //Gets an instance of the MavicMiniMissionOperator class and gives this activity's context as input
    private fun getWaypointMissionOperator(): MavicMiniMissionOperator? {

        if (instance == null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                instance = MavicMiniMissionOperator(this)
            }

        return instance
    }
}