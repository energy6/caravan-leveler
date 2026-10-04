package de.energy6.caravanleveler

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Quaternion as SceneQuaternion
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.collision.HitResult
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.toRotation
import io.github.sceneview.node.CameraNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelInstance
import de.energy6.caravanleveler.math.Quaternion as DomainQuaternion
import de.energy6.caravanleveler.math.Vector3 as DomainVector3
import de.energy6.caravanleveler.math.slerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tan

private const val MIN_VERTICAL_FOV_DEGREES = 30f
private const val MAX_VERTICAL_FOV_DEGREES = 160f

private val COMPASS_POSITION = Position(x = 0.15f, y = 0.25f, z = 0.5f)

@Composable
fun LevelerScene(
    cameraState: LevelerCameraState,
    caravanState: LevelerCaravanState,
    showCompass: Boolean
) {
    val engine = rememberEngine()
    val cameraNode = rememberCameraNode(engine) {
        position = cameraState.position.toScenePosition()
        quaternion = cameraState.direction.toSceneQuaternion()
        focalLength = verticalFovToFocalLength(cameraState.verticalFovDegrees)
    }
    val verticalFov = remember {
        mutableFloatStateOf(cameraState.verticalFovDegrees)
    }

    SceneView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        engine = engine,
        isOpaque = true,
        frameRatePolicy = FrameRatePolicy.OnDemand(),
        autoCenterContent = false,
        autoFitContent = false,
        cameraNode = cameraNode,
        cameraManipulator = null,
        onGestureListener = null,
        onTouchEvent = remember(cameraNode, verticalFov) {
            { event, hitResult ->
                cameraNode.handleTouch(event, hitResult, verticalFov)
                true
            }
        }
    ) {
        rememberModelInstance(modelLoader, "caravan.glb")?.let { caravan ->
            ModelNode(
                modelInstance = caravan,
                autoAnimate = false,
                position = caravanState.position.toScenePosition(),
                rotation = caravanState.rotation.toSceneRotation(),
                scale = Scale(0.1f)
            )
        }
        rememberModelInstance(modelLoader, "compass.glb")?.let { compass ->
            ModelNode(
                modelInstance = compass,
                autoAnimate = false,
                position = COMPASS_POSITION,
                rotation = caravanState.compass.toSceneRotation(),
                scale = Scale(0.12f),
                isVisible = showCompass
            )
        }
    }

    LaunchedEffect(cameraNode, cameraState) {
        cameraNode.animateTo(cameraState, verticalFov)
    }
}

private suspend fun CameraNode.animateTo(
    state: LevelerCameraState,
    verticalFov: MutableFloatState
) {
    val startPosition = position.toDomainVector3()
    val startRotation = quaternion.toDomainQuaternion()
    val startFov = verticalFov.floatValue
    val positionDurationMillis =
        DomainVector3.angleBetweenVectors(startPosition, state.position).toLong() * 10L
    val fovDurationMillis =
        abs(startFov - state.verticalFovDegrees).toLong() * 100L
    val durationMillis = max(positionDurationMillis, fovDurationMillis)

    if (durationMillis == 0L) {
        applyCameraState(state.position, state.direction, state.verticalFovDegrees, verticalFov)
        return
    }

    val startNanos = withFrameNanos { it }
    while (true) {
        val nowNanos = withFrameNanos { it }
        val elapsedMillis = (nowNanos - startNanos) / 1_000_000f
        val positionFraction = animationFraction(elapsedMillis, positionDurationMillis)
        val fovFraction = animationFraction(elapsedMillis, fovDurationMillis)
        applyCameraState(
            position = slerp(startPosition, state.position, positionFraction),
            rotation = DomainQuaternion.slerp(startRotation, state.direction, positionFraction),
            fov = startFov + (state.verticalFovDegrees - startFov) * fovFraction,
            verticalFov = verticalFov
        )
        if (elapsedMillis >= durationMillis) break
    }
    applyCameraState(state.position, state.direction, state.verticalFovDegrees, verticalFov)
}

private fun CameraNode.applyCameraState(
    position: DomainVector3,
    rotation: DomainQuaternion,
    fov: Float,
    verticalFov: MutableFloatState
) {
    this.position = position.toScenePosition()
    quaternion = rotation.toSceneQuaternion()
    verticalFov.floatValue = fov
    focalLength = verticalFovToFocalLength(fov)
}

private fun animationFraction(elapsedMillis: Float, durationMillis: Long): Float =
    if (durationMillis == 0L) 1f else (elapsedMillis / durationMillis).coerceIn(0f, 1f)

private fun CameraNode.handleTouch(
    event: MotionEvent,
    hitResult: HitResult?,
    verticalFov: MutableFloatState
) {
    if (event.actionMasked != MotionEvent.ACTION_MOVE) return
    when (event.pointerCount) {
        1 -> move(hitResult, event)
        2 -> zoom(event, verticalFov)
    }
}

@Suppress("DEPRECATION")
private fun CameraNode.move(hitResult: HitResult?, event: MotionEvent) {
    if (event.historySize == 0 || hitResult?.nodeOrNull == null) return

    val current = event.pointerVector(pointerIndex = 0)
    val previous = event.pointerVector(pointerIndex = 0, historyPosition = 0)
    val pointerDistance = current - previous
    val viewDirection = DomainQuaternion.rotateVector(
        quaternion.toDomainQuaternion(),
        DomainVector3.forward()
    ).normalized()
    val viewPlane = DomainVector3(
        1f - viewDirection.x * viewDirection.x,
        1f - viewDirection.y * viewDirection.y,
        1f - viewDirection.z * viewDirection.z
    )

    val hitPoint = hitResult.getPoint()
    val screenPoint = worldToScreenPoint(hitPoint)
    val ray = screenPointToRay(
        screenPoint.x + pointerDistance.x,
        screenPoint.y + pointerDistance.y
    )
    val rayPoint = ray.getPoint(hitResult.getDistance()).toDomainVector3()
    val movement = (hitPoint.toDomainVector3() - rayPoint).componentScale(viewPlane)

    if (movement.isFinite()) {
        position = (position.toDomainVector3() + movement).toScenePosition()
    }
}

private fun CameraNode.zoom(event: MotionEvent, verticalFov: MutableFloatState) {
    if (event.historySize == 0) return

    val currentDistance = (
        event.pointerVector(pointerIndex = 0) - event.pointerVector(pointerIndex = 1)
    ).length()
    val previousDistance = (
        event.pointerVector(pointerIndex = 0, historyPosition = 0) -
            event.pointerVector(pointerIndex = 1, historyPosition = 0)
    ).length()
    val scale = 1f + ((previousDistance - currentDistance) / 100f).coerceIn(-0.1f, 0.1f)
    val newFov = (verticalFov.floatValue * scale).coerceIn(
        MIN_VERTICAL_FOV_DEGREES,
        MAX_VERTICAL_FOV_DEGREES
    )

    verticalFov.floatValue = newFov
    focalLength = verticalFovToFocalLength(newFov)
}

private fun verticalFovToFocalLength(verticalFovDegrees: Float): Double =
    FILAMENT_SENSOR_HEIGHT_MILLIMETERS / 2.0 /
        tan(Math.toRadians(verticalFovDegrees.toDouble()) / 2.0)

private fun MotionEvent.pointerVector(
    pointerIndex: Int,
    historyPosition: Int? = null
): DomainVector3 = if (historyPosition == null) {
    DomainVector3(getX(pointerIndex), getY(pointerIndex), -getPressure(pointerIndex))
} else {
    DomainVector3(
        getHistoricalX(pointerIndex, historyPosition),
        getHistoricalY(pointerIndex, historyPosition),
        -getHistoricalPressure(pointerIndex, historyPosition)
    )
}

private fun DomainVector3.componentScale(other: DomainVector3): DomainVector3 =
    DomainVector3(x * other.x, y * other.y, z * other.z)

private fun DomainVector3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

private fun DomainVector3.toScenePosition(): Position = Position(x, y, z)

private fun Position.toDomainVector3(): DomainVector3 = DomainVector3(x, y, z)

private fun io.github.sceneview.collision.Vector3.toDomainVector3(): DomainVector3 =
    DomainVector3(x, y, z)

private fun DomainQuaternion.toSceneQuaternion(): SceneQuaternion =
    SceneQuaternion(x, y, z, w)

private fun SceneQuaternion.toDomainQuaternion(): DomainQuaternion =
    DomainQuaternion(x, y, z, w)

private fun DomainQuaternion.toSceneRotation(): Rotation = toSceneQuaternion().toRotation()

private operator fun DomainVector3.minus(other: DomainVector3): DomainVector3 =
    DomainVector3.subtract(this, other)

private operator fun DomainVector3.plus(other: DomainVector3): DomainVector3 =
    DomainVector3.add(this, other)

private const val FILAMENT_SENSOR_HEIGHT_MILLIMETERS = 24.0
