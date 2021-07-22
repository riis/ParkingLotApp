package com.dji.droneparking.mission


import android.app.Activity
import android.content.Context
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
import java.util.*

class MavicMiniMissionOperator(private val context: Context) {

    private var state: MissionState = WaypointMissionState.UNKNOWN
    private lateinit var mission: WaypointMission
    private lateinit var waypoints: MutableList<Waypoint>


    val currentState: MissionState
        get() = state

    private lateinit var currentDroneLocation: LocationCoordinate2D
    private lateinit var currentWaypoint: Waypoint
    private val sendDataTimer = Timer()
    private lateinit var sendDataTask: SendDataTask

    private lateinit var locationCallback: (LocationCoordinate2D) -> Unit

    init {
        initFlightController()
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

                locationCallback(currentDroneLocation)
            }
        }
    }

    fun setLocationListener(callback: (LocationCoordinate2D) -> Unit) {
        this.locationCallback = callback
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
     * PITCH: POSITIVE is SOUTH, NEGATIVE is NORTH, Range: [-30, 30]
     * ROLL: POSITIVE is EAST, NEGATIVE is WEST, Range: [-30, 30]
     * YAW: POSITIVE is RIGHT, NEGATIVE is LEFT, Range: [-360, 360]
     * THROTTLE: UPWARDS MOVEMENT
     */

    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIError>?) {

        if (this.state == WaypointMissionState.READY_TO_START) {
            showToast(context as Activity, "Starting to Takeoff")
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

    private fun executeMission() {
        this.state = WaypointMissionState.EXECUTION_STARTING
        for (waypoint in waypoints) {
            this.state = WaypointMissionState.EXECUTING
            currentWaypoint = waypoint
            goToLatitude()
            goToLongitude()
            waypoints.remove(waypoint)
        }
    }

    private fun goToLatitude() {
        sendDataTask =
            SendDataTask(-5f, 0f, 0f, currentWaypoint.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)

    }

    private fun goToLongitude() {
        sendDataTask =
            SendDataTask(-5f, 0f, 0f, currentWaypoint.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)


    }


    fun resumeMission() {

    }

    fun pauseMission() {

    }

    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        showToast(context as Activity, "trying to land")
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
        }
    }

}

