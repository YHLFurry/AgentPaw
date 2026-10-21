// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.anim

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * HyperOS Folme spring: a damping ratio plus a response time, turned into a Compose spring.
 *
 * miuix 0.9.1's `anim` package ships the easings but not this conversion, so it lives here. The
 * response is the period of the undamped oscillation in seconds, which fixes the stiffness at
 * `(2pi / response)^2`; the damping ratio is passed straight through.
 *
 * @param damping The damping ratio. 1 settles without overshoot; below 1 overshoots once.
 * @param response The period of the undamped oscillation, in seconds. Must be greater than zero.
 * @param visibilityThreshold The settle threshold, in the animated type's own units.
 */
fun <T> folmeSpring(
    damping: Float,
    response: Float,
    visibilityThreshold: T? = null,
): SpringSpec<T> {
    require(response > 0f) { "A spring's response must be greater than zero, was $response" }
    val angularFrequency = 2f * PI.toFloat() / response
    return spring(
        dampingRatio = damping,
        stiffness = angularFrequency * angularFrequency,
        visibilityThreshold = visibilityThreshold,
    )
}
