package com.dji.droneparking.mission

import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.util.CommonCallbacks

class MavicMiniMissionOperator {

    private var state = 0
    private lateinit var mission: MavicMiniMission

    fun loadMission(mission: MavicMiniMission?): DJIError? {
        if (mission == null)
            return DJIError.COMMON_EXECUTION_FAILED
        else
            this.mission = mission
            return null
    }

    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>){

    }

    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>){

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

    val currentState: Int
        get() = state

}