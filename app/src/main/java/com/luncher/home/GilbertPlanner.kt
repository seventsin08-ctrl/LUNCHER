package com.luncher.home

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Gilbert's planner: reminders (they ring even when his console is closed), follow-ups, and
 * read-only views of your calendar, Gmail notifications, photos and downloads.
 */
object GilbertPlanner {
    class Item(val id: Int, val text: String, val at: Long, val done: Boolean)
    private fun p(ctx: Context) = ctx.getSharedPreferences("gil_planner", Context.MODE_PRIVATE)

    private fun load(ctx: Context, key: String): List<Item> = runCatching {
        val a = JSONArray(p(ctx).getString(key, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Item(o.optInt("id"), o.optString("t"), o.optLong("at"), o.optBoolean("d")) } }
    }.getOrDefault(emptyList())
    private fun save(ctx: Context, key: String, l: List<Item>) {
        val a = JSONArray(); l.takeLast(150).forEach { a.put(JSONObject().put("id", it.id).put("t", it.text).put("at", it.at).put("d", it.done)) }
        p(ctx).edit().putString(key, a.toString()).apply()
    }
    private fun nextId(ctx: Context) = p(ctx).getInt("next", 1).also { p(ctx).edit().putInt("next", it + 1).apply() }

    // ------------------------------------------------------------------ when → time
    /** Turn "in 10 minutes" / "tomorrow at 8" / "on friday" into a real time. */
    fun resolve(w: GilbertIntent.When?, defaultHour: Int = 9): Long {
        val now = Calendar.getInstance()
        if (w == null || w.isEmpty) return now.timeInMillis + 60 * 60_000L
        w.inMin?.let { return now.timeInMillis + it * 60_000L }
        val c = now.clone() as Calendar
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (w.date != null && w.month != null) {   // "Nov 28"
            c.set(Calendar.MONTH, w.month - 1); c.set(Calendar.DAY_OF_MONTH, w.date)
            if (c.timeInMillis < now.timeInMillis - 86_400_000L) c.add(Calendar.YEAR, 1)
        }
        if (w.day > 0) c.add(Calendar.DAY_OF_YEAR, w.day)
        w.weekday?.let { wd -> var add = (wd - c.get(Calendar.DAY_OF_WEEK) + 7) % 7; if (add == 0 && w.hour != null && w.hour <= now.get(Calendar.HOUR_OF_DAY)) add = 7; c.add(Calendar.DAY_OF_YEAR, add) }
        c.set(Calendar.HOUR_OF_DAY, w.hour ?: defaultHour); c.set(Calendar.MINUTE, if (w.hour != null) w.min else 0)
        if (c.timeInMillis <= now.timeInMillis && w.date == null) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun label(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val dayDiff = ((t - today.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis) / 86_400_000L).toInt()
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(c.time)
        return when (dayDiff) { 0 -> "today at $time"; 1 -> "tomorrow at $time"; in 2..6 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(c.time) + " at $time"
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(c.time) + " at $time" }
    }

    // ------------------------------------------------------------------ reminders
    fun reminders(ctx: Context) = load(ctx, "rem").filter { !it.done }.sortedBy { it.at }

    fun remind(ctx: Context, text: String, at: Long): Item {
        val it = Item(nextId(ctx), text.replaceFirstChar { c -> c.uppercase() }, at, false)
        save(ctx, "rem", load(ctx, "rem") + it)
        schedule(ctx, it)
        return it
    }
    fun cancelReminder(ctx: Context, id: Int) {
        save(ctx, "rem", load(ctx, "rem").map { if (it.id == id) Item(it.id, it.text, it.at, true) else it })
        (ctx.getSystemService(AlarmManager::class.java))?.cancel(pi(ctx, id, ""))
    }
    private fun pi(ctx: Context, id: Int, text: String) = PendingIntent.getBroadcast(ctx, 9000 + id,
        Intent(ctx, GilbertReminderReceiver::class.java).putExtra("id", id).putExtra("text", text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Exact when allowed; otherwise within a few minutes (Android's rule). */
    fun schedule(ctx: Context, item: Item) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pi(ctx, item.id, item.text)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.at, pi)
            else am.setWindow(AlarmManager.RTC_WAKEUP, item.at, 60_000L, pi)
        }.onFailure { runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, item.at, 60_000L, pi) } }
    }
    fun exactAllowed(ctx: Context) = android.os.Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    fun rescheduleAll(ctx: Context) = reminders(ctx).filter { it.at > System.currentTimeMillis() - 60_000 }.forEach { schedule(ctx, it) }

    fun fired(ctx: Context, id: Int, text: String) {
        save(ctx, "rem", load(ctx, "rem").map { if (it.id == id) Item(it.id, it.text, it.at, true) else it })
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel("gil_remind") == null)
            nm.createNotificationChannel(NotificationChannel("gil_remind", "Gilbert reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, GilbertChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        runCatching { nm.notify(8000 + id, Notification.Builder(ctx, "gil_remind").setSmallIcon(R.drawable.ic_cc_mic)
            .setContentTitle("Gilbert · Reminder").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()) }
        GilbertMind.log(ctx, "Reminder: $text")
    }

    // ------------------------------------------------------------------ follow-ups
    fun follows(ctx: Context) = load(ctx, "fol").filter { !it.done }
    fun follow(ctx: Context, text: String, at: Long?): Item {
        val it = Item(nextId(ctx), text.replaceFirstChar { c -> c.uppercase() }, at ?: 0L, false)
        save(ctx, "fol", load(ctx, "fol") + it)
        if (at != null && at > 0) remind(ctx, "Follow up: ${it.text}", at)
        return it
    }
    fun doneFollow(ctx: Context, id: Int) = save(ctx, "fol", load(ctx, "fol").map { if (it.id == id) Item(it.id, it.text, it.at, true) else it })

    // ------------------------------------------------------------------ calendar (read)
    class Event(val title: String, val start: Long, val end: Long, val allDay: Boolean, val where: String)
    fun calendarAllowed(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun events(ctx: Context, span: String): List<Event> {
        if (!calendarAllowed(ctx)) return emptyList()
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        if (span == "tomorrow") c.add(Calendar.DAY_OF_YEAR, 1)
        val from = if (span == "today") System.currentTimeMillis() - 2 * 3_600_000L else c.timeInMillis
        val to = c.timeInMillis + (if (span == "week") 7 else 1) * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to) }.build()
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                val out = ArrayList<Event>()
                while (cur.moveToNext() && out.size < 25) out += Event(cur.getString(0) ?: "(no title)", cur.getLong(1), cur.getLong(2), cur.getInt(3) == 1, cur.getString(4) ?: "")
                out
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
    fun eventTime(e: Event) = if (e.allDay) "All day" else java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(e.start))

    // ------------------------------------------------------------------ Gmail (from its notifications)
    fun emails(ctx: Context): List<GilbertArchive.Entry> =
        GilbertArchive.entries(ctx, 2).filter { it.app.contains("gmail", true) || it.app.equals("Email", true) || it.app.contains("outlook", true) }.take(10)

    fun emailFor(ctx: Context, who: String): String? = runCatching {
        if (who.contains("@")) return who
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        ctx.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    // ------------------------------------------------------------------ photos & files
    class Media(val uri: android.net.Uri, val name: String, val time: Long, val mime: String)
    fun photos(ctx: Context, screenshots: Boolean, n: Int = 12): List<Media> = runCatching {
        val sel = if (screenshots && android.os.Build.VERSION.SDK_INT >= 29) "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" else null
        val args = if (sel != null) arrayOf("%Screenshot%") else null
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED), sel, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val out = ArrayList<Media>()
            while (c.moveToNext() && out.size < n) out += Media(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                c.getString(1) ?: "", c.getLong(2) * 1000, "image/*")
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun downloads(ctx: Context, n: Int = 12): List<Media> = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 29) return emptyList()
        ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_ADDED, MediaStore.Downloads.MIME_TYPE), null, null,
            "${MediaStore.Downloads.DATE_ADDED} DESC")?.use { c ->
            val out = ArrayList<Media>()
            while (c.moveToNext() && out.size < n) out += Media(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                c.getString(1) ?: "", c.getLong(2) * 1000, c.getString(3) ?: "*/*")
            out
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

/** A reminder's time came: show it (works even when the launcher's console is closed). */
class GilbertReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") { GilbertPlanner.rescheduleAll(ctx); return }
        GilbertPlanner.fired(ctx, intent.getIntExtra("id", 0), intent.getStringExtra("text") ?: "Reminder")
    }
}
