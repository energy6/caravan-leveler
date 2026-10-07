package de.energy6.caravanleveler

import android.Manifest
import android.os.SystemClock
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
    fun rendererLoadsCompassAndHandlesLifecycle() {
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
            scenario.onActivity { activity ->
                val sceneView = activity.findViewById<ComposeView>(R.id.sceneView)
                assertTrue(sceneView.isShown)
                assertTrue(sceneView.width > 0)
                assertTrue(sceneView.height > 0)
            }
            SystemClock.sleep(MODEL_LOAD_TIMEOUT_MILLIS)

            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<ComposeView>(R.id.sceneView).isShown)
            }

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

    private companion object {
        const val MODEL_LOAD_TIMEOUT_MILLIS = 5_000L
        const val RENDERER_RESUME_TIMEOUT_MILLIS = 1_000L
    }
}
