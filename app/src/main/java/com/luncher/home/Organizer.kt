package com.luncher.home

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Settings
import java.time.LocalTime

/** Focus modes: Work, Study, Sleep, Personal. Each changes the Home Screen and notifications. */
object Focus {
    class Mode(val id: String, val name: String, val emoji: String, val color: Int, val desc: String)

    val modes = listOf(
        Mode("work", "Work", "💼", 0xFF0A84FF.toInt(), "Work dock & page · priority notifications only"),
        Mode("study", "Study", "📚", 0xFFFF9F0A.toInt(), "No badges · priority notifications only"),
        Mode("sleep", "Sleep", "🌙", 0xFF5E5CE6.toInt(), "Dim Home Screen · no badges · alarms only"),
        Mode("personal", "Personal", "🏠", 0xFF30D158.toInt(), "Personal dock & page · everything allowed")
    )

    private fun p(ctx: Context) = ctx.getSharedPreferences("luncher", Context.MODE_PRIVATE)

    fun current(ctx: Context): Mode? = p(ctx).getString("focus_mode", null)?.let { id -> modes.firstOrNull { it.id == id } }

    fun set(ctx: Context, id: String?) {
        p(ctx).edit().putString("focus_mode", id).apply()
        applyDoNotDisturb(ctx, id)
    }

    fun homePage(ctx: Context, id: String): Int = p(ctx).getInt("focus_page_$id", 0)
    fun setHomePage(ctx: Context, id: String, page: Int) = p(ctx).edit().putInt("focus_page_$id", page).apply()

    fun silences(ctx: Context, id: String): Boolean = p(ctx).getBoolean("focus_dnd_$id", id != "personal")
    fun setSilences(ctx: Context, id: String, on: Boolean) {
        p(ctx).edit().putBoolean("focus_dnd_$id", on).apply()
        if (current(ctx)?.id == id) applyDoNotDisturb(ctx, id)
    }

    fun hidesBadges(id: String?) = id == "sleep" || id == "study"
    fun extraDim(id: String?) = if (id == "sleep") 45 else 0

    fun sleepSchedule(ctx: Context) = p(ctx).getBoolean("focus_sleep_schedule", false)
    fun setSleepSchedule(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("focus_sleep_schedule", on).apply()

    /** Turn Sleep on 10:30 PM–7 AM (and off again in the morning) when the schedule is on. */
    fun applySchedule(ctx: Context): Boolean {
        if (!sleepSchedule(ctx)) return false
        val now = LocalTime.now()
        val night = now.isAfter(LocalTime.of(22, 30)) || now.isBefore(LocalTime.of(7, 0))
        val cur = current(ctx)?.id
        val auto = p(ctx).getBoolean("focus_auto", false)
        if (night && cur == null) { set(ctx, "sleep"); p(ctx).edit().putBoolean("focus_auto", true).apply(); return true }
        if (!night && cur == "sleep" && auto) { set(ctx, null); p(ctx).edit().putBoolean("focus_auto", false).apply(); return true }
        return false
    }

    fun hasDndAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun applyDoNotDisturb(ctx: Context, id: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        val filter = when {
            id == null -> NotificationManager.INTERRUPTION_FILTER_ALL
            !silences(ctx, id) -> NotificationManager.INTERRUPTION_FILTER_ALL
            id == "sleep" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        }
        runCatching { nm.setInterruptionFilter(filter) }
    }

    fun dndSettingsIntent() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}

/** Sorts apps into App Library folders, like iOS. */
object AppCategories {
    private val cache = HashMap<String, String>()

