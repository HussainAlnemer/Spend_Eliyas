package com.alnemer.spend.ui

import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * House motion system, translated from Apple's "Designing Fluid Interfaces" (WWDC 2018) into
 * Compose. Two designer-friendly knobs instead of raw mass/stiffness/damping:
 *   - dampingRatio: 1.0 = critically damped (no bounce, default for anything not gesture-driven).
 *   - response: seconds to reach the target - NOT a fixed duration, a spring has none.
 * stiffness is derived from response assuming mass = 1: stiffness = (2*pi/response)^2.
 */
object Motion {
    const val DampingDefault = 1f
    const val ResponseDefault = 0.4f
    // Momentum spring: only for elements a gesture just threw (flick release, drag commit) -
    // a little overshoot reads as physical; using it on a plain fade-in would look like a bug.
    const val DampingMomentum = 0.8f
    const val ResponseMomentum = 0.3f

    fun stiffness(response: Float): Float {
        val omega = (2f * Math.PI.toFloat()) / response
        return omega * omega
    }

    fun default() = spring<Float>(dampingRatio = DampingDefault, stiffness = stiffness(ResponseDefault))
    fun momentum() = spring<Float>(dampingRatio = DampingMomentum, stiffness = stiffness(ResponseMomentum))
}

/**
 * Apple's rubber-band resistance function (Designing Fluid Interfaces) - progressive resistance
 * past a boundary instead of a hard stop. `overshoot` is how far past the edge the raw drag has
 * gone; `dimension` is the size of the resisting region.
 */
fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
    (overshoot * dimension * constant) / (dimension + constant * kotlin.math.abs(overshoot))

/**
 * Apple's momentum-projection formula (Designing Fluid Interfaces sample code) - projects where
 * a flick would come to rest under natural deceleration, so a fast short drag can still commit
 * and a slow long drag with no velocity doesn't overshoot. decelerationRate ~0.998 matches normal
 * scroll feel.
 */
fun projectedOffset(current: Float, velocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float =
    current + (velocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)

/** Mirrors `prefers-reduced-motion`: Android's system "Remove animations" accessibility toggle. */
val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun ProvideReduceMotion(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val reduce = remember {
        try {
            AndroidSettings.Global.getFloat(ctx.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) { false }
    }
    CompositionLocalProvider(LocalReduceMotion provides reduce, content = content)
}

/**
 * Drop-in replacement for `Modifier.clickable { }` that responds on press-down (not release) with
 * an instant, interruptible scale - the "response" and "interruptibility" principles applied to
 * every tap in the app, not just the showcase gestures.
 */
fun Modifier.fluidClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val interactionSource = remember { MutableInteractionSource() }
    val reduceMotion = LocalReduceMotion.current
    LaunchedEffect(interactionSource, reduceMotion) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scope.launch {
                    if (reduceMotion) scale.snapTo(0.97f)
                    else scale.animateTo(0.97f, spring(dampingRatio = 1f, stiffness = Motion.stiffness(0.15f)))
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> scope.launch {
                    if (reduceMotion) scale.snapTo(1f) else scale.animateTo(1f, Motion.default())
                }
            }
        }
    }
    this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick)
}
