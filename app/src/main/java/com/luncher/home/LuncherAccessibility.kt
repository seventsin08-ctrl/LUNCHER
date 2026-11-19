package com.luncher.home

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional helper. When the user turns it on, the launcher can open the system
 * Recents (real app previews), lock the screen with a double-tap, and pull down
 * notifications. It reads nothing on screen.
 */
class LuncherAccessibility : AccessibilityService() {
    companion object {
        var instance: LuncherAccessibility? = null
            private set

        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Lets the headphone pop-up appear on top of any app.
        runCatching { HeadsetPopup.get(applicationContext) }
        // Bring the always-on sidebar back (e.g. after a restart).
        runCatching { SidebarService.sync(applicationContext) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