    private val keywords = listOf(
        "Social" to listOf("whatsapp", "facebook", "instagram", "messenger", "telegram", "snapchat", "twitter",
            "tiktok", "discord", "viber", "wechat", "line", "threads", "reddit", "linkedin", "pinterest", "signal",
            "messaging", "mms", "dialer", "contacts", "zhiliaoapp", "musically", "bereal", "tumblr"),
        "Entertainment" to listOf("youtube", "netflix", "spotify", "music", "video", "disney", "hulu", "prime",
            "twitch", "player", "podcast", "soundcloud", "deezer", "tidal", "hbo", "iqiyi", "viu", "crunchyroll", "anime"),
        "Games" to listOf("game", "games", "supercell", "roblox", "mojang", "minecraft", "pubg", "tencent.ig",
            "mobile.legends", "moonton", "genshin", "hoyoverse", "garena", "king.", "playrix", "rovio", "chess"),
        "Finance" to listOf("bank", "pay", "wallet", "gcash", "maya", "bpi", "bdo", "finance", "money", "crypto",
            "binance", "coins", "invest", "paypal", "venmo", "revolut", "wise"),
        "Shopping & Food" to listOf("shop", "shopee", "lazada", "amazon", "ebay", "temu", "shein", "zalora",
            "grabfood", "foodpanda", "food", "mcdonald", "jollibee", "starbucks", "store", "market", "aliexpress"),
        "Travel" to listOf("maps", "waze", "uber", "grab", "airbnb", "booking", "agoda", "trip", "flight",
            "airline", "travel", "transit", "joyride", "angkas"),
        "Photo & Video" to listOf("camera", "photo", "gallery", "album", "lightroom", "snapseed", "capcut",
            "vsco", "picsart", "canva", "editor", "inshot", "remini"),
        "Productivity" to listOf("docs", "sheets", "slides", "office", "word", "excel", "powerpoint", "notion",
            "drive", "dropbox", "calendar", "notes", "keep", "todo", "mail", "gmail", "outlook", "zoom", "teams",
            "meet", "slack", "trello", "evernote", "chatgpt", "openai", "gemini", "bard", "claude", "copilot"),
        "Education" to listOf("duolingo", "classroom", "khan", "coursera", "udemy", "quizlet", "brainly",
            "dictionary", "translate", "learn", "school", "study"),
        "Health & Fitness" to listOf("health", "fit", "strava", "workout", "sleep", "meditat", "calm", "headspace",
            "step", "run", "yoga")
    )

    fun categoryOf(ctx: Context, app: AppInfo): String {
        cache[app.packageName]?.let { return it }
        val pkg = app.packageName.lowercase()
        val label = app.label.lowercase()
        var cat: String? = null
        val ai = runCatching { ctx.packageManager.getApplicationInfo(app.packageName, 0) }.getOrNull()
        if (ai != null) {
            cat = when (ai.category) {
                ApplicationInfo.CATEGORY_GAME -> "Games"
                ApplicationInfo.CATEGORY_SOCIAL -> "Social"
                ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> "Entertainment"
                ApplicationInfo.CATEGORY_IMAGE -> "Photo & Video"
                ApplicationInfo.CATEGORY_MAPS -> "Travel"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
                ApplicationInfo.CATEGORY_NEWS -> "Information & Reading"
                else -> null
            }
            if (cat == null && (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0) cat = "Games"
        }
        if (cat == null) {
            cat = keywords.firstOrNull { (_, words) -> words.any { pkg.contains(it) || label.contains(it) } }?.first
        }
        val sys = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val result = cat ?: if (sys) "Utilities" else "Other"
        cache[app.packageName] = result
        return result
    }

    /**
     * Folders in iOS order: Suggestions (recently used), Recently Added (newest installs),
     * then categories with at least one app.
     */
    fun build(ctx: Context, apps: List<AppInfo>, recentKeys: List<String>): List<Pair<String, List<AppInfo>>> {
        val out = ArrayList<Pair<String, List<AppInfo>>>()
        val byKey = apps.associateBy { it.key }
        val suggestions = recentKeys.mapNotNull { byKey[it] }.take(8)
        if (suggestions.isNotEmpty()) out += "Suggestions" to suggestions
        val pm = ctx.packageManager
        val recentlyAdded = apps.mapNotNull { a ->
            runCatching { a to pm.getPackageInfo(a.packageName, 0).firstInstallTime }.getOrNull()
        }.filter { System.currentTimeMillis() - it.second < 14L * 24 * 3600 * 1000 }
            .sortedByDescending { it.second }.map { it.first }.take(8)
        if (recentlyAdded.isNotEmpty()) out += "Recently Added" to recentlyAdded
        val groups = apps.groupBy { categoryOf(ctx, it) }
        val order = listOf("Social", "Entertainment", "Games", "Productivity", "Photo & Video", "Finance",
            "Shopping & Food", "Travel", "Information & Reading", "Education", "Health & Fitness", "Utilities", "Other")
        for (name in order) groups[name]?.takeIf { it.isNotEmpty() }?.let { out += name to it.sortedBy { a -> a.label.lowercase() } }
        return out
    }

    fun clear() = cache.clear()
}

/** Extra Spotlight results: settings pages, quick actions and contacts. */
object SpotlightExtras {
    class Result(val title: String, val subtitle: String, val icon: String, val action: () -> Unit)

