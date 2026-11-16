package com.luncher.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent

object AppLoader {
    fun load(ctx: Context, iconPx: Int, style: String, tint: Int): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, 0)
        return activities
            .filter { it.activityInfo.packageName != ctx.packageName }
            .mapNotNull { ri ->
                runCatching {
                    AppInfo(
                        label = ri.loadLabel(pm).toString(),
                        component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                        icon = IconFactory.render(ri.loadIcon(pm), iconPx, style, tint)
                    )
                }.getOrNull()
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
    }
}
