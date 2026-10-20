package app.astiko.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val FILL_DURATION_MS = 100

private val SpinEasing = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)

/**
 * Reports whether a tab is becoming selected after being unselected.
 * First composition and a tap on the already active tab must stay still.
 */
internal class TabSelectionGate(
    selected: Boolean,
) {
    private var wasSelected = selected

    fun shouldPlay(selected: Boolean): Boolean {
        if (selected == wasSelected) return false
        wasSelected = selected
        return selected
    }
}

/**
 * The tab glyph, outline when unselected and filled when selected, plus
 * the motion that plays when the tab becomes selected. The crossfade is
 * the closest a static vector pair gets to the fill morph of the Play
 * Store bar, which animates a variable font's FILL axis.
 */
@Composable
internal fun TabIcon(
    tab: MainTab,
    selected: Boolean,
) {
    val fillProgress =
        animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = tween(durationMillis = FILL_DURATION_MS),
            label = "tab-fill",
        )
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val selectionGate = remember { TabSelectionGate(selected) }

    LaunchedEffect(selected) {
        val play = selectionGate.shouldPlay(selected)

        if (!selected) {
            scale.snapTo(1f)
            rotation.snapTo(0f)
            return@LaunchedEffect
        }

        if (!play) return@LaunchedEffect

        when (tab.motion) {
            TabMotion.POP -> {
                scale.animateTo(0.86f, tween(durationMillis = 84, easing = EaseInOut))
                scale.animateTo(1.07f, tween(durationMillis = 98, easing = EaseInOut))
                scale.animateTo(1f, tween(durationMillis = 98, easing = EaseInOut))
            }

            TabMotion.ROCK -> {
                rotation.snapTo(-7f)
                rotation.animateTo(2.5f, tween(durationMillis = 200, easing = EaseInOut))
                rotation.animateTo(-0.8f, tween(durationMillis = 120, easing = EaseInOut))
                rotation.animateTo(0f, tween(durationMillis = 80, easing = EaseInOut))
            }

            TabMotion.SPIN -> {
                rotation.snapTo(0f)
                rotation.animateTo(180f, tween(durationMillis = 340, easing = SpinEasing))
            }
        }
    }

    Box(
        Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            rotationZ = rotation.value
        },
    ) {
        Box(Modifier.graphicsLayer { alpha = 1f - fillProgress.value }) { tab.outlinedIcon() }
        Box(Modifier.graphicsLayer { alpha = fillProgress.value }) { tab.filledIcon() }
    }
}
