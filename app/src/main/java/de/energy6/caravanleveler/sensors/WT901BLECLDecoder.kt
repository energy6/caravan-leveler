package de.energy6.caravanleveler.sensors

import android.hardware.SensorManager
import de.energy6.caravanleveler.math.getRotationVectorFromMatrix

/** Decodes the notification payload independently of the GATT transport. */
internal class WT901BLECLDecoder {
    private class FilterMedian(private val depth : Int) {
        private val history = arrayListOf<ArrayList<Float>>()

        fun filter(value: FloatArray) = value.mapIndexed { index, fl ->
                if (history.size <= index) {
                    history.add(arrayListOf())
                }
                history[index].run {
                    add(fl)
                    if (size > depth) {
                        removeAt(0)
                        val median = sorted().subList((depth/4), depth*3/4)
                        median.reduce { acc, v -> acc + v } / median.size
                    } else {
                        reduce { acc, v -> acc + v } / size
                    }
                }
            }.toFloatArray()
    }

    private var mGravit = FloatArray(3)
    private var mGeomagneticField = FloatArray(3)
    private val mFilterGrav = FilterMedian(10)
    private val mFilterMag = FilterMedian(10)

    private fun ByteArray.decodeToFloat(index: Int) : Float {
        return ((this[index].toInt() and 0xFF) + (this[index+1].toInt() and 0xFF shl 8)).toShort().toFloat()
    }

    fun decode(value: ByteArray, coordinates: Sensor.Coordinates): FloatArray? {
        if (value[1].toInt() == 0x61) {
            val ax = value.decodeToFloat(2) / 32768.0f * 16.0f * 9.81f
            val ay = value.decodeToFloat(4) / 32768.0f * 16.0f * 9.81f
            val az = value.decodeToFloat(6) / 32768.0f * 16.0f * 9.81f
            mGravit = mFilterGrav.filter(floatArrayOf(ax, ay, az))

            val rv = Pair(FloatArray(9), FloatArray(9)).let {
                val success = SensorManager.getRotationMatrix(it.first, null, mGravit, mGeomagneticField)
                if (success) {
                    SensorManager.remapCoordinateSystem(
                        it.first,
                        coordinates.xaxis.id,
                        coordinates.yaxis.id,
                        it.second
                    )
                    getRotationVectorFromMatrix(it.second)
                } else {
                    null
                }
            }

            return rv
        } else if ((value[1].toInt() == 0x71) and (value[2].toInt() == 0x3A)) {
            val hx = value.decodeToFloat(4) / 10f
            val hy = value.decodeToFloat(6) / 10f
            val hz = value.decodeToFloat(8) / 10f
            mGeomagneticField = mFilterMag.filter(floatArrayOf(hx, hy, hz))
        }
        return null
    }

}
