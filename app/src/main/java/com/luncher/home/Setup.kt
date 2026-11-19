package com.luncher.home

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.core.app.NotificationManagerCompat

/**
 * Everything the launcher may need from Android, with a check ("is it allowed?") and the exact
 * screen to send you to. Settings › Permissions & Setup walks through the missing ones.
 */
object Setup {
    class Item(
        val id: String,
        val title: String,
        val why: String,
        val allowed: (Context) -> Boolean,
        /** Android runtime permission (asked with a pop-up) — or null when it's a settings screen. */
        val runtime: String? = null,
        val open: ((Activity) -> Unit)? = null
    )

    private fun pkgUri(ctx: Context) = Uri.parse("package:${ctx.packageName}")

    private fun go(a: Activity, vararg intents: Intent) {
        for (i in intents) if (runCatching { a.startActivity(i) }.isSuccess) return
    }

    fun isDefaultHome(ctx: Context): Boolean {
        val r = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == ctx.packageName
    }

    private fun accessibilityOn(ctx: Context): Boolean {
        if (LuncherAccessibility.instance != null) return true
        val list = SysSettings.Secure.getString(ctx.contentResolver, SysSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.contains(ctx.packageName + "/")
    }

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun items(ctx: Context): List<Item> {
        val list = ArrayList<Item>()
        list += Item("home", "Default Home App", "Makes Apple Launcher your Home Screen",
            { isDefaultHome(it) }, open = { a -> go(a, Intent(SysSettings.ACTION_HOME_SETTINGS), Intent(SysSettings.ACTION_SETTINGS)) })
        list += Item("notifications", "Notification Access", "Badges, Now Playing, messages in the side island and island live activities",
            { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) },
            open = { a ->
                val cn = ComponentName(a, NotificationService::class.java).flattenToString()
                go(a, Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                    .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", cn),
                    Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        list += Item("overlay", "Display Over Other Apps", "Island, side island, sidebar, chat window and voice orb on top of other apps",
            { SysSettings.canDrawOverlays(it) },
            open = { a -> go(a, Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(a)), Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION)) })
        list += Item("accessibility", "Accessibility Helper", "Lock screen, Recents, Quick Settings, and keeps the island and sidebar running",
            { accessibilityOn(it) },
            open = { a ->
                val cn = ComponentName(a, LuncherAccessibility::class.java).flattenToString()
                go(a, Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, cn),
                    Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        list += Item("battery", "Run in Background", "Stops Android from closing the island, sidebar and voice activation",
            { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
            open = { a ->
                go(a, Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(a)),
                    Intent(SysSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
        if (Build.VERSION.SDK_INT >= 33) list += Item("post", "Notifications", "Lets the launcher show its own small status notifications",
            { granted(it, Manifest.permission.POST_NOTIFICATIONS) }, runtime = Manifest.permission.POST_NOTIFICATIONS)
        list += Item("usage", "Usage Access", "Recently used apps in the side island and app switcher",
            { a -> runCatching {
                val ops = a.getSystemService(android.app.AppOpsManager::class.java)
                val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), a.packageName)
                    else @Suppress("DEPRECATION") ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), a.packageName)
                mode == android.app.AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false) },
            open = { a -> go(a, Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri(a)), Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS)) })
        list += Item("write", "Modify System Settings", "Brightness, rotation lock and ringtones",
            { SysSettings.System.canWrite(it) },
            open = { a -> go(a, Intent(SysSettings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri(a))) })
        list += Item("dnd", "Do Not Disturb Access", "Lets Focus modes silence notifications",
            { it.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            open = { a -> go(a, Intent(SysSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })
        list += Item("contacts", "Contacts", "Find people in Search",
            { granted(it, Manifest.permission.READ_CONTACTS) }, runtime = Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 31) list += Item("bt", "Nearby Devices", "Headphone battery in the AirPods-style pop-up",
            { granted(it, Manifest.permission.BLUETOOTH_CONNECT) }, runtime = Manifest.permission.BLUETOOTH_CONNECT)
        return list
    }

    /** vivo / iQOO phones also have their own background-start switch. */
    fun vivoBackground(a: Activity) = go(a,
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
        Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(a)))

    val isVivo get() = Build.MANUFACTURER.equals("vivo", true) || Build.BRAND.equals("iqoo", true)
}
