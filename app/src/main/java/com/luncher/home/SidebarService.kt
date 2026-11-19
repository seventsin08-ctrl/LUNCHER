package com.luncher.home

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Keeps the sidebar on screen everywhere — on top of games, full-screen videos and any app —
 * even if Android closes the launcher screen in the background to save memory.
 * Android requires a small "running" notification for this; it can be hidden in the phone's
 * notification settings.
 */
class SidebarService : Service() {
    companion object {
        private const val CHANNEL = "sidebar"
        private const val ID = 7301
        const val ACTION_REFRESH = "com.applelauncher.home.SIDEBAR_REFRESH"

        /** Start (or refresh) the always-on sidebar if it's turned on and allowed; otherwise stop it. */
        fun sync(ctx: Context): Boolean {
            val s = Settings(ctx)
            val allowed = LuncherAccessibility.instance != null || android.provider.Settings.canDrawOverlays(ctx)
            // Also keeps the launcher alive so the island (music, timers…) stays on top of other apps.
            val want = allowed && ((s.sidebarOn && s.sidebarOverApps) || (s.island && s.islandOverApps))
            val i = Intent(ctx, SidebarService::class.java).setAction(ACTION_REFRESH)
            if (want) runCatching { ctx.startForegroundService(i) }
            else runCatching { ctx.stopService(Intent(ctx, SidebarService::class.java)) }
            return want
        }
    }

    private var sidebar: Sidebar? = null
    private var tools: ToolsBar? = null
    @Volatile private var apps: List<AppInfo> = emptyList()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
        val settings = Settings(this)
        sidebar = Sidebar(this, null, settings, { apps }) { a ->
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
        }
        loadApps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        // Settings changed (side, size, apps…): rebuild the handle.
        main.post { runCatching { sidebar?.attach() } }
        // The tools bar: always on screen, on the Home Screen and over every app.
        // (The tools now live inside the Dynamic Island, so no separate tools window.)
        main.post { runCatching { tools?.detach(); tools = null } }
        if (intent?.action == ACTION_REFRESH) loadApps()
        return START_STICKY
    }

    private fun loadApps() {
        val s = Settings(this)
        val px = (s.iconSize * resources.displayMetrics.density).toInt() * 2
        io.execute {
            val list = runCatching { AppLoader.load(this, px, s.iconStyle, s.tint) }.getOrDefault(emptyList())
            apps = list
        }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sidebar", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the sidebar available over games and videos"
                setShowBadge(false)
            })
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, SettingsActivity::class.java).putExtra("page", "sidebar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_set_sidebar)
            .setContentTitle("Apple Launcher is running")
            .setContentText("Keeps the island and sidebar on top of your apps")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
    }

    override fun onDestroy() {
        runCatching { sidebar?.detach() }
        runCatching { tools?.detach() }; tools = null
        sidebar = null
        io.shutdownNow()
        super.onDestroy()
    }
}
