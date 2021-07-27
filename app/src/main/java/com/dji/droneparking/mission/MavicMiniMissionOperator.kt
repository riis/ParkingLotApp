package com.dji.droneparking.mission

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.dji.droneparking.mission.Tools.showToast
import com.google.android.gms.maps.GoogleMap
import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.flightcontroller.virtualstick.*
import dji.common.mission.MissionState
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs


private const val TAG = "MavicMiniMissionOperator"

class MavicMiniMissionOperator(context: Context) {

    private var state: MissionState = WaypointMissionState.INITIAL_PHASE
    private val activity: AppCompatActivity
    private lateinit var mission: WaypointMission
    private lateinit var waypoints: MutableList<Waypoint>
    val currentState: MissionState
        get() = state
    private lateinit var currentDroneLocation: LocationCoordinate2D
    private var droneLocationLiveData: MutableLiveData<LocationCoordinate2D> = MutableLiveData()
    private lateinit var currentWaypoint: Waypoint
    private var sendDataTimer = Timer()
    private lateinit var sendDataTask: SendDataTask
    private lateinit var locationListener: LocationListener
    private var travelledLongitude = false
    private var waypointTracker = 0
    private var toggle = false
    private lateinit var polylineOptions: PolylineOptions
    private lateinit var map : GoogleMap
    private lateinit var polyline : Polyline

    init {
        initFlightController()
        activity = context as AppCompatActivity
    }

    fun interface LocationListener {
        fun locate(location: LocationCoordinate2D)
    }

    fun setMap(map:GoogleMap){
        this.map = map
    }

    private fun initFlightController() {
        DJIDemoApplication.getFlightController()?.let { flightController ->

            flightController.setVirtualStickModeEnabled(true, null)
            flightController.rollPitchControlMode = RollPitchControlMode.VELOCITY
            flightController.yawControlMode = YawControlMode.ANGLE
            flightController.verticalControlMode = VerticalControlMode.POSITION
            flightController.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND

            flightController.setStateCallback { flightControllerState ->
                currentDroneLocation = LocationCoordinate2D(
                    flightControllerState.aircraftLocation.latitude,
                    flightControllerState.aircraftLocation.longitude
                )

                droneLocationLiveData.postValue(currentDroneLocation)
                locationListener.locate(currentDroneLocation)

            }
        }
    }

    fun setLocationListener(listener: LocationListener) {
        this.locationListener = listener
    }

