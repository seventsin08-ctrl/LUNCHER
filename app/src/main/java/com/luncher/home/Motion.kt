package com.luncher.home

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * iOS-style spring physics. Springs keep their velocity when they are interrupted,
 * so a press, release and re-press blend together instead of jumping.
 */
object Motion {
    /** Settings animation scale: 0 = no animation, 0.6 fast, 1 normal, 1.5 slow. */
    var scale = 1f
    /** Spring Motion (iPhone feature 1). Off = same timing but no bounce/overshoot. */
    var bouncy = true

    // Presets tuned to feel like iOS.
    /** Quick, no bounce: finger-down feedback, snapping. */
    val SNAPPY = Spec(1400f, 0.9f)
    /** Gentle bounce: releasing an icon, items landing. */
    val BOUNCY = Spec(520f, 0.62f)
    /** Smooth settle with a hint of overshoot: icons sliding to make room, the dock. */
    val FLUID = Spec(420f, 0.82f)
    /** Large, calm motions: the home screen zooming back. */
    val CALM = Spec(260f, 0.95f)

    class Spec(val stiffness: Float, val damping: Float)

    private fun tagFor(prop: DynamicAnimation.ViewProperty): Int = when {
        prop === DynamicAnimation.SCALE_X -> R.id.spring_scale_x
        prop === DynamicAnimation.SCALE_Y -> R.id.spring_scale_y
        prop === DynamicAnimation.ALPHA -> R.id.spring_alpha
        prop === DynamicAnimation.TRANSLATION_X -> R.id.spring_translation_x
        else -> R.id.spring_translation_y
    }

    fun spring(
        view: View,
        prop: DynamicAnimation.ViewProperty,
        target: Float,
        spec: Spec,
        start: Float? = null,
        endAction: (() -> Unit)? = null
    ) {
        val tag = tagFor(prop)
        (view.getTag(tag) as? SpringAnimation)?.cancel()
        if (scale <= 0f) {
            prop.setValue(view, target)
            endAction?.invoke()
            return
        }
        if (start != null) prop.setValue(view, start)
        // Slower settings = softer spring; faster = stiffer.
        val stiffness = (spec.stiffness / (scale * scale)).coerceIn(30f, 10000f)
        val anim = SpringAnimation(view, prop, target).apply {
            spring = SpringForce(target).apply {
                this.stiffness = stiffness
                dampingRatio = if (bouncy) spec.damping else maxOf(spec.damping, 1f)
            }
            setMinimumVisibleChange(
                if (prop === DynamicAnimation.TRANSLATION_X || prop === DynamicAnimation.TRANSLATION_Y)
                    DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS
                else DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            )
            if (endAction != null) {
                addEndListener { _, canceled, _, _ -> if (!canceled) endAction() }
            }
        }
        view.setTag(tag, anim)
        anim.start()
    }

    fun scaleTo(view: View, target: Float, spec: Spec, start: Float? = null, endAction: (() -> Unit)? = null) {
        spring(view, DynamicAnimation.SCALE_X, target, spec, start)
        spring(view, DynamicAnimation.SCALE_Y, target, spec, start, endAction)
    }

    fun alphaTo(view: View, target: Float, spec: Spec, start: Float? = null) =
        spring(view, DynamicAnimation.ALPHA, target.coerceIn(0f, 1f), spec, start)

    fun translateTo(view: View, x: Float, y: Float, spec: Spec, startX: Float? = null, startY: Float? = null) {
        spring(view, DynamicAnimation.TRANSLATION_X, x, spec, startX)
        spring(view, DynamicAnimation.TRANSLATION_Y, y, spec, startY)
    }

    /** Stop every spring on a view (e.g. before it is reused). */
    fun cancel(view: View) {
        for (id in intArrayOf(
            R.id.spring_scale_x, R.id.spring_scale_y, R.id.spring_alpha,
            R.id.spring_translation_x, R.id.spring_translation_y
        )) (view.getTag(id) as? SpringAnimation)?.cancel()
    }
}
