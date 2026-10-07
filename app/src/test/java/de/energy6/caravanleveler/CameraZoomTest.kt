package de.energy6.caravanleveler

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.closeTo
import org.junit.jupiter.api.Test

class CameraZoomTest {
    @Test
    fun `spreading pointers zooms in`() {
        val fov = calculateZoomFov(
            currentVerticalFovDegrees = 90f,
            previousPointerDistance = 100f,
            currentPointerDistance = 200f
        )

        assertThat(fov.toDouble(), closeTo(81.0, TOLERANCE))
    }

    @Test
    fun `closing pointers zooms out`() {
        val fov = calculateZoomFov(
            currentVerticalFovDegrees = 90f,
            previousPointerDistance = 200f,
            currentPointerDistance = 100f
        )

        assertThat(fov.toDouble(), closeTo(99.0, TOLERANCE))
    }

    @Test
    fun `zoom stays within supported field of view`() {
        val minimum = calculateZoomFov(
            currentVerticalFovDegrees = 30f,
            previousPointerDistance = 100f,
            currentPointerDistance = 200f
        )
        val maximum = calculateZoomFov(
            currentVerticalFovDegrees = 160f,
            previousPointerDistance = 200f,
            currentPointerDistance = 100f
        )

        assertThat(minimum.toDouble(), closeTo(30.0, TOLERANCE))
        assertThat(maximum.toDouble(), closeTo(160.0, TOLERANCE))
    }

    private companion object {
        const val TOLERANCE = 1e-5
    }
}
