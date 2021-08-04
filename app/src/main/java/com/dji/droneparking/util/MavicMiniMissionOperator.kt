package com.dji.droneparking.util

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.dji.droneparking.util.Tools.showToast
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.flightcontroller.virtualstick.*
import dji.common.mission.MissionState
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt


private const val TAG = "MavicMiniMissionOperator"

class MavicMiniMissionOperator(context: Context) {

    private val activity: AppCompatActivity

    private var state: MissionState = WaypointMissionState.INITIAL_PHASE
    private lateinit var mission: WaypointMission
    private lateinit var waypoints: MutableList<Waypoint>
    private lateinit var currentWaypoint: Waypoint

    private var locationListener: LocationListener? =
        null //uninitialized implementation of LocationListener interface
    private var operatorListener: WaypointMissionOperatorListener? = null
    private lateinit var currentDroneLocation: LocationCoordinate2D
    private var droneLocationLiveData: MutableLiveData<LocationCoordinate2D> = MutableLiveData()

    private var travelledLongitude = false
    private var travelledLatitude = false
    private var waypointTracker = 0

    private var sendDataTimer =
        Timer() //used to schedule tasks for future execution in a background thread
    private lateinit var sendDataTask: SendDataTask

    private var originalLongitudeDiff = -1.0
    private var originalLatitudeDiff = -1.0
    private var directions: Direction = Direction(altitude = 0f)
    private var segmentCounter = 6.4008
    private lateinit var mCompassListener: CompassListener
    private var compassHeadingLiveData: MutableLiveData<Float> = MutableLiveData()

    init {
        initFlightController()
        activity = context as AppCompatActivity
    }


    //Interface used to listen to the drone's location whenever it gets updated.
    //When onLocationUpdate() is called, any implementations of LocationListener will receive the drone's location coordinates.
    fun interface LocationListener {
        fun onLocationUpdate(location: LocationCoordinate2D)
    }

    fun interface CompassListener {
        fun onHeadingUpdate(heading: Float)
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
                locationListener?.onLocationUpdate(currentDroneLocation)

                //TODO Implement code in FlightActivity to get code below to work
//                val heading = DJIDemoApplication.getFlightController()?.compass?.heading
//                if (heading != null) {
//                    mCompassListener.onHeadingUpdate(heading)
//                    compassHeadingLiveData.postValue(heading)
//                }


            }
        }
    }

    //This function is called by MainActivity to create a new LocationListener implementation inside it.
    //locationListener is then set to this implementation.
    fun setLocationListener(listener: LocationListener) {
        this.locationListener = listener
    }

    fun setCompassListener(listener: CompassListener) {
        this.mCompassListener = listener
    }

    fun alignHeading() {

        DJIDemoApplication.getFlightController()?.startTakeoff { error ->
            if (error == null) {
                sendDataTimer.cancel()
                sendDataTimer = Timer()

                activity.lifecycleScope.launch {
                    withContext(Dispatchers.Main) {

                        compassHeadingLiveData.observe(activity, { heading ->
                            if (heading != 0f) {
                                Log.d("STATUS", "heading not aligned")
                                sendDataTask =
                                    SendDataTask(0f, 0f, 0f, 1.2f)
                                sendDataTimer.schedule(sendDataTask, 0, 200)

                            } else {
                                DJIDemoApplication.getFlightController()?.startLanding { error ->
                                    if (error == null) {
                                        this.cancel()
                                    }
                                }
                            }


                        })
                    }
                }


            }
        }
    }

    //Function for taking a a single photo using the DJI Product's camera
    private fun takePhoto() {
        val camera: Camera = DJIDemoApplication.getCameraInstance() ?: return

        // Setting the camera capture mode to SINGLE, and then taking a photo using the camera.
        // If the resulting callback for each operation returns an error that is null, then the two operations are successful.
        val photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE
        camera.setShootPhotoMode(photoMode) { djiError ->
            if (djiError == null) {
                activity.lifecycleScope.launch {
                    camera.startShootPhoto { djiErrorSecond ->
                        if (djiErrorSecond == null) {
                            Log.i("STATUS", "take photo: success")
                        } else {
                            Log.i("STATUS", "Take Photo Failure: ${djiError?.description}")
                        }
                    }
                }
            }
        }

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
    private fun distance(a: LocationCoordinate2D, b: LocationCoordinate2D): Double {
        return sqrt((a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0))
    }

    //Function used to execute the current waypoint mission
    @SuppressLint("LongLogTag")
    private fun executeMission() {

        state = WaypointMissionState.EXECUTION_STARTING
        operatorListener?.onExecutionStart()

        //running the execution in a coroutine to prevent blocking the main thread
        activity.lifecycleScope.launch {
            withContext(Dispatchers.Main) {

                currentWaypoint = waypoints[waypointTracker] //getting the current waypoint

                //observing changes to the drone's location coordinates
                droneLocationLiveData.observe(activity, { currentLocation ->

                    state = WaypointMissionState.EXECUTING

                    Log.d(
                        "TESTING",
                        "${
                            distance(
                                LocationCoordinate2D(
                                    currentWaypoint.coordinate.latitude,
                                    currentWaypoint.coordinate.longitude
                                ),
                                LocationCoordinate2D(
                                    currentLocation.latitude,
                                    currentLocation.longitude
                                )
                            )
                        }"
                    )

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

                    val droneLocation =
                        LocationCoordinate2D(currentLocation.latitude, currentLocation.longitude)
                    val pointA = LocationCoordinate2D(
                        waypoints[1].coordinate.latitude,
                        waypoints[1].coordinate.longitude
                    )
                    val autoStitchDistance = distance(droneLocation, pointA) * 111139




                    Log.i("STATUS", "$autoStitchDistance meters left")


                    //terminating the sendDataTimer and creating a new one
                    sendDataTimer.cancel()
                    sendDataTimer = Timer()

                    //MOVE IN LONGITUDE DIRECTION
                    if (!travelledLongitude) {//!travelledLongitude
                        val speed = kotlin.math.max(
                            (mission.autoFlightSpeed * (abs(longitudeDiff) / (originalLongitudeDiff))).toFloat(),
                            0.5f
                        )

                        if (longitudeDiff > 0) {
                            directions.pitch = speed
                        } else {
                            directions.pitch = -speed
                        }
                    }

                    //MOVE IN LATITUDE DIRECTION IF LONGITUDE IS DONE
                    if (!travelledLatitude) {

                        val speed = kotlin.math.max(
                            (mission.autoFlightSpeed * (abs(latitudeDiff) / (originalLatitudeDiff))).toFloat(),
                            0.5f
                        )

                        if (latitudeDiff > 0) {
                            directions.roll = speed
                        } else {
                            directions.roll = -speed
                        }
                    }

                    //when the longitude difference becomes insignificant:
                    if (abs(longitudeDiff) < 0.000002) {
                        Log.i("STATUS", "finished travelling LONGITUDE")
//                            sendDataTimer.cancel() //cancel all scheduled data tasks
                        directions.pitch = 0f
                        travelledLongitude = true
                    }


                    if (abs(latitudeDiff) < 0.000002) {
                        Log.i("STATUS", "finished travelling LATITUDE")
                        directions.roll = 0f
                        travelledLatitude = true
                    }

                    //when the latitude difference becomes insignificant and there
                    //... is no longitude difference (current waypoint has been reached):
                    if (travelledLatitude && travelledLongitude) {
                        //move to the next waypoint in the waypoints list
                        waypointTracker++
                        if (waypointTracker < waypoints.size) {
                            currentWaypoint = waypoints[waypointTracker]
                            originalLatitudeDiff = -1.0
                            originalLongitudeDiff = -1.0
                            travelledLongitude = false
                            travelledLatitude = false
                            directions = Direction()
                        } else { //If all waypoints have been reached, stop the mission
                            state = WaypointMissionState.EXECUTION_STOPPING
                            operatorListener?.onExecutionFinish(null)
                            stopMission(null)
                        }

                        sendDataTimer.cancel() //cancel all scheduled data tasks
                    } else {
                        directions.altitude = currentWaypoint.altitude
                        move(directions)
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

    fun addListener(listener: WaypointMissionOperatorListener) {
        this.operatorListener = listener
    }

    fun removeListener() {
        this.operatorListener = null
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
        var pitch: Float = 0f,
        var roll: Float = 0f,
        var yaw: Float = 0f,
        var altitude: Float = currentWaypoint.altitude
    )
}