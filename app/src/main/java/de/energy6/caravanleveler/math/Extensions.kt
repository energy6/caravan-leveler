package de.energy6.caravanleveler.math

import android.view.MotionEvent
import java.lang.Float.max
import java.lang.Float.min
import kotlin.math.*

const val DEG_PER_RAD: Float = (180.0/ PI).toFloat()
const val RAD_PER_DEG: Float = (PI /180.0).toFloat()
val NaN3: Vector3 = Vector3(Float.NaN, Float.NaN, Float.NaN)

fun getRotationVectorFromMatrix(rm: FloatArray) : FloatArray {
    val qw = sqrt(1.0f + rm[0] + rm[4] + rm[8]) / 2.0f
    val qw4 = 4.0f * qw
    val qx = (rm[7] - rm[5]) / qw4
    val qy = (rm[2] - rm[6]) / qw4
    val qz = (rm[3] - rm[1]) / qw4

    return floatArrayOf(qx, qy, qz, qw, -1.0f)
}


/* Quaternion */

operator fun Quaternion.times(rhs: Quaternion): Quaternion = Quaternion.multiply(this, rhs)
operator fun Quaternion.unaryMinus(): Quaternion = this.inverted()

/**
 * Converts the Quaternion into an orientation vector with Euler angles
 * in degrees.
 * <p>
 * When it returns, the array values are as follows:
 * <ul>
 * <li>pitch  Angle of rotation about the x axis.</li>
 * <li>roll   Angle of rotation about the y axis.</li>
 * <li>yaw    Angle of rotation about the z axis.</li>
 * </ul>
  *
 * @return Vector3(pitch, roll, yaw) in degrees
 *
 * @see Quaternion.eulerAngles
 */
fun Quaternion.toOrientation() : Vector3 {
    val sqw = w*w
    val sqx = x*x
    val sqy = y*y
    val sqz = z*z
    val yaw = atan2(2.0f * (x*y + z*w), (sqx - sqy - sqz + sqw))
    val pitch = atan2(2.0f * (y*z + x*w), (-sqx - sqy + sqz + sqw))
    val roll = asin(-2.0f * (x*z - y*w))
    return Vector3(pitch, roll, yaw).toDegrees()
}
fun Quaternion.toFloatArray(): FloatArray = floatArrayOf(this.w, this.x, this.y, this.z)
fun Quaternion.toRotationVector(): FloatArray = floatArrayOf(this.x, this.y, this.z, this.w, -1f)

fun FloatArray.toQuaternion(): Quaternion {
    return when(size) {
        4 -> Quaternion(this[1], this[2], this[3], this[0])
        5 -> Quaternion(this[0], this[1], this[2], this[3])
        else -> error("Cannot convert FloatArray of size $size to Quaternion!")
    }
}

/* Vector3 */

fun MotionEvent.PointerCoords.toVector3() : Vector3 = Vector3(this.x, this.y, -this.pressure)

/**
 * Returns the spherical linear interpolation between two given vectors.
 *
 * If fraction is 0 this returns start.
 * As fraction approaches 1 {@link #slerp} approaches end
 * If fraction is above 1 or below 0 the result will be extrapolated.
 *
 * @param start the beginning value
 * @param end the ending value
 * @param fraction the ratio between the two vectors
 * @return interpolated value between the two vectors
 */
fun slerp(start: Vector3, end: Vector3, fraction: Float): Vector3 {
    val dot = min(max(Vector3.dot(start, end), -1.0f), 1.0f)
    val theta = acos(dot) * fraction
    val vec = (end - (start * dot)).normalized()
    return (start * cos(theta)) + (vec * sin(theta))
}

operator fun Vector3.minus(rhs: Vector3): Vector3 = Vector3.subtract(this, rhs)
operator fun Vector3.plus(rhs: Vector3): Vector3 = Vector3.add(this, rhs)
operator fun Vector3.unaryMinus(): Vector3 = this.negated()
operator fun Vector3.times(rhs: Float): Vector3 = this.scaled(rhs)
operator fun Vector3.times(rhs: Double): Vector3 = this.scaled(rhs.toFloat())
operator fun Vector3.times(rhs: Vector3): Float = this.x*rhs.x + this.y*rhs.y + this.z*rhs.z

infix fun Vector3.scl(rhs: Vector3): Vector3 = Vector3(this.x*rhs.x, this.y*rhs.y, this.z*rhs.z)
infix fun Vector3.x(rhs: Vector3): Vector3 = Vector3.cross(this, rhs)
infix fun Vector3.rot(rhs: Quaternion) : Vector3 = Quaternion.rotateVector(rhs, this)
infix fun Vector3.irot(rhs: Quaternion) : Vector3 = Quaternion.inverseRotateVector(rhs, this)

operator fun Float.times(rhs: Vector3): Vector3 = rhs.scaled(this)
operator fun Double.times(rhs: Vector3): Vector3 = rhs.scaled(this.toFloat())

fun Float.toRadians() = this * RAD_PER_DEG
fun Float.toDegrees() = this * DEG_PER_RAD
fun Vector3.toRadians() : Vector3 = this.scaled(RAD_PER_DEG)
fun Vector3.toDegrees() : Vector3 = this.scaled(DEG_PER_RAD)

fun abs(lhs: Vector3) : Float = lhs.length()
