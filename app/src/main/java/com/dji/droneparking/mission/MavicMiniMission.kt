package com.dji.droneparking.mission

import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode

class MavicMiniMission private constructor(
    val waypointList: List<Waypoint>,
    val finishedAction: WaypointMissionFinishedAction,
    val headingMode: WaypointMissionHeadingMode,
    val autoFlightSpeed: Float,
    val maxFlightSpeed: Float,
    val flightPathMode: WaypointMissionFlightPathMode,
) {

    class Builder(
        private var waypoints: MutableList<Waypoint> = emptyList<Waypoint>() as MutableList<Waypoint>,
        private var finishedAction: WaypointMissionFinishedAction = WaypointMissionFinishedAction.NO_ACTION,
        private var headingMode: WaypointMissionHeadingMode = WaypointMissionHeadingMode.AUTO,
        private var autoFlightSpeed: Float = 1.0f,
        private var maxFlightSpeed: Float = 10.0f,
        private var flightPathMode: WaypointMissionFlightPathMode = WaypointMissionFlightPathMode.NORMAL
    ) {

        val waypointList: List<Waypoint>
            get() = this.waypoints

        fun addWaypoint(waypoint: Waypoint): Builder {
            waypoints.add(waypoint)
            return this
        }

        fun removeWaypoint(waypoint: Waypoint): Builder {
            waypoints.remove(waypoint)
            return this
        }

        fun finishedAction(finishedAction: WaypointMissionFinishedAction): Builder {
            this.finishedAction = finishedAction
            return this
        }

        fun headingMode(headingMode: WaypointMissionHeadingMode): Builder {
            this.headingMode = headingMode
            return this
        }

        fun autoFlightSpeed(speed: Float): Builder {
            this.autoFlightSpeed = speed
            return this
        }

        fun maxFlightSpeed(speed: Float): Builder {
            this.maxFlightSpeed = speed
            return this
        }

        fun flightPathMode(pathMode: WaypointMissionFlightPathMode): Builder {
            this.flightPathMode = pathMode
            return this
        }

        fun build(): MavicMiniMission? {
            return if (isValid())
                MavicMiniMission(
                    waypoints,
                    finishedAction,
                    headingMode,
                    autoFlightSpeed,
                    maxFlightSpeed,
                    flightPathMode
                )
            else {
                null
            }
        }

        private fun isValid(): Boolean {
            var isValid = true
            for (waypoint in waypoints) {
                if (!checkGpsCoordination(
                        waypoint.coordinate.latitude,
                        waypoint.coordinate.longitude
                    )
                ) {
                    isValid = false
                }
            }

            if (autoFlightSpeed > 15 || autoFlightSpeed < -15 || maxFlightSpeed < 2 || maxFlightSpeed > 15)
                isValid = false

            return isValid
        }

        private fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }


    }

}