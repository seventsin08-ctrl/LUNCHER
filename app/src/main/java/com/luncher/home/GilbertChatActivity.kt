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
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val root = FrameLayout(this)
        setContentView(root)
        val s = Settings(this)
        runCatching { ThemeEngine.resolve(this, s) }
        root.post { PocherConsole.show(this, root, s) { finish(); overridePendingTransition(0, android.R.anim.fade_out) } }
    }

    private var picking = false
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        picking = requestCode == GilbertChat.REQ_BRAIN || requestCode == PocherConsole.REQ_SCAN
        super.startActivityForResult(intent, requestCode, options)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        picking = false
        val uri = data?.data
        if (requestCode == GilbertChat.REQ_BRAIN && resultCode == RESULT_OK && uri != null) GilbertChat.onBrainPicked(this, uri)
        if (requestCode == PocherConsole.REQ_SCAN && resultCode == RESULT_OK && uri != null) PocherConsole.onScanPicked(this, uri)
    }

    override fun onPause() {
        super.onPause()
        // Leaving (home / another app): close quietly.
        if (!isChangingConfigurations && !picking) finish()
    }
}

/** Quick Settings tile: pull down the shade and tap "Pocher" to chat from anywhere. */
class GilbertTileService : android.service.quicksettings.TileService() {
    override fun onStartListening() {
        qsTile?.apply { label = "Pocher"; state = android.service.quicksettings.Tile.STATE_INACTIVE; updateTile() }
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
