package com.dji.droneparking.mission

import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMissionFinishedAction
import dji.common.mission.waypoint.WaypointMissionFlightPathMode
import dji.common.mission.waypoint.WaypointMissionHeadingMode

class MavicMiniMission(builder: Builder) {
    var waypointList: List<Waypoint> = builder.waypointList

    class Builder(var waypointList: MutableList<Waypoint> = emptyList<Waypoint>() as MutableList<Waypoint>) {

        fun addWaypoint(waypoint: Waypoint): Builder {
            waypointList.add(waypoint)
            return this
        }

        fun removeWaypoint(waypoint: Waypoint): Builder {
            waypointList.remove(waypoint)
            return this
        }

        fun finishedAction(finishedAction: WaypointMissionFinishedAction): Builder {
            //todo
            return this
        }

        fun headingMode(headingMode: WaypointMissionHeadingMode):Builder{
            //todo
            return this
        }

        fun autoFlightSpeed(speed: Float): Builder {
            // todo
            return this
        }

        fun maxFlightSpeed(speed: Float): Builder {
            // todo
            return this
        }

        fun flightPathMode(normal: WaypointMissionFlightPathMode): Builder {
            // todo
            return this
        }

        fun build(): MavicMiniMission? {
            return if (isValid())
                MavicMiniMission(this)
            else {
                //TODO maybe handle this a better way
                null
            }
        }

        private fun isValid(): Boolean {
            var isValid = true
            for (waypoint in waypointList) {
                if (!checkGpsCoordination(
                        waypoint.coordinate.latitude,
                        waypoint.coordinate.longitude
                    )
                ) {
                    isValid = false
                }
            }
            return isValid
        }

        private fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }


    }

}