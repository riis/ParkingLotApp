package com.dji.droneparking.util

import com.dji.droneparking.util.Tools.getListClone
import com.dji.droneparking.util.Tools.reverseList
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.mapboxsdk.geometry.LatLng
//import com.mapbox.turf.TurfJoins
import dji.common.mission.waypoint.*
import dji.common.model.LocationCoordinate2D
import java.util.*

object FlightPlanner {
    /// The maximum speed the drone is allowed to fly while executing the flight plan
    private const val MAX_FLIGHT_SPEED = 15.0f

    /// The base automatic flight speed for the plan
    private const val AUTO_FLIGHT_SPEED = 15.0f

    /// The altitude to fly at (in m)
    private const val ALTITUDE = 46.672f

    /// Turns a lits of coordinates constituting a flight plan into a waypoint path.
    fun createFlightMissionFromCoordinates(coords: List<LocationCoordinate2D>): WaypointMission {
        val missionBuilder = WaypointMission.Builder()
        missionBuilder.maxFlightSpeed(MAX_FLIGHT_SPEED)
        missionBuilder.autoFlightSpeed(AUTO_FLIGHT_SPEED)
        // Go home when done.
        missionBuilder.finishedAction(WaypointMissionFinishedAction.GO_HOME)
        missionBuilder.headingMode(WaypointMissionHeadingMode.USING_WAYPOINT_HEADING)
        missionBuilder.flightPathMode(WaypointMissionFlightPathMode.NORMAL)
        // Allow the gimbal to rotate during operation
        missionBuilder.isGimbalPitchRotationEnabled = true
        // Abort if we can't comm w/ the drone
        missionBuilder.isExitMissionOnRCSignalLostEnabled = true
        // Forces an altitude rise to the waypoint's altitude before it moves there.
        missionBuilder.gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)

        // Add each coordinate
        for (coord in coords) {
            val waypointForCoord = Waypoint(coord.latitude, coord.longitude, ALTITUDE)
            waypointForCoord.heading = 0
            waypointForCoord.actionRepeatTimes = 1
            waypointForCoord.actionTimeoutInSeconds = 30
            waypointForCoord.turnMode = WaypointTurnMode.CLOCKWISE
            waypointForCoord.addAction(WaypointAction(WaypointActionType.GIMBAL_PITCH, -90))
            waypointForCoord.addAction(WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0))
            waypointForCoord.gimbalPitch = -90f
            // 28.956 == 95 ft in meters
            waypointForCoord.shootPhotoDistanceInterval = 28.956f
            missionBuilder.addWaypoint(waypointForCoord)
        }
        return missionBuilder.build()
    }

    @Throws(NotEnoughPointsException::class)
    fun createFlightPlan(
        points: List<LatLng>, spacingFeet: Float, poly: List<LatLng>
    ): List<LatLng> {
        if (points.size < 3) throw NotEnoughPointsException()

        // Calculate the minimum lat/lng.
        var minX = points[0].longitude
        var minY = points[0].latitude
        var maxX = points[0].longitude
        var maxY = points[0].latitude
        for (coord in points) {
            minX = if (minX > coord.longitude) coord.longitude else minX
            minY = if (minY > coord.latitude) coord.latitude else minY
            maxX = if (maxX < coord.longitude) coord.longitude else maxX
            maxY = if (maxY < coord.latitude) coord.latitude else maxY
        }
        var x = minX
        var y = minY
        val xIncrement = spacingFeetToDegrees(spacingFeet)
        val yIncrement = spacingFeetToDegrees(spacingFeet)
        val locations: MutableList<LocationCoordinate2D> = ArrayList()

        // Increment the starting x and y by the relevant amount;
        while (x < maxX) {
            var yLineLocations: MutableList<LocationCoordinate2D> = ArrayList()
            while (y < maxY) {
                yLineLocations.add(LocationCoordinate2D(y, x))
                y += yIncrement
            }

            // Remove points not in the area.
            yLineLocations = getFilteredYLineLocations(yLineLocations, poly)
            locations.addAll(yLineLocations)

            // Reset y and increment x;
            y = minY
            x = x + xIncrement
        }
        val coordinates = getReorderedFlightPlan(locations)
        val results: MutableList<LatLng> = ArrayList()
        for (coordinate in coordinates) {
            val coord = LatLng(coordinate.latitude, coordinate.longitude)
            results.add(coord)
        }
        return results
    }

    /// Get a more efficient flight plan from one that contains the required points.
    /// Corresponds to step 5 in the iOS implementation.
    private fun getReorderedFlightPlan(locations: List<LocationCoordinate2D>): List<LocationCoordinate2D> {
        val lines: MutableList<List<LocationCoordinate2D>> = ArrayList()
        val currentLine: MutableList<LocationCoordinate2D> = ArrayList()
        var currentLongitude = locations[0].longitude
        for (loc in locations) {
            currentLongitude = if (loc.longitude == currentLongitude) {
                currentLine.add(loc)
                loc.longitude
            } else {
                // Flip every other line.
                if (lines.size % 2 == 0) {
                    lines.add(reverseList(getListClone(currentLine)) as List<LocationCoordinate2D>)
                } else {
                    lines.add(getListClone(currentLine))
                }
                currentLine.clear()
                currentLine.add(loc)
                loc.longitude
            }
        }

        // Add leftover line from end to start.
        if (lines.size % 2 == 0) {
            lines.add(reverseList(currentLine) as List<LocationCoordinate2D>)
        } else {
            lines.add(currentLine)
        }

        // Flatten the list
        val result: MutableList<LocationCoordinate2D> = ArrayList()
        // Because we aren't in Java 8 (or >= API 24) , we can't use the more efficient stream()
        // and must imperatively concatenate the lists.
        for (list in lines) result.addAll(list)
        return result
    }

    private fun spacingFeetToDegrees(spacingFeet: Float): Double {
        // 10_000 / 90 is the average length of 1deg (depends on how close you are to the N/S poles)
        // 3280 is the ft -> km conversion factor.
        return spacingFeet / 3280.4 / (10000.0 / 90.0)
    }

    /// Return a filtered list containing only the points from the input list that lie in `area`.
    private fun getFilteredYLineLocations(
        locations: List<LocationCoordinate2D>,
        area: List<LatLng>
    ): MutableList<LocationCoordinate2D> {
        val points: MutableList<List<Point>> = ArrayList()
        val outerPoints: MutableList<Point> = ArrayList()
        val result: MutableList<LocationCoordinate2D> = ArrayList()
        for (point in area) {
            outerPoints.add(Point.fromLngLat(point.longitude, point.latitude))
        }
        points.add(outerPoints)
        val polygon = Polygon.fromLngLats(points)
        for (coord in locations) {
            val longitude = coord.longitude
            val latitude = coord.latitude
            val point = Point.fromLngLat(longitude, latitude)
//         TODO val inside: Boolean = TurfJoins.inside(point, polygon)
//            if (inside) {
//                result.add(coord)
//            }
        }
        return result
    }

    class NotEnoughPointsException internal constructor() : Exception()
}