    private val settingsPages = listOf(
        Triple("Wi-Fi", "📶", Settings.ACTION_WIFI_SETTINGS),
        Triple("Bluetooth", "ᛒ", Settings.ACTION_BLUETOOTH_SETTINGS),
        Triple("Mobile Data", "⇅", Settings.ACTION_DATA_ROAMING_SETTINGS),
        Triple("Airplane Mode", "✈", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Triple("Display & Brightness", "☀", Settings.ACTION_DISPLAY_SETTINGS),
        Triple("Sounds & Vibration", "🔊", Settings.ACTION_SOUND_SETTINGS),
        Triple("Battery", "🔋", Intent.ACTION_POWER_USAGE_SUMMARY),
        Triple("Notifications", "🔔", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"),
        Triple("Apps", "📱", Settings.ACTION_APPLICATION_SETTINGS),
        Triple("Storage", "💾", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Triple("Location", "📍", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Triple("Security & Face Unlock", "🔐", Settings.ACTION_SECURITY_SETTINGS),
        Triple("Privacy", "🛡", Settings.ACTION_PRIVACY_SETTINGS),
        Triple("Accessibility", "♿", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Triple("Date & Time", "🕒", Settings.ACTION_DATE_SETTINGS),
        Triple("Language & Keyboard", "⌨", Settings.ACTION_LOCALE_SETTINGS),
        Triple("Hotspot & Tethering", "📡", Settings.ACTION_WIRELESS_SETTINGS),
        Triple("NFC", "📳", Settings.ACTION_NFC_SETTINGS),
        Triple("Wallpaper", "🖼", Intent.ACTION_SET_WALLPAPER),
        Triple("Developer Options", "🛠", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Triple("About Phone", "ℹ", Settings.ACTION_DEVICE_INFO_SETTINGS)
    )

    fun settings(query: String, open: (Intent) -> Unit): List<Result> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return settingsPages.filter { (name, _, _) ->
            name.lowercase().contains(q) || name.lowercase().split(' ', '&').any { it.trim().startsWith(q) }
        }.take(4).map { (name, icon, action) -> Result(name, "Settings", icon) { open(Intent(action)) } }
    }

    /** Natural shortcuts: "timer 10", "5 min timer", "flashlight", "focus work", "control center". */
    fun shortcuts(query: String, timer: (Int) -> Unit, controlCenter: () -> Unit, focus: (String?) -> Unit): List<Result> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val out = ArrayList<Result>()
        val num = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull()
        for (m in Focus.modes) {
            if (q.contains("focus") || m.name.lowercase().startsWith(q) || q.contains(m.id)) {
                if (q.contains("focus") || q == m.id || m.name.lowercase().startsWith(q) && q.length >= 3)
                    out += Result("Turn on ${m.name} Focus", "Focus", m.emoji) { focus(m.id) }
            }
        }
        if (q.contains("focus") && (q.contains("off") || q.contains("stop"))) {
            out += Result("Turn off Focus", "Focus", "🌙") { focus(null) }
        }
        return out.take(4)
    }

    fun hasContactsPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    class Contact(val name: String, val number: String?, val uri: android.net.Uri)

    fun contacts(ctx: Context, query: String): List<Contact> {
        val q = query.trim()
        if (q.length < 2 || !hasContactsPermission(ctx)) return emptyList()
        val out = ArrayList<Contact>()
        runCatching {
            ctx.contentResolver.query(
                android.net.Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, android.net.Uri.encode(q)),
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.HAS_PHONE_NUMBER),
                null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            )?.use { c ->
                while (c.moveToNext() && out.size < 4) {
                    val id = c.getLong(0)
                    val name = c.getString(2) ?: continue
                    var number: String? = null
                    if (c.getInt(3) > 0) {
                        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", arrayOf(id.toString()), null
                        )?.use { pc -> if (pc.moveToFirst()) number = pc.getString(0) }
                    }
                    out += Contact(name, number, ContactsContract.Contacts.getLookupUri(id, c.getString(1)))
                }
            }
        }
        return out
    }
}
