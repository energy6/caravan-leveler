package de.energy6.caravanleveler

import android.view.MotionEvent
import de.energy6.caravanleveler.IsCloseTo.closeTo
import de.energy6.caravanleveler.math.*
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.collection.ArrayMatching.arrayContaining
import org.hamcrest.collection.ArrayMatching.asEqualMatchers
import org.hamcrest.core.IsEqual
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

class MathExtensionsTest {
    companion object {
        @JvmStatic
        fun quaternions() = listOf(
            Arguments.of(Quaternion.identity(), Vector3.zero()),
            Arguments.of(Quaternion.axisAngle(Vector3.back(), 30.0f), Vector3(0.0f, 0.0f, 30.0f)),
            Arguments.of(Quaternion.axisAngle(Vector3.up(), 10.0f), Vector3(0.0f, 10.0f, 0.0f)),
            Arguments.of(Quaternion.axisAngle(Vector3.right(), 15.0f), Vector3(15.0f, 0.0f, 0.0f))
        )

        @JvmStatic
        fun rotationMatrices() = listOf(
            Arguments.of(
                floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
                floatArrayOf(0f, 0f, 0f, 1f, -1f)
            ),
            Arguments.of(
                floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
                floatArrayOf(0.707f, 0f, 0f, 0.707f, -1f)
            ),
            Arguments.of(
                floatArrayOf(1f, 0f, 0f, 0f, 0.707f, -0.707f, 0f, 0.707f, 0.707f),
                floatArrayOf(0.383f, 0f, 0f, 0.924f, -1f)
            ),
            Arguments.of(
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f),
                floatArrayOf(0f, 0.707f, 0f, 0.707f, -1f)
            ),
            Arguments.of(
                floatArrayOf(0.707f, 0f, 0.707f, 0f, 1f, 0f, -0.707f, 0f, 0.707f),
                floatArrayOf(0f, 0.383f, 0f, 0.924f, -1f)
            ),
            Arguments.of(
                floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
                floatArrayOf(0f, 0f, 0.707f, 0.707f, -1f)
            ),
            Arguments.of(
                floatArrayOf(0.707f, -0.707f, 0f, 0.707f, 0.707f, 0f, 0f, 0f, 1f),
                floatArrayOf(0f, 0f, 0.383f, 0.924f, -1f)
            )
        )
    }

    @ParameterizedTest
    @MethodSource("quaternions")
    fun `test Quaternion toOrientation()`(q: Quaternion, expected: Vector3) {
        val result = q.toOrientation()
        assertThat("Euler vector", result, IsEqual(expected))
        assertThat("Quaternion", Quaternion.eulerAngles(result), IsEqual(q))
    }

    @ParameterizedTest
    @MethodSource("rotationMatrices")
    fun `test getRotationVectorFromMatrix`(rm: FloatArray, rv: FloatArray) {
        val result = getRotationVectorFromMatrix(rm)
        val matcher = rv.map { f -> closeTo(f, 0.0005f) }
        assertThat(result.toTypedArray(), arrayContaining(matcher))
    }

    @Test
    fun `test FloatArray4 (aka raw quaternion) toQuaternion()`() {
        val fa = floatArrayOf(1f, 2f, 3f, 4f)
        val result = fa.toQuaternion()
        assertThat(result, IsEqual(Quaternion(2f, 3f, 4f, 1f)))
    }

    @Test
    fun `test FloatArray5 (aka raw rotation vector) toQuaternion()`() {
        val fa = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val result = fa.toQuaternion()
        assertThat(result, IsEqual(Quaternion(1f, 2f, 3f, 4f)))
    }

    @Test
    fun `test FloatArray6 (aka invalid) toQuaternion()`() {
        val fa = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        assertThrows<IllegalStateException> {
            fa.toQuaternion()
        }
    }

    @Test
    fun `test PointerCoords toVector3()`() {
        val pc = MotionEvent.PointerCoords().apply {
            x = 1f
            y = 2f
            pressure = 3f
        }
        val result = pc.toVector3()
        assertThat(result, IsEqual(Vector3(1f, 2f, -3f)))
    }

    @Test
    fun `test Vector3 slerp`() {
        val start = Vector3.right()
        val stop = Vector3.up()
        val result = (0..10).map { slerp(start, stop, it/10.0f) }
        val expected = (0..10).map { Vector3(cos(it/20f*PI.toFloat()), sin(it/20f*PI.toFloat()), 0f) }
        assertThat(result.toTypedArray(), arrayContaining(asEqualMatchers(expected.toTypedArray())))
    }

    @Test
    fun `project vector primitives preserve the right handed coordinate system`() {
        assertThat(Vector3.cross(Vector3.right(), Vector3.up()), IsEqual(Vector3.back()))
        assertThat(Vector3.dot(Vector3.right(), Vector3.up()), closeTo(0f, 1e-7f))
        assertThat(
            Vector3.angleBetweenVectors(Vector3.right(), Vector3.up()),
            closeTo(90f, 1e-5f)
        )
        assertThat(Vector3(3f, 4f, 0f).normalized(), IsEqual(Vector3(0.6f, 0.8f, 0f)))
    }

    @Test
    fun `project quaternion inverse restores a rotated vector`() {
        val rotation = Quaternion.eulerAngles(Vector3(17f, -23f, 41f))
        val source = Vector3(0.3f, -0.7f, 1.2f)

        val restored = Quaternion.inverseRotateVector(
            rotation,
            Quaternion.rotateVector(rotation, source)
        )

        assertThat(restored.x, closeTo(source.x, 1e-5f))
        assertThat(restored.y, closeTo(source.y, 1e-5f))
        assertThat(restored.z, closeTo(source.z, 1e-5f))
    }

    @Test
    fun `project quaternion slerp follows the shortest arc`() {
        val midpoint = Quaternion.slerp(
            Quaternion.identity(),
            Quaternion.axisAngle(Vector3.back(), 90f),
            0.5f
        )
        val direction = Quaternion.rotateVector(midpoint, Vector3.right())
        val diagonal = (1f / sqrt(2f))

        assertThat(direction, IsEqual(Vector3(diagonal, diagonal, 0f)))
    }

    @Test
    fun `project look rotations preserve all three camera view directions`() {
        val viewDirections = listOf(
            Vector3.up() to Vector3.back(),
            Vector3.left() to Vector3.back(),
            Vector3.forward() to Vector3.up()
        )

        viewDirections.forEach { (forward, up) ->
            val rotation = Quaternion.lookRotation(forward, up)
            assertThat(
                Quaternion.rotateVector(rotation, Vector3.forward()),
                IsEqual(forward)
            )
        }
    }

    @Test
    fun `calibration quaternion storage keeps x y z w ordering`() {
        val quaternion = Quaternion(1f, 2f, 3f, 4f)
        val stored = CalibrationValue.from(quaternion)

        assertThat(stored.x, closeTo(quaternion.x, 1e-7f))
        assertThat(stored.y, closeTo(quaternion.y, 1e-7f))
        assertThat(stored.z, closeTo(quaternion.z, 1e-7f))
        assertThat(stored.w, closeTo(quaternion.w, 1e-7f))
        assertThat(stored.toQuaternion(), IsEqual(quaternion))
    }
}
