package de.energy6.caravanleveler

import de.energy6.caravanleveler.math.Vector3
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CameraPanTest {
    @Test
    fun `horizontal pan follows pointer without changing depth`() {
        val movement = calculatePanMovement(
            pointerDeltaX = 100f,
            pointerDeltaY = 0f,
            distanceToPlane = 1f,
            verticalFovDegrees = 90f,
            viewportHeightPixels = 1_000,
            cameraRight = Vector3.right(),
            cameraUp = Vector3.up()
        )

        requireNotNull(movement)
        assertThat(movement.x.toDouble(), closeTo(-0.2, TOLERANCE))
        assertThat(movement.y.toDouble(), closeTo(0.0, TOLERANCE))
        assertThat(movement.z.toDouble(), closeTo(0.0, TOLERANCE))
    }

    @Test
    fun `vertical pan follows pointer without changing depth`() {
        val movement = calculatePanMovement(
            pointerDeltaX = 0f,
            pointerDeltaY = 100f,
            distanceToPlane = 1f,
            verticalFovDegrees = 90f,
            viewportHeightPixels = 1_000,
            cameraRight = Vector3.right(),
            cameraUp = Vector3.up()
        )

        requireNotNull(movement)
        assertThat(movement.x.toDouble(), closeTo(0.0, TOLERANCE))
        assertThat(movement.y.toDouble(), closeTo(0.2, TOLERANCE))
        assertThat(movement.z.toDouble(), closeTo(0.0, TOLERANCE))
    }

    @Test
    fun `pan respects an arbitrarily oriented camera plane`() {
        val cameraRight = Vector3(0f, 0f, -1f)
        val cameraUp = Vector3.up()
        val cameraForward = Vector3.right()
        val movement = calculatePanMovement(
            pointerDeltaX = 100f,
            pointerDeltaY = 50f,
            distanceToPlane = 1f,
            verticalFovDegrees = 90f,
            viewportHeightPixels = 1_000,
            cameraRight = cameraRight,
            cameraUp = cameraUp
        )

        requireNotNull(movement)
        assertThat(
            Vector3.dot(movement, cameraForward).toDouble(),
            closeTo(0.0, TOLERANCE)
        )
    }

    @Test
    fun `invalid projection does not produce movement`() {
        val movement = calculatePanMovement(
            pointerDeltaX = 100f,
            pointerDeltaY = 0f,
            distanceToPlane = 1f,
            verticalFovDegrees = 90f,
            viewportHeightPixels = 0,
            cameraRight = Vector3.right(),
            cameraUp = Vector3.up()
        )

        assertNull(movement)
    }

    private companion object {
        const val TOLERANCE = 1e-5
    }
}
