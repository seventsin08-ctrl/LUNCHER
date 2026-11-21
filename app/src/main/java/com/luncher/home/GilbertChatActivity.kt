package com.luncher.home

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Talk to Gilbert from anywhere: a see-through window that opens over whatever app you're in
 * (from Gilbert over apps, the Sidebar, or the Quick Settings tile). Close it and you're right
 * back where you were.
 */
class GilbertChatActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        HighRefresh.apply(this)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // Never leave a picture of the console in the recent-apps screen.
        if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { setRecentsScreenshotEnabled(false) }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val root = FrameLayout(this)
        setContentView(root)
        val s = Settings(this)
        runCatching { ThemeEngine.resolve(this, s) }
        root.post { GilbertConsole.show(this, root, s) { finishAndRemoveTask(); overridePendingTransition(0, android.R.anim.fade_out) } }
    }

    companion object { @JvmStatic var picking = false }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        picking = false
        if (requestCode == GilbertConsole.REQ_PHOTOS && PhotoWatcher.allowed(this)) {
            PhotoWatcher.register(this)
            android.widget.Toast.makeText(this, "Gilbert will pick up photos saved from your chats", android.widget.Toast.LENGTH_SHORT).show()
            GilbertConsole.refresh()
        }
    }
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        picking = requestCode == GilbertChat.REQ_BRAIN || requestCode == GilbertConsole.REQ_SCAN
        super.startActivityForResult(intent, requestCode, options)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        picking = false
        val uri = data?.data
        if (requestCode == GilbertChat.REQ_BRAIN && resultCode == RESULT_OK && uri != null) GilbertChat.onBrainPicked(this, uri)
        if (requestCode == GilbertConsole.REQ_SCAN && resultCode == RESULT_OK && uri != null) GilbertConsole.onScanPicked(this, uri)
    }

    /** Close the console and take its task out of recent apps (unless a picker/permission is open). */
    private fun leave() { if (!isChangingConfigurations && !picking && !isFinishing) finishAndRemoveTask() }

    // Swiping up / pressing Home or Recents: close right away, so the console never sits in the
    // recent-apps screen (Android keeps it running during the gesture, before onPause).
    override fun onUserLeaveHint() { super.onUserLeaveHint(); leave() }
    override fun onTopResumedActivityChanged(isTop: Boolean) {
        super.onTopResumedActivityChanged(isTop)
        if (!isTop) window.decorView.postDelayed({ if (!hasWindowFocus()) leave() }, 250)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) window.decorView.postDelayed({ if (!hasWindowFocus()) leave() }, 400)
    }
    override fun onStop() { super.onStop(); leave() }

    override fun onPause() {
        super.onPause()
        // Leaving (home / another app): close quietly.
        if (!isChangingConfigurations && !picking) finishAndRemoveTask()
    }
}

/** Quick Settings tile: pull down the shade and tap "Gilbert" to chat from anywhere. */
class GilbertTileService : android.service.quicksettings.TileService() {
    override fun onStartListening() {
        qsTile?.apply { label = "Gilbert"; state = android.service.quicksettings.Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        val i = android.content.Intent(this, GilbertChatActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        } else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}

object GilbertLauncher {
    fun open(ctx: android.content.Context) {
        runCatching {
            ctx.startActivity(android.content.Intent(ctx, GilbertChatActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
