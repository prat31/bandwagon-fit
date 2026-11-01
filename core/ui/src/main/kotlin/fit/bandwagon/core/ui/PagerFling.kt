package fit.bandwagon.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable

/**
 * A snappy, tactile fling for tab pagers.
 *
 * [PagerDefaults.flingBehavior]'s `snapPositionalThreshold` defaults to 0.5 — a slow drag has to
 * cross half the page before it commits. Lowering it to 0.15 makes a much smaller swipe flip the
 * page (Wear's "high sensitivity" preset is 0.125), and the lightly-bouncy spring gives the snap a
 * physical, tactile settle. A fast fling always advances regardless of distance.
 */
@Composable
fun rememberTactilePagerFlingBehavior(state: PagerState): TargetedFlingBehavior =
    PagerDefaults.flingBehavior(
        state = state,
        snapPositionalThreshold = 0.15f,
        snapAnimationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
    )
