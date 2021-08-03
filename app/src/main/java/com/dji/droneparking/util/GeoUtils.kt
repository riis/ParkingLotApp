package com.riis.cattlecounter.util

import com.mapbox.mapboxsdk.geometry.LatLng
import kotlin.math.pow
import kotlin.math.sqrt


typealias Vector = DoubleArray
typealias Matrix = Array<Vector>

/*
 * Calculate the euclidean distance between two points.
 * Ignore curvature of the earth.
 *
 * @param a: The first point
 * @param b: The second point
 * @return: The square of the distance between a and b
 */
fun distance(a: LatLng, b: LatLng): Double {
    return (a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0)
}

/* Calculate the distance between a line segment described by two points, and a third point.
 *
 * @param a, b: The points describing the line segment
 * @param point: The point to find the distance to
 * @return: A double describing the distance
 */
fun distanceToSegment(a: LatLng, b: LatLng, point: LatLng): Double {
    // Find length of the segment, break if it's zero. That is a==b; just use distance formula
    val lengthSquared = distance(a, b)
    if (lengthSquared == 0.0) {
        return distance(a, point)
    }

    // Convert points to vectors
    val aVec: Vector = doubleArrayOf(a.longitude, a.latitude)
    val bVec: Vector = doubleArrayOf(b.longitude, b.latitude)
    val pointVec: Vector = doubleArrayOf(point.longitude, point.latitude)

    val scaleFactor = dot(pointVec - aVec, bVec - aVec)

    // Divide by the length, then clamp to [0, 1]
    val t: Double = kotlin.math.max(0.0, kotlin.math.min(1.0, scaleFactor / lengthSquared))

    // Determine the projection of the point onto the segment
    val aMat = Matrix(1) { aVec }
    val bMat = Matrix(1) { bVec }
    val projection = aMat + t * (bMat - aMat)

    // Find the distance
    val leftPow = (point.longitude - projection[0][0]).pow(2.0)
    val rightPow = (point.latitude - projection[0][1]).pow(2.0)

    return sqrt(leftPow + rightPow)
}

private operator fun Double.times(matrix: Array<Vector>): Matrix {
    return Matrix(matrix.size) { index ->
        matrix[index].map { value ->
            value * this
        }.toDoubleArray()
    }
}

private operator fun Vector.minus(other: Vector): Vector {
    val result = Vector(this.size)

    // Just (this - other) at each index
    for (i in this.indices) {
        result[i] = this[i] - other[i]
    }

    return result
}

private operator fun Matrix.minus(other: Matrix): Matrix {
    // Check to make sure this is valid. They must have same dimension
    require(this.size == other.size)
    require(this[0].size == other[0].size)

    val result = Matrix(this.size) { Vector(other[0].size) }

    // Subtract (this - other) at each coordinate and put into result
    for (i in this.indices) {
        for (j in this[0].indices) {
            result[i][j] = this[i][j] - other[i][j]
        }
    }

    return result
}

private operator fun Matrix.times(value: Double): Matrix {
    val result = Matrix(this.size) { Vector(this[0].size) }

    // Multiply (this * value) at all locations and put into result
    for (i in this.indices) {
        for (j in this[0].indices) {
            result[i][j] = this[i][j] * value
        }
    }

    return result
}

private operator fun Matrix.times(other: Matrix): Matrix {
    // Get the matrix sizes
    val firstRows = this.size
    val firstCols = this[0].size
    val secondRows = other.size
    val secondCols = other[0].size

    // Make sure the multiplication is legal
    require(firstCols == secondRows)

    // Initialize a correctly-sized result matrix
    val result = Matrix(firstRows) { Vector(secondCols) }

    // Actually multiply them together
    for (i in this.indices) {
        for (j in other.indices) {
            for (k in other[0].indices) {
                result[i][j] += this[i][k] * other[k][j]
            }
        }
    }

    return result
}

private operator fun Matrix.plus(other: Matrix): Matrix {
    val result = Matrix(this.size) { Vector(this[0].size) }

    for (i in this.indices) {
        for (j in this[0].indices) {
            result[i][j] = this[i][j] + other[i][j]
        }
    }

    return result
}

private fun dot(v1: Vector, v2: Vector): Double {
    return v1.zip(v2).map { it.first * it.second }.reduce { a, b -> a + b }
}