package com.dji.droneparking.util

import android.os.Handler
import android.util.Log
import android.widget.TextView
import com.dji.droneparking.FlightPlanActivity
import dji.common.error.DJIError
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionDownloadEvent
import dji.common.mission.waypoint.WaypointMissionExecutionEvent
import dji.common.mission.waypoint.WaypointMissionUploadEvent
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener

class WaypointMissionManager(
    private val mission: WaypointMission, private val operator: MavicMiniMissionOperator,
    t: TextView, act: FlightPlanActivity
) {
    private val toEdit: TextView = t
    private val runningIn: FlightPlanActivity = act
    private var flightStopped = false

    fun startMission() {
        updateToast("Uploading mission to drone...")
        operator.loadMission(mission)
        operator.addListener(object : WaypointMissionOperatorListener {
            override fun onDownloadUpdate(waypointMissionDownloadEvent: WaypointMissionDownloadEvent) {}
            override fun onUploadUpdate(waypointMissionUploadEvent: WaypointMissionUploadEvent) {}
            override fun onExecutionUpdate(waypointMissionExecutionEvent: WaypointMissionExecutionEvent) {}
            override fun onExecutionStart() {
                updateToast("Mission started.")
            }

            override fun onExecutionFinish(djiError: DJIError?) {
                if (djiError == null) {
                    if (!flightStopped) {
                        runningIn.animateNextButton()
                        updateToast("Mission completed!")
                    } else {
                        updateToast("Mission cancelled")
                    }
                }
            }
        })
        operator.uploadMission { djiError: DJIError? ->
            if (djiError != null) {
                Log.e("MISSION", "Upload error: " + djiError.getDescription())
                if (flightStopped) {
                    return@uploadMission
                }
                val handler = Handler()
                handler.postDelayed({ startMission() }, 5000)
                updateToast("Could not upload mission! Retrying...")
            } else {
                Log.d("MISSION", "Uploaded mission.")
                updateToast("Mission uploaded.")
                beginMission()
            }
        }
    }

    private fun beginMission() {
        Log.d("MISSION", "Starting mission.")
        operator.startMission { djiError: DJIError? ->
            Log.d(TAG, "beginMissiononResultdjiError: $djiError")
            if (djiError != null) {
                updateToast("Something went wrong, check GPS.!")
                Log.e(
                    "MISSION_FAILED",
                    "Mission completion failed: " + djiError.getDescription()
                )
                return@startMission
            }
            Log.d("MISSION", "Mission started.")
            updateToast("Starting mission")
        }
    }

    fun stopFlight() {
        flightStopped = true
        operator.stopMission { djiError: DJIError? ->
            if (djiError != null) {
                updateToast("Could not cancel flight!")
                Log.e("MISSION_FAILED", "Could not cancel mission: " + djiError.getDescription())
                return@stopMission
            }
            updateToast("Flight cancelled.")
        }
    }

    private fun updateToast(msg: String) {
        try {
            toEdit.text = msg
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val TAG = "WaypointMissionManager"
    }

}