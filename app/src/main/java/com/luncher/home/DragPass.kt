package com.luncher.home

import android.view.DragEvent
import android.view.View
import android.view.WindowManager

/**
 * Our floating windows (tools ring, side island, side bars, game ball, pop-ups) sit above every
 * app. While something is being dragged — a file from Files, a photo, text — Android sends the
 * drag only to the top window under your finger, so those windows used to act as invisible
 * blocks over the share targets below them. Now, the moment any drag starts, each of our windows
 * turns see-through to touches, and goes back to normal when the drag ends.
 */
object DragPass {
    private const val NOT_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    fun watch(wm: WindowManager?, view: View) {
        var weAdded = false
        fun set(off: Boolean) {
            val l = view.layoutParams as? WindowManager.LayoutParams ?: return
            if (off) {
                if (l.flags and NOT_TOUCHABLE != 0) return       // already see-through: leave it alone
                l.flags = l.flags or NOT_TOUCHABLE; weAdded = true
            } else {
                if (!weAdded) return
                l.flags = l.flags and NOT_TOUCHABLE.inv(); weAdded = false
            }
            runCatching { wm?.updateViewLayout(view, l) }
        }
        val giveBack = Runnable { set(false) }   // safety net in case the drag never reports its end
        view.setOnDragListener { _, e ->
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> {
                    set(true)
                    view.removeCallbacks(giveBack); view.postDelayed(giveBack, 60_000)
                    true   // stay subscribed so we hear when it ends
                }
                DragEvent.ACTION_DRAG_ENDED -> { view.removeCallbacks(giveBack); set(false); true }
                else -> false
            }
        }
    }
}
