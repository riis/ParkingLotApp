package com.dji.droneparking

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.dji.droneparking.mission.MavicMiniMissionOperator
import com.dji.droneparking.mission.Tools.showToast
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import dji.common.gimbal.*
import dji.common.mission.waypoint.*
import dji.sdk.gimbal.Gimbal
import dji.sdk.sdkmanager.DJISDKManager
import java.util.concurrent.ConcurrentHashMap


class MainActivity : AppCompatActivity(), GoogleMap.OnMapClickListener, OnMapReadyCallback,
    View.OnClickListener {
    private lateinit var locate: Button
    private lateinit var add: Button
    private lateinit var clear: Button
    private lateinit var config: Button
    private lateinit var upload: Button
    private lateinit var start: Button
    private lateinit var stop: Button

    companion object {
        const val TAG = "GSDemoActivity"
        private var waypointMissionBuilder: WaypointMission.Builder? = null

        fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }
    }

    private var isAdd = false
    private var droneLocationLat: Double = 15.0
    private var droneLocationLng: Double = 15.0
    private var droneMarker: Marker? = null
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>()
    private var gMap: GoogleMap? = null
    private lateinit var mapFragment: SupportMapFragment

    private var altitude = 100f
    private var speed = 10f

    private var instance: MavicMiniMissionOperator? = null
    private var finishedAction = WaypointMissionFinishedAction.NO_ACTION
    private var headingMode = WaypointMissionHeadingMode.AUTO

    private lateinit var gimbal: Gimbal


    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUi()

        mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this)

        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
        gimbal = DJISDKManager.getInstance().product.gimbal
        gimbal.rotate(rotation
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

        getWaypointMissionOperator()?.setLocationListener { location ->
            droneLocationLat = location.latitude
            droneLocationLng = location.longitude
            updateDroneLocation()
        }
        

    }

    override fun onMapReady(googleMap: GoogleMap) {
        this.gMap = googleMap
        gMap?.setOnMapClickListener(this)
        gMap?.mapType = GoogleMap.MAP_TYPE_SATELLITE
        getWaypointMissionOperator()?.setMap(gMap!!)
    }

    override fun onMapClick(point: LatLng) {
        if (isAdd) {
            markWaypoint(point)

            //TODO 'logic to add waypoint'

            val waypoint = Waypoint(point.latitude, point.longitude, altitude)
// Instantiates a new Polygon object and adds points to define a rectangle

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
    }


    private fun initUi() {
        locate = findViewById(R.id.locate)
        add = findViewById(R.id.add)
        clear = findViewById(R.id.clear)
        config = findViewById(R.id.config)
        upload = findViewById(R.id.upload)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)

        locate.setOnClickListener(this)
        add.setOnClickListener(this)
        clear.setOnClickListener(this)
        config.setOnClickListener(this)
        start.setOnClickListener(this)
        stop.setOnClickListener(this)
        upload.setOnClickListener(this)
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
            else -> {
            }
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
                    speed = 5.0f
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
            add.text = "Exit"
        } else {
            isAdd = false
            add.text = "Add"
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

    private fun getWaypointMissionOperator(): MavicMiniMissionOperator? {

        if (instance == null)
            instance = MavicMiniMissionOperator(this)

        return instance
    }
}