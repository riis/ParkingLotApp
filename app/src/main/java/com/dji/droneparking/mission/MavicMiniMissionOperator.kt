package com.dji.droneparking.mission


import android.app.Activity
import android.content.Context
import com.dji.droneparking.mission.Tools.showToast
import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.flightcontroller.virtualstick.FlightControlData
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.util.CommonCallbacks
import java.util.*

class MavicMiniMissionOperator(val context: Context) {

    private var state: WaypointMissionState = WaypointMissionState.UNKNOWN
    private lateinit var mission: MavicMiniMission
    val currentState: WaypointMissionState
        get() = state

    private var sendDataTimer: Timer? = null
    private var sendDataTask: SendDataTask? = null

    fun loadMission(mission: MavicMiniMission?): DJIError? {
        return if (mission == null) {
            this.state = WaypointMissionState.NOT_READY as WaypointMissionState
            DJIMissionError.NULL_MISSION
        } else {
            this.mission = mission
            this.state = WaypointMissionState.READY_TO_UPLOAD
            null
        }
    }

    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        if (this.state == WaypointMissionState.READY_TO_UPLOAD) {
            this.state = WaypointMissionState.READY_TO_EXECUTE
            callback?.onResult(null)
        } else {
            this.state = WaypointMissionState.NOT_READY as WaypointMissionState
            callback?.onResult(DJIMissionError.UPLOADING_WAYPOINT)
        }
    }

    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>) {
        if (this.state == WaypointMissionState.READY_TO_EXECUTE) {

            showToast(context as Activity, "trying to move")

            DJIDemoApplication.getFlightController()?.startTakeoff(null)

            /***********************
             * PITCH: POSITIVE is BACKWARD, NEGATIVE is FORWARD, Range: [-30, 30]
             * ROLL: POSITIVE is RIGHT, NEGATIVE is LEFT, Range: [-30, 30]
             * YAW: POSITIVE is RIGHT, NEGATIVE is LEFT, Range: [-360, 360]
             * THROTTLE: UPWARDS MOVEMENT
             */

            if (null == sendDataTimer) {
                sendDataTask =
                    SendDataTask(0f, 0f, 0f, 0f)
                sendDataTimer = Timer()
                sendDataTimer?.schedule(sendDataTask, 0, 200)
            } else {
                sendDataTimer?.cancel()
                sendDataTimer = null
                showToast(context, "setting to null")
            }
        } else {
            callback.onResult(DJIMissionError.FAILED)
        }
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
            DJIDemoApplication.getFlightController()?.setVirtualStickModeEnabled(true, null)
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