    fun loadMission(mission: WaypointMission?): DJIError? {
        return if (mission == null) {
            this.state = WaypointMissionState.NOT_READY
            DJIMissionError.NULL_MISSION
        } else {
            this.mission = mission
            this.waypoints = mission.waypointList
            this.state = WaypointMissionState.READY_TO_UPLOAD
            null
        }
    }

    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        if (this.state == WaypointMissionState.READY_TO_UPLOAD) {
            this.state = WaypointMissionState.READY_TO_START
            polylineOptions = PolylineOptions()
            for (waypoint in waypoints) {
                val coordinate = LatLng(waypoint.coordinate.latitude, waypoint.coordinate.longitude)
                polylineOptions.add(coordinate)
            }
            polyline = map.addPolyline(polylineOptions)
            callback?.onResult(null)
        } else {
            this.state = WaypointMissionState.NOT_READY
            callback?.onResult(DJIMissionError.UPLOADING_WAYPOINT)
        }
    }

    /***********************
     * Roll: POSITIVE is SOUTH, NEGATIVE is NORTH, Range: [-30, 30]
     * Pitch: POSITIVE is EAST, NEGATIVE is WEST, Range: [-30, 30]
     * YAW: POSITIVE is RIGHT, NEGATIVE is LEFT, Range: [-360, 360]
     * THROTTLE: UPWARDS MOVEMENT
     */

    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIError>?) {
        if (this.state == WaypointMissionState.READY_TO_START) {

            showToast(activity, "Starting to Takeoff")

            DJIDemoApplication.getFlightController()?.startTakeoff { error ->
                if (error == null) {
                    callback?.onResult(null)
                    this.state = WaypointMissionState.READY_TO_EXECUTE
                    executeMission()
                } else {
                    callback?.onResult(error)
                }
            }
        } else {
            callback?.onResult(DJIMissionError.FAILED)
        }
    }

    @SuppressLint("LongLogTag")
    private fun executeMission() {

        state = WaypointMissionState.EXECUTION_STARTING

        activity.lifecycleScope.launch {
            withContext(Dispatchers.Main) {


                currentWaypoint = waypoints[waypointTracker]

                droneLocationLiveData.observe(activity, { currentLocation ->

                    state = WaypointMissionState.EXECUTING
                    val longitudeDiff =
                        currentWaypoint.coordinate.longitude - currentLocation.longitude
                    val latitudeDiff =
                        currentWaypoint.coordinate.latitude - currentLocation.latitude

                    sendDataTimer.cancel()
                    sendDataTimer = Timer()

                    when {
                        //END CLAUSE FOR LONGITUDE MOVEMENT
                        abs(longitudeDiff) < 0.000003 && !travelledLongitude -> {
                            travelledLongitude = true
                            Log.i("STATUS", "finished travelling LONGITUDE")
                            sendDataTimer.cancel()
                        }

                        //END CLAUSE FOR LATITUDE MOVEMENT
                        abs(latitudeDiff) < 0.000003 && travelledLongitude -> {
                            waypointTracker++
                            Log.i("STATUS", "finished travelling LATITUDE")
                            if (waypointTracker < waypoints.size) {
                                currentWaypoint = waypoints[waypointTracker]
                                travelledLongitude = false
                            } else {
                                state = WaypointMissionState.EXECUTION_STOPPING
                                stopMission { error ->

                                    if (error == null) state = WaypointMissionState.INITIAL_PHASE

                                    showToast(
                                        activity,
                                        "Mission Ended: " + if (error == null) "Successfully" else error.description
                                    )
                                }
                            }

                            sendDataTimer.cancel()
                        }

                        //MOVE IN LONGITUDE DIRECTION
                        !travelledLongitude -> {//!travelledLongitude
                            Log.i("STATUS", "LONG")
                            toggle = true
                            chooseDirection(
                                longitudeDiff,
                                Direction(pitch = mission.autoFlightSpeed),
                                Direction(pitch = mission.autoFlightSpeed * -1)
                            )
                        }

                        //MOVE IN LATITUDE DIRECTION IF LONGITUDE IS DONE
                        travelledLongitude -> {//travelledLongitude
                            Log.i("STATUS", "LAT")
                            toggle = false
                            chooseDirection(
                                latitudeDiff,
                                Direction(roll = mission.autoFlightSpeed),
                                Direction(roll = mission.autoFlightSpeed * -1)
                            )
                        }
                    }

                })

            }
        }
    }

    private fun chooseDirection(difference: Double, dir1: Direction, dir2: Direction) {
        if (difference > 0) {
            move(dir1)
        } else {
            move(dir2)
        }
    }

    private fun move(dir: Direction) {
        sendDataTask =
            SendDataTask(dir.pitch, dir.roll, dir.yaw, dir.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)
    }

    fun resumeMission() {
    }

    fun pauseMission() {
    }

    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        showToast(activity, "trying to land")
        DJIDemoApplication.getFlightController()?.startLanding(callback)
    }

    fun retryUploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        uploadMission(callback)
    }

    class SendDataTask(pitch: Float, roll: Float, yaw: Float, throttle: Float) : TimerTask() {
        private val mPitch = pitch
        private val mRoll = roll
        private val mYaw = yaw
        private val mThrottle = throttle
        override fun run() {
            DJIDemoApplication.getFlightController()?.sendVirtualStickFlightControlData(
                FlightControlData(
                    mPitch,
                    mRoll,
                    mYaw,
                    mThrottle
                ), null
            )

            this.cancel()
        }
    }

    inner class Direction(
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val yaw: Float = 0f,
        val altitude: Float = currentWaypoint.altitude
    )
}