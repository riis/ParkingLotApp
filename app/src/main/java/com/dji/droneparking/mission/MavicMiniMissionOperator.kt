package com.dji.droneparking.mission

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import com.dji.droneparking.mission.Tools.showToast
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

    private var state: MissionState = WaypointMissionState.UNKNOWN
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

    init {
        initFlightController()
        activity = context as AppCompatActivity
    }

    fun interface LocationListener{
        fun locate(location: LocationCoordinate2D)
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
    // TODO figure out currentWaypoint.speed issue
    private fun executeMission() {

        state = WaypointMissionState.EXECUTION_STARTING

        activity.lifecycleScope.launch {
            withContext(Dispatchers.Main) {

                state = WaypointMissionState.EXECUTING

//                for (waypoint in waypoints) {
                currentWaypoint = waypoints[0]

                droneLocationLiveData.observe(activity, Observer {

                    val difference = currentWaypoint.coordinate.longitude - it.longitude

//                        Log.d(TAG, it.toString())
//                        Log.d(TAG, "Difference: $difference")

                    sendDataTimer.cancel()
                    sendDataTimer = Timer()

                    when {
                        abs(difference) < 0.000001 && abs(difference) > -0.000001 -> {
                            Log.d(TAG, "Trying to stop lol")
//                            goToLongitude(0f)
                            waypoints.remove(currentWaypoint)
                            if (waypoints.isNotEmpty()) {
                                currentWaypoint = waypoints[0]
                            } else {
                                stopMission(null)
                            }
                            sendDataTimer.cancel()
                        }
                        difference < 0 -> {
                            goToLongitude(-5f)
                        }
                        difference > 0 -> {
                            goToLongitude(5f)
                        }
                    }

                })


//                    break
//                }
            }
        }
    }

    private fun goToLatitude() {
        sendDataTask =
            SendDataTask(-5f, 0f, 0f, currentWaypoint.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)
    }

    private fun goToLongitude(pitch: Float) {
        sendDataTask =
            SendDataTask(pitch, 0f, 0f, currentWaypoint.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)
    }

    fun resumeMission() {
    }

    fun pauseMission() {
    }

    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        showToast(activity, "trying to land")
        DJIDemoApplication.getFlightController()?.startLanding(null)
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
}