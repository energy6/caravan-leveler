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
    val panGesture = remember { PanGestureState() }

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
        onTouchEvent = remember(cameraNode, verticalFov, panGesture) {
            { event, hitResult ->
                cameraNode.handleTouch(event, hitResult, verticalFov, panGesture)
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
    verticalFov: MutableFloatState,
    panGesture: PanGestureState
) {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> panGesture.start(
            cameraNode = this,
            hitResult = hitResult,
            pointerX = event.getX(0),
            pointerY = event.getY(0)
        )
        MotionEvent.ACTION_POINTER_DOWN -> panGesture.clear()
        MotionEvent.ACTION_MOVE -> when (event.pointerCount) {
            1 -> move(panGesture, event, verticalFov.floatValue)
            2 -> zoom(event, verticalFov)
        }
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> panGesture.clear()
    }
}

private fun CameraNode.move(
    panGesture: PanGestureState,
    event: MotionEvent,
    verticalFovDegrees: Float
) {
    val distanceToPlane = panGesture.distanceToPlane ?: return
    val cameraRight = panGesture.cameraRight ?: return
    val cameraUp = panGesture.cameraUp ?: return
    val viewportHeight = viewport?.height ?: return
    val pointerMovement = panGesture.moveTo(event.getX(0), event.getY(0)) ?: return
    val movement = calculatePanMovement(
        pointerDeltaX = pointerMovement.x,
        pointerDeltaY = pointerMovement.y,
        distanceToPlane = distanceToPlane,
        verticalFovDegrees = verticalFovDegrees,
        viewportHeightPixels = viewportHeight,
        cameraRight = cameraRight,
        cameraUp = cameraUp
    )

    if (movement != null) {
        position = (position.toDomainVector3() + movement).toScenePosition()
    }
}

private class PanGestureState {
    var distanceToPlane: Float? = null
        private set
    var cameraRight: DomainVector3? = null
        private set
    var cameraUp: DomainVector3? = null
        private set
    private var pointerX: Float? = null
    private var pointerY: Float? = null

    fun start(
        cameraNode: CameraNode,
        hitResult: HitResult?,
        pointerX: Float,
        pointerY: Float
    ) {
        val hitPoint = hitResult?.takeIf { it.nodeOrNull != null }?.getPoint()
        if (hitPoint == null) {
            clear()
            return
        }
        val rotation = cameraNode.quaternion.toDomainQuaternion()
        val forward = DomainQuaternion.rotateVector(
            rotation,
            DomainVector3.forward()
        ).normalized()
        val distance = DomainVector3.dot(
            DomainVector3.subtract(hitPoint.toDomainVector3(), cameraNode.position.toDomainVector3()),
            forward
        )
        if (!distance.isFinite() || distance <= 0f) {
            clear()
            return
        }
        distanceToPlane = distance
        cameraRight = DomainQuaternion.rotateVector(rotation, DomainVector3.right()).normalized()
        cameraUp = DomainQuaternion.rotateVector(rotation, DomainVector3.up()).normalized()
        this.pointerX = pointerX
        this.pointerY = pointerY
    }

    fun moveTo(pointerX: Float, pointerY: Float): PointerMovement? {
        val previousX = this.pointerX ?: return null
        val previousY = this.pointerY ?: return null
        this.pointerX = pointerX
        this.pointerY = pointerY
        return PointerMovement(pointerX - previousX, pointerY - previousY)
    }

    fun clear() {
        distanceToPlane = null
        cameraRight = null
        cameraUp = null
        pointerX = null
        pointerY = null
    }
}

private data class PointerMovement(val x: Float, val y: Float)

private fun CameraNode.zoom(event: MotionEvent, verticalFov: MutableFloatState) {
    if (event.historySize == 0) return

    val currentDistance = (
        event.pointerVector(pointerIndex = 0) - event.pointerVector(pointerIndex = 1)
    ).length()
    val previousDistance = (
        event.pointerVector(pointerIndex = 0, historyPosition = 0) -
            event.pointerVector(pointerIndex = 1, historyPosition = 0)
    ).length()
    val newFov = calculateZoomFov(
        currentVerticalFovDegrees = verticalFov.floatValue,
        previousPointerDistance = previousDistance,
        currentPointerDistance = currentDistance
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
