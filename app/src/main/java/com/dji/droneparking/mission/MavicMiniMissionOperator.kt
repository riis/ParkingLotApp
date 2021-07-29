package com.dji.droneparking.mission

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.dji.droneparking.mission.Tools.showToast
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
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
import kotlin.math.pow


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
    private var sendDataTimer =
        Timer() //used to schedule tasks for future execution in a background thread
    private lateinit var sendDataTask: SendDataTask
    private lateinit var mLocationListener: LocationListener //uninitialized implementation of LocationListener interface
    private var travelledLongitude = false
    private var waypointTracker = 0
    private lateinit var polylineOptions: PolylineOptions
    private lateinit var map: GoogleMap
    private lateinit var polyline: Polyline
    private var originalLongitudeDiff = -1.0
    private var originalLatitudeDiff = -1.0

    init {
        initFlightController()
        activity = context as AppCompatActivity
    }


    //Interface used to listen to the drone's location whenever it gets updated.
    //When onLocationUpdate() is called, any implementations of LocationListener will receive the drone's location coordinates.
    fun interface LocationListener {
        fun onLocationUpdate(location: LocationCoordinate2D)
    }

    //initializing the drone's flight controller
    fun setMap(map: GoogleMap) {
        this.map = map
    }

    private fun initFlightController() {
        DJIDemoApplication.getFlightController()?.let { flightController ->

            flightController.setVirtualStickModeEnabled(
                true,
                null
            ) //enables the aircraft to be controlled virtually

            //setting the modes for controlling the drone's roll, pitch, and yaw
            flightController.rollPitchControlMode = RollPitchControlMode.VELOCITY
            flightController.yawControlMode = YawControlMode.ANGLE
            flightController.verticalControlMode = VerticalControlMode.POSITION

            //setting the drone's flight coordinate system
            flightController.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND

            //Checking the flightController's state (10 times a second) and getting the drone's current location coordinates
            flightController.setStateCallback { flightControllerState ->
                currentDroneLocation = LocationCoordinate2D(
                    flightControllerState.aircraftLocation.latitude,
                    flightControllerState.aircraftLocation.longitude
                )

                droneLocationLiveData.postValue(currentDroneLocation)
                mLocationListener.onLocationUpdate(currentDroneLocation)

            }
        }
    }

    //This function is called by MainActivity to create a new LocationListener implementation inside it.
    //mLocationListener is then set to this implementation.
    fun setLocationListener(listener: LocationListener) {
        this.mLocationListener = listener
    }

    //Function used to set the current waypoint mission and waypoint list
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

    //Function used to get the current waypoint mission ready to start
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

    //Function used to make the drone takeoff and then begin execution of the current waypoint mission
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

    /*
 * Calculate the euclidean distance between two points.
 * Ignore curvature of the earth.
 *
 * @param a: The first point
 * @param b: The second point
 * @return: The square of the distance between a and b
 */
    fun distance(a: LatLng, b: LatLng): Double {
        return (a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0)
    }

    //Function used to execute the current waypoint mission
    @SuppressLint("LongLogTag")
    private fun executeMission() {

        state = WaypointMissionState.EXECUTION_STARTING

        //running the execution in a coroutine to prevent blocking the main thread
        activity.lifecycleScope.launch {
            withContext(Dispatchers.Main) {

                currentWaypoint = waypoints[waypointTracker] //getting the current waypoint

                //observing changes to the drone's location coordinates
                droneLocationLiveData.observe(activity, { currentLocation ->

                    state = WaypointMissionState.EXECUTING


                    val longitudeDiff =
                        currentWaypoint.coordinate.longitude - currentLocation.longitude
                    val latitudeDiff =
                        currentWaypoint.coordinate.latitude - currentLocation.latitude

                    if (abs(latitudeDiff) > originalLatitudeDiff) {
                        originalLatitudeDiff = abs(latitudeDiff)
                    }

                    if (abs(longitudeDiff) > originalLongitudeDiff) {
                        originalLongitudeDiff = abs(longitudeDiff)
                    }

                    val droneLocation = LatLng(currentLocation.latitude, currentLocation.longitude)
                    val pointA = LatLng(waypoints[1].coordinate.latitude, waypoints[1].coordinate.longitude)
                    val autoStitchDistance = distance(droneLocation, pointA) * 111139

                    Log.i("STATUS", "$autoStitchDistance meters left")


                    //terminating the sendDataTimer and creating a new one
                    sendDataTimer.cancel()
                    sendDataTimer = Timer()

                    when {
                        //when the longitude difference becomes insignificant:
                        abs(longitudeDiff) < 0.000001 && !travelledLongitude -> {
                            travelledLongitude = true
                            Log.i("STATUS", "finished travelling LONGITUDE")
                            sendDataTimer.cancel() //cancel all scheduled data tasks
                        }
                        //when the latitude difference becomes insignificant and there
                        //... is no longitude difference (current waypoint has been reached):
                        abs(latitudeDiff) < 0.000001 && travelledLongitude -> {
                            //move to the next waypoint in the waypoints list
                            waypointTracker++
                            Log.i("STATUS", "finished travelling LATITUDE")
                            if (waypointTracker < waypoints.size) {
                                currentWaypoint = waypoints[waypointTracker]
                                travelledLongitude = false
                                originalLatitudeDiff = -1.0
                                originalLongitudeDiff = -1.0

                                Handler(Looper.getMainLooper()).postDelayed(
                                    {
                                        Log.d(TAG, "I was just stopped")
                                    },
                                    1000
                                )

                            } else { //If all waypoints have been reached, stop the mission
                                state = WaypointMissionState.EXECUTION_STOPPING
                                stopMission { error ->
                                    state = WaypointMissionState.INITIAL_PHASE
                                    showToast(
                                        activity,
                                        "Mission Ended: " + if (error == null) "Successfully" else error.description
                                    )
                                }
                            }

                            sendDataTimer.cancel() //cancel all scheduled data tasks
                        }

                        //MOVE IN LONGITUDE DIRECTION
                        !travelledLongitude -> {//!travelledLongitude
                            var speed: Float = mission.autoFlightSpeed

                            if (abs(longitudeDiff) / originalLongitudeDiff < 0.3f) {
                                speed = kotlin.math.max(
                                    (mission.autoFlightSpeed * (abs(longitudeDiff) / (originalLongitudeDiff * 0.3f))).toFloat(),
                                    1.2f
                                )
                            }

                            chooseDirection(
                                longitudeDiff,
                                Direction(pitch = speed),
                                Direction(pitch = -speed)
                            )
                        }

                        //MOVE IN LATITUDE DIRECTION IF LONGITUDE IS DONE
                        travelledLongitude -> {//travelledLongitude
                            var speed: Float = mission.autoFlightSpeed

                            if (abs(latitudeDiff) / originalLatitudeDiff < 0.3f) {
                                speed = kotlin.math.max(
                                    (mission.autoFlightSpeed * (abs(latitudeDiff) / (originalLatitudeDiff * 0.3f))).toFloat(),
                                    1.2f
                                )
                            }

                            chooseDirection(
                                latitudeDiff,
                                Direction(roll = speed),
                                Direction(roll = -speed)
                            )
                        }
                    }

                })

            }
        }
    }

    //Function used to choose whether the drone should move positively or negatively in the provided direction
    private fun chooseDirection(difference: Double, dir1: Direction, dir2: Direction) {
        if (difference > 0) {
            move(dir1)
        } else {
            move(dir2)
        }
    }

    @SuppressLint("LongLogTag")
    //Function used to move the drone in the provided direction
    private fun move(dir: Direction) {
        Log.d(TAG, "PITCH: ${dir.pitch}, ROLL: ${dir.roll}")
        sendDataTask =
            SendDataTask(dir.pitch, dir.roll, dir.yaw, dir.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)
    }

    fun resumeMission() {
    }

    fun pauseMission() {
    }

    //Function used to stop the current waypoint mission and land the drone
    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        showToast(activity, "trying to land")
        DJIDemoApplication.getFlightController()?.startLanding(callback)
    }

    //Function used to upload the
    fun retryUploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        uploadMission(callback)
    }

    /*
     * Roll: POSITIVE is SOUTH, NEGATIVE is NORTH, Range: [-30, 30]
     * Pitch: POSITIVE is EAST, NEGATIVE is WEST, Range: [-30, 30]
     * YAW: POSITIVE is RIGHT, NEGATIVE is LEFT, Range: [-360, 360]
     * THROTTLE: UPWARDS MOVEMENT
     */

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