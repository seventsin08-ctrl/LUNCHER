package com.luncher.home

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Patterns

/**
 * "Origin island"-style services. Everything here comes from public Android APIs:
 * ongoing notifications (navigation, clock, calls, recording, transfers, workouts),
 * the clipboard (Copy & go) and the microphone / camera in-use callbacks.
 */
class OngoingInfo(
    val key: String,
    val pkg: String,
    val type: String,
    val title: String,
    val text: String,
    /** Chronometer base (System.currentTimeMillis time) or 0. */
    val chronoBase: Long,
    val countDown: Boolean,
    val progress: Int,
    val progressMax: Int,
    val actions: List<Pair<String, PendingIntent>>,
    val contentIntent: PendingIntent?
) {
    /** Right-hand text in the island: a running time or a percentage. */
    fun trailing(now: Long = System.currentTimeMillis()): String? = when {
        chronoBase > 0 -> {
            val ms = if (countDown) (chronoBase - now).coerceAtLeast(0) else (now - chronoBase).coerceAtLeast(0)
            val s = ms / 1000
            if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format("%02d:%02d", s / 60, s % 60)
        }
        progressMax > 0 -> "${(progress * 100L / progressMax).toInt().coerceIn(0, 100)}%"
        else -> null
    }
}

object IslandServices {
    /** Every service, in the order shown in Settings: id, name, description. */
    val services = listOf(
        Triple("navigation", "Navigation", "Turn-by-turn directions from Maps, Waze…"),
        Triple("clock", "Clock", "In-progress timers, stopwatches and snoozed alarms"),
        Triple("call", "Calls", "Ongoing calls with hang-up and speaker buttons"),
        Triple("recording", "Record audio & screen", "Voice and screen recorders while recording"),
        Triple("progress", "Transfers & downloads", "Downloads, uploads, file sharing and backups"),
        Triple("workout", "Sport", "Workouts and runs from fitness apps"),
        Triple("privacy", "Microphone & camera in use", "Shows when an app is using them"),
        Triple("copy", "Copy & go", "Copied phone numbers, links and meetings")
    )

    private val clockPkgs = setOf(
        "com.google.android.deskclock", "com.android.deskclock", "com.android.BBKClock",
        "com.sec.android.app.clockpackage", "com.oneplus.deskclock", "com.coloros.alarmclock",
        "com.miui.clock", "com.huawei.deskclock"
    )
    private val navPkgs = setOf(
        "com.google.android.apps.maps", "com.waze", "com.here.app.maps", "com.sygic.aura",
        "com.mapswithme.maps.pro", "app.organicmaps", "net.osmand", "com.grabtaxi.passenger"
    )

    /** Which island service an ongoing notification belongs to, or null to ignore it. */
    fun classify(own: String, sbn: StatusBarNotification): String? {
        val n = sbn.notification
        if (sbn.packageName == own) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val ex = n.extras ?: return null
        if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null // music has its own player
        val ongoing = sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val cat = n.category
        val pkg = sbn.packageName
        val words = (ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() + " " +
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() + " " + pkg).lowercase()
        return when {
            cat == Notification.CATEGORY_CALL && ongoing -> "call"
            cat == Notification.CATEGORY_NAVIGATION || (pkg in navPkgs && ongoing) -> "navigation"
            cat == Notification.CATEGORY_STOPWATCH -> "clock"
            pkg in clockPkgs && (ongoing || cat == Notification.CATEGORY_ALARM) -> "clock"
            cat == "workout" || (ongoing && (words.contains("workout") || pkg.contains("fitness") ||
                pkg.contains("strava") || pkg.contains("health"))) -> "workout"
            ongoing && (words.contains("record") || pkg.contains("recorder") || pkg.contains("screenrecord")) -> "recording"
            ongoing && ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 -> "progress"
            cat == Notification.CATEGORY_PROGRESS && ongoing -> "progress"
            else -> null
        }
    }

    fun toInfo(type: String, sbn: StatusBarNotification): OngoingInfo {
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: services.first { it.first == type }.second
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            ?.toString().orEmpty()
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val actions = n.actions.orEmpty().mapNotNull { a ->
            val t = a.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pi = a.actionIntent ?: return@mapNotNull null
            t to pi
        }.take(3)
        return OngoingInfo(
            sbn.key, sbn.packageName, type, title, text,
            if (chrono && n.`when` > 0) n.`when` else 0L,
            ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
            ex.getInt(Notification.EXTRA_PROGRESS, 0),
            if (ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) 0 else ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            actions, n.contentIntent
        )
    }

    /** Fire a notification's PendingIntent from the launcher (allowed while we're in front). */
    fun send(ctx: Context, pi: PendingIntent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else pi.send()
        }
    }

    // ------------------------------------------------------------------ Copy & go

    class Copied(val kind: String, val text: String)

    private var lastClipStamp = 0L

    /**
     * Looks at the clipboard when the Home Screen comes back. Only a newly copied item is read
     * (Android may show a small "pasted from your clipboard" notice when it is).
     */
    fun checkClipboard(ctx: Context): Copied? {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
        val desc = runCatching { cm.primaryClipDescription }.getOrNull() ?: return null
        val stamp = desc.timestamp
        val prefs = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)
        if (lastClipStamp == 0L) lastClipStamp = prefs.getLong("clip_stamp", 0L)
        if (stamp <= lastClipStamp) return null
        lastClipStamp = stamp
        prefs.edit().putLong("clip_stamp", stamp).apply()
        // Ignore anything copied a long time ago.
        if (System.currentTimeMillis() - stamp > 10 * 60_000L) return null
        if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return null
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() && it.length < 2000 } ?: return null
        return classifyText(text)
    }

    fun classifyText(text: String): Copied? {
        val lower = text.lowercase()
        val meeting = Regex("https?://(meet\\.google\\.com|[\\w.-]*zoom\\.us/j|teams\\.microsoft\\.com|teams\\.live\\.com)\\S*")
            .find(lower)?.let { text.substring(it.range) }
        if (meeting != null) return Copied("meeting", meeting)
        val url = Patterns.WEB_URL.matcher(text)
        if (url.find() && (url.group().contains('.') && (url.end() - url.start()) >= text.length * 0.6)) {
            val u = url.group()
            return Copied("url", if (u.startsWith("http")) u else "https://$u")
        }
        val digits = text.count { it.isDigit() }
        if (digits in 7..15 && Patterns.PHONE.matcher(text).matches()) return Copied("phone", text)
        return null
    }

    fun dial(num: String) = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)))
    fun sms(num: String) = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)))
    fun saveContact(num: String) = Intent(Intent.ACTION_INSERT).apply {
        type = android.provider.ContactsContract.Contacts.CONTENT_TYPE
        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, num)
    }
    fun open(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    fun share(text: String, pkg: String? = null): Intent {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return if (pkg != null) i.setPackage(pkg) else Intent.createChooser(i, null)
    }

    /** Apps that can receive shared text, for the Pinned Services picker. */
    fun shareTargets(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
}
