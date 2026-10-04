package de.energy6.caravanleveler

import android.Manifest
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.platform.ComposeView
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation.fragment.NavHostFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneViewInstrumentedTest {
    @Test
    fun rendererHandlesPanPinchCompassAndLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        ).forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                permission
            )
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(
            instrumentation.targetContext
        )
        assertTrue(preferences.edit().putBoolean(PREF_COMPASS, true).commit())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sceneBounds = Rect()
            scenario.onActivity { activity ->
                val sceneView = activity.findViewById<ComposeView>(R.id.sceneView)
                assertTrue(sceneView.isShown)
                assertTrue(sceneView.getGlobalVisibleRect(sceneBounds))
            }
            SystemClock.sleep(MODEL_LOAD_TIMEOUT_MILLIS)

            val centerX = sceneBounds.exactCenterX()
            val centerY = sceneBounds.exactCenterY()
            injectPan(centerX, centerY)
            injectPinch(centerX, centerY)

            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.fragment) as NavHostFragment
                navHost.navController.navigate(
                    NavGraphDirections.actionGlobalSettingsFragment()
                )
            }
            SystemClock.sleep(RENDERER_RESUME_TIMEOUT_MILLIS)
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager
                    .findFragmentById(R.id.fragment) as NavHostFragment
                assertTrue(navHost.navController.popBackStack())
            }
            SystemClock.sleep(MODEL_LOAD_TIMEOUT_MILLIS)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<ComposeView>(R.id.sceneView).isShown)
            }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            SystemClock.sleep(RENDERER_RESUME_TIMEOUT_MILLIS)
            scenario.onActivity { activity ->
                assertEquals(
                    androidx.lifecycle.Lifecycle.State.RESUMED,
                    activity.lifecycle.currentState
                )
                assertTrue(activity.findViewById<ComposeView>(R.id.sceneView).isShown)
            }
        }
    }

    private fun injectPan(centerX: Float, centerY: Float) {
        val downTime = SystemClock.uptimeMillis()
        inject(singlePointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, centerX, centerY))

        val moveTime = downTime + EVENT_INTERVAL_MILLIS
        val move = singlePointerEvent(
            downTime,
            moveTime,
            MotionEvent.ACTION_MOVE,
            centerX,
            centerY
        ).apply {
            addBatch(
                moveTime + EVENT_INTERVAL_MILLIS,
                centerX + PAN_DISTANCE_PIXELS,
                centerY,
                PRESSURE,
                POINTER_SIZE,
                0
            )
        }
        inject(move)
        inject(
            singlePointerEvent(
                downTime,
                moveTime + 2 * EVENT_INTERVAL_MILLIS,
                MotionEvent.ACTION_UP,
                centerX + PAN_DISTANCE_PIXELS,
                centerY
            )
        )
    }

    private fun injectPinch(centerX: Float, centerY: Float) {
        val downTime = SystemClock.uptimeMillis()
        val initialLeft = centerX - INITIAL_PINCH_RADIUS_PIXELS
        val initialRight = centerX + INITIAL_PINCH_RADIUS_PIXELS
        val finalLeft = centerX - FINAL_PINCH_RADIUS_PIXELS
        val finalRight = centerX + FINAL_PINCH_RADIUS_PIXELS

        inject(singlePointerEvent(downTime, downTime, MotionEvent.ACTION_DOWN, initialLeft, centerY))
        inject(
            twoPointerEvent(
                downTime,
                downTime + EVENT_INTERVAL_MILLIS,
                MotionEvent.ACTION_POINTER_DOWN or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                initialLeft,
                initialRight,
                centerY
            )
        )

        val moveTime = downTime + 2 * EVENT_INTERVAL_MILLIS
        val move = twoPointerEvent(
            downTime,
            moveTime,
            MotionEvent.ACTION_MOVE,
            initialLeft,
            initialRight,
            centerY
        ).apply {
            addBatch(
                moveTime + EVENT_INTERVAL_MILLIS,
                pointerCoordinates(finalLeft, centerY, finalRight, centerY),
                0
            )
        }
        inject(move)
        inject(
            twoPointerEvent(
                downTime,
                moveTime + 2 * EVENT_INTERVAL_MILLIS,
                MotionEvent.ACTION_POINTER_UP or
                    (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                finalLeft,
                finalRight,
                centerY
            )
        )
        inject(
            singlePointerEvent(
                downTime,
                moveTime + 3 * EVENT_INTERVAL_MILLIS,
                MotionEvent.ACTION_UP,
                finalLeft,
                centerY
            )
        )
    }

    private fun inject(event: MotionEvent) {
        try {
            assertTrue(
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .injectInputEvent(event, true)
            )
        } finally {
            event.recycle()
        }
    }

    private fun singlePointerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent = motionEvent(
        downTime,
        eventTime,
        action,
        arrayOf(pointerProperties(0)),
        arrayOf(pointerCoordinates(x, y))
    )

    private fun twoPointerEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        firstX: Float,
        secondX: Float,
        y: Float
    ): MotionEvent = motionEvent(
        downTime,
        eventTime,
        action,
        arrayOf(pointerProperties(0), pointerProperties(1)),
        pointerCoordinates(firstX, y, secondX, y)
    )

    private fun motionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        properties: Array<MotionEvent.PointerProperties>,
        coordinates: Array<MotionEvent.PointerCoords>
    ): MotionEvent = MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        properties.size,
        properties,
        coordinates,
        0,
        0,
        1f,
        1f,
        0,
        0,
        InputDevice.SOURCE_TOUCHSCREEN,
        0
    )

    private fun pointerProperties(id: Int) = MotionEvent.PointerProperties().apply {
        this.id = id
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }

    private fun pointerCoordinates(x: Float, y: Float) = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = PRESSURE
        size = POINTER_SIZE
    }

    private fun pointerCoordinates(
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float
    ) = arrayOf(
        pointerCoordinates(firstX, firstY),
        pointerCoordinates(secondX, secondY)
    )

    private companion object {
        const val MODEL_LOAD_TIMEOUT_MILLIS = 5_000L
        const val RENDERER_RESUME_TIMEOUT_MILLIS = 1_000L
        const val EVENT_INTERVAL_MILLIS = 16L
        const val PAN_DISTANCE_PIXELS = 120f
        const val INITIAL_PINCH_RADIUS_PIXELS = 100f
        const val FINAL_PINCH_RADIUS_PIXELS = 220f
        const val PRESSURE = 1f
        const val POINTER_SIZE = 1f
    }
}
