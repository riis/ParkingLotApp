package com.dji.droneparking.mission

import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.util.CommonCallbacks

class MavicMiniMissionOperator {

    private var state: WaypointMissionState = WaypointMissionState.UNKNOWN
    private lateinit var mission: MavicMiniMission
    val currentState: WaypointMissionState
        get() = state

    fun loadMission(mission: MavicMiniMission?): DJIError? {
        return if (mission == null) {
            this.state = WaypointMissionState.NOT_READY as WaypointMissionState
            DJIMissionError.NULL_MISSION
        }else {
            this.mission = mission
            this.state = WaypointMissionState.READY_TO_UPLOAD
            null
        }
    }

    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>){
        if(this.state == WaypointMissionState.READY_TO_UPLOAD) {
            this.state = WaypointMissionState.READY_TO_EXECUTE
            callback.onResult(null)
        }else{
            this.state = WaypointMissionState.NOT_READY as WaypointMissionState
            callback.onResult(DJIMissionError.UPLOADING_WAYPOINT)
        }
    }

    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>){
        if (this.state == WaypointMissionState.READY_TO_EXECUTE){

        }else{
            callback.onResult(DJIMissionError.FAILED)
        }
    }

    fun resumeMission(){

    }

    fun pauseMission(){

    }

    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>){

    }

    fun setAutoFlightSpeed(){

    }

    fun retryUploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {

    }



}