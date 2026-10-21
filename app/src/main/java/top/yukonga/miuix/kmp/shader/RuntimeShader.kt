// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.shader

import android.graphics.Shader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush

/**
 * The platform AGSL shader, exposed under the package miuix-glass expects.
 *
 * miuix 0.9.1 ships `miuix-blur` — which carries [top.yukonga.miuix.kmp.blur.Backdrop] and the
 * effect pipeline the glass renders through — but has no `miuix-shader` artefact at that version.
 * This is the whole surface the glass overlays need: build a shader from AGSL source, write its
 * uniforms, and paint it as a [Brush].
 */
typealias RuntimeShader = android.graphics.RuntimeShader

/** Paints this shader as a [Brush], so `drawRect(brush = shader.asBrush())` renders it. */
fun RuntimeShader.asBrush(): Brush = object : ShaderBrush() {
    override fun createShader(size: Size): Shader = this@asBrush
}

/**
 * Whether AGSL shaders can run on this device.
 *
 * The glass has a solid-fill fallback for every shader it draws, so a `false` here costs the
 * material, not the component.
 */
fun isRuntimeShaderSupported(): Boolean = top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported()
