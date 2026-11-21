package com.luncher.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Gilbert's hands: he can work every switch, slider and style in the launcher, plus phone
 * functions (apps, flashlight, volume, brightness, media, Do Not Disturb, lock, alarms, settings
 * pages, calls). He turns what you say/type into an [Action] — and NOTHING runs until you tap
 * Allow (Rule 3). Every action is written to his log.
 */
object GilbertControl {
    class Action(val title: String, val detail: String, val needsOk: Boolean = true, val run: (Activity) -> String)

    // ------------------------------------------------------------------ launcher settings (all of them)
    private class Prop(val name: String, val type: Class<*>, val words: Set<String>, val label: String)

    /** Friendly names for settings (anything else is matched by its own name). */
    private val ALIAS = mapOf(
        "dockOn" to "dock", "libraryOn" to "app library", "island" to "dynamic island", "islandFace" to "gilbert face orb",
        "sidebarOn" to "sidebar", "sideHud" to "side island", "soundFx" to "sound effects sounds", "gilbertSpeaks" to "voice gilbert voice speak",
        "gilbertEcho" to "echo ai echo", "simpleMode" to "simple mode", "gameMode" to "game mode", "gameBall" to "game ball",
        "islandBeats" to "beats beat", "lyrics" to "lyrics", "haptics" to "vibration haptics", "showLabels" to "labels app names",
        "badges" to "badges notification dots", "blur" to "blur", "glass" to "glass", "animations" to "animations",
        "gilbertCalm" to "calm mode", "alwaysShowMessages" to "always show messages", "doubleTapLock" to "double tap lock",
        "headsetPopup" to "headphone popup", "islandTools" to "tools camera tools", "sideChatCard" to "chat window chat card",
        "gilbertTalk" to "gilbert talks bubbles", "islandOverApps" to "island over apps", "sidebarOverApps" to "sidebar over apps",
        "musicKeepLast" to "keep last song", "gilbertArchive" to "archive", "soundsInDnd" to "sounds in do not disturb",
        "pageEffect" to "page transition swipe effect", "animSpeed" to "animation speed", "launchAnim" to "app opening animation",
        "dockStyle" to "dock style", "libraryStyle" to "library style", "musicStyle" to "player style music style",
        "notifStyle" to "notification style", "islandStyle" to "island style", "gilbertFace" to "face style",
        "gilbertTitle" to "call me", "iconSize" to "icon size", "columns" to "columns", "rows" to "rows",
        "islandScale" to "island size", "dim" to "wallpaper dim", "soundFxVolume" to "effects volume", "labelSize" to "label size",
        "iconCorner" to "icon corners", "dockIconPct" to "dock icon size", "sidebarCols" to "sidebar columns")
    /** Choices for style settings. */
    private val OPTIONS = mapOf(
        "pageEffect" to listOf("depth", "flat", "stack", "cube", "roll", "flip", "card", "tilt", "zoom", "fade"),
        "animSpeed" to listOf("slow", "normal", "fast", "instant"),
        "launchAnim" to listOf("match", "zoom", "fade", "slide", "none"),
        "dockStyle" to listOf("glass", "dark", "light", "clear"),
        "libraryStyle" to listOf("folders", "list"),
        "notifStyle" to listOf("launcher", "original", "both"),
        "islandStyle" to listOf("black", "glass", "outline", "color"),
        "gilbertFace" to listOf("orb", "classic", "happy", "dots", "sleepy"),
        "gilbertTitle" to listOf("auto", "sir", "boss", "name"))
    private val SKIP = Regex("(Dx|Dy|Y|X|Seen|Shown|Uri|Color|Order|order|Apps|apps|hidden|pending|Pinned|Placement|Nudge|Angle|Mode)$")

    private fun words(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()

    private val props: List<Prop> by lazy {
        Settings::class.java.methods.mapNotNull { m ->
            if (m.parameterCount != 1 || !m.name.startsWith("set")) return@mapNotNull null
            val name = m.name.removePrefix("set").replaceFirstChar { it.lowercase() }
            val t = m.parameterTypes[0]
            if (t != java.lang.Boolean.TYPE && t != Integer.TYPE && t != String::class.java) return@mapNotNull null
            if (SKIP.containsMatchIn(name) && name !in ALIAS) return@mapNotNull null
            if (t == String::class.java && name !in OPTIONS) return@mapNotNull null
            val alias = ALIAS[name]
            Prop(name, t, words(name) + (alias?.let { words(it) } ?: emptySet()), alias?.split(" ")?.take(3)?.joinToString(" ") ?: words(name).joinToString(" "))
        }
    }

    private fun get(s: Settings, p: Prop): Any? = runCatching {
        val g = Settings::class.java.methods.firstOrNull { it.parameterCount == 0 && (it.name == "get" + p.name.replaceFirstChar { c -> c.uppercase() } || it.name == p.name) }
        g?.invoke(s)
    }.getOrNull()
    private fun set(s: Settings, p: Prop, v: Any) {
        Settings::class.java.methods.first { it.name == "set" + p.name.replaceFirstChar { c -> c.uppercase() } && it.parameterCount == 1 }.invoke(s, v)
    }

    private fun findProp(q: String, type: Class<*>? = null): Prop? {
        val w = words(q) - setOf("the", "my", "turn", "on", "off", "set", "to", "change", "make", "show", "hide", "enable", "disable", "please", "gilbert")
        if (w.isEmpty()) return null
        val alias = ALIAS.entries.firstOrNull { e -> e.value.split(" ").any { q.contains(it) && it.length > 3 } && (type == null || props.any { p -> p.name == e.key && p.type == type }) }
        return props.filter { type == null || it.type == type }
            .map { it to (it.words.count { x -> x in w } * 2 - (it.words.size - it.words.count { x -> x in w }) * 0.3f + if (ALIAS[it.name]?.let { a -> q.contains(a) } == true) 3 else 0) }
            .filter { it.second > 0.9f }.maxByOrNull { it.second }?.first
            ?: alias?.let { a -> props.firstOrNull { it.name == a.key } }
    }

    /** Everything he can do, for the help list. */
    val HELP = listOf(
        "Turn on/off <any feature> — dock, app library, island, sidebar, side island, sounds, haptics, simple mode, game mode, lyrics, beats, blur, glass, labels, badges, calm mode…",
        "Set <style> to <choice> — page transition to cube · dock style to dark · animation speed to fast · island style to glass · notification style to both",
        "Set <size> to <number> — icon size 60 · columns 5 · rows 6 · island size 110",
        "Open <app> · open launcher settings · open wifi / bluetooth / battery / display / sound settings",
        "Flashlight on/off · volume up/down/mute · brightness 50% · play / pause / next / previous",
        "Do not disturb on/off · lock the phone · set alarm 6:30 · timer 10 minutes · call <contact> · search <anything>",
        "Go to <window> · remember <note>")

    // ------------------------------------------------------------------ understanding you
    fun parse(ctx: Context, input: String): Action? {
        val q = input.trim().lowercase().removePrefix("gilbert").trim(' ', ',', '.').removePrefix("please").trim()
        if (q.isEmpty()) return null
        val s = Settings(ctx)
        val onWords = Regex("^(turn on|switch on|enable|activate|show|start|open up|bring back)\\b")
        val offWords = Regex("^(turn off|switch off|disable|deactivate|hide|stop|remove|kill)\\b")

        // ---- phone functions first (specific words)
        if (Regex("flash ?light|torch").containsMatchIn(q)) {
            val on = !q.contains("off")
            return Action("Flashlight ${if (on) "on" else "off"}", "Turns the camera light ${if (on) "on" else "off"}.") { a ->
                runCatching { val cm = a.getSystemService(android.hardware.camera2.CameraManager::class.java)
                    cm.setTorchMode(cm.cameraIdList.first(), on) }.fold({ "Flashlight ${if (on) "on" else "off"}." }, { "This phone wouldn't let me use the flashlight." })
            }
        }
        if (q.contains("volume")) {
            val am = ctx.getSystemService(AudioManager::class.java)
            val dir = when { q.contains("up") || q.contains("louder") || q.contains("max") -> AudioManager.ADJUST_RAISE; q.contains("mute") || q.contains("silent") -> AudioManager.ADJUST_MUTE
                q.contains("unmute") -> AudioManager.ADJUST_UNMUTE; else -> AudioManager.ADJUST_LOWER }
            val times = if (q.contains("max")) 15 else 2
            return Action("Volume ${when (dir) { AudioManager.ADJUST_RAISE -> "up"; AudioManager.ADJUST_MUTE -> "mute"; AudioManager.ADJUST_UNMUTE -> "unmute"; else -> "down" }}", "Media volume.") {
                repeat(times) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, if (it == times - 1) AudioManager.FLAG_SHOW_UI else 0) }; "Volume adjusted."
            }
        }
        if (q.contains("brightness")) {
            val pct = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull() ?: if (q.contains("up") || q.contains("max")) 100 else if (q.contains("low") || q.contains("down")) 15 else 50
            return Action("Brightness ${pct.coerceIn(1, 100)}%", "Screen brightness.") { a ->
                if (!android.provider.Settings.System.canWrite(a)) {
                    runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${a.packageName}"))) }
                    "I need permission to change brightness — allow “Modify system settings” and ask again."
                } else {
                    android.provider.Settings.System.putInt(a.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    android.provider.Settings.System.putInt(a.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, (pct.coerceIn(1, 100) * 255 / 100))
                    "Brightness set to $pct percent."
                }
            }
        }
        Regex("^(play|pause|resume|next|skip|previous|prev|back)( (the )?(song|music|track))?$").find(q)?.let { m ->
            val key = when (m.groupValues[1]) { "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS }
            return Action("Music: ${m.groupValues[1]}", "Controls what's playing.") { a ->
                val am = a.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)); "Done."
            }
        }
        if (Regex("do not disturb|\\bdnd\\b|focus mode").containsMatchIn(q)) {
            val on = !q.contains("off")
            return Action("Do Not Disturb ${if (on) "on" else "off"}", "Silences calls and notifications.") { a ->
                val nm = a.getSystemService(android.app.NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) {
                    runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    "I need Do Not Disturb access — allow it for the launcher and ask again."
                } else { nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL); "Do Not Disturb ${if (on) "on" else "off"}." }
            }
        }
        if (Regex("^lock( the)?( phone| screen)?$").containsMatchIn(q))
            return Action("Lock the phone", "Locks the screen now.") { _ ->
                if (LuncherAccessibility.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) "Locked." else "Turn on the launcher's gesture helper (Accessibility) so I can lock the phone." }
        Regex("(?:alarm|wake me)(?: up)?(?: for| at)? (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?").find(q)?.let { m ->
            var hr = m.groupValues[1].toInt(); val mn = m.groupValues[2].toIntOrNull() ?: 0
            if (m.groupValues[3] == "pm" && hr < 12) hr += 12; if (m.groupValues[3] == "am" && hr == 12) hr = 0
            return Action("Alarm ${"%d:%02d".format(hr, mn)}", "Sets an alarm in your Clock app.") { a ->
                runCatching { a.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, hr)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, mn).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Gilbert")) }
                "Alarm set for ${"%d:%02d".format(hr, mn)}."
            }
        }
        Regex("timer (?:for )?(\\d+) ?(sec|second|min|minute|hour|hr)").find(q)?.let { m ->
            val n = m.groupValues[1].toInt(); val secs = when { m.groupValues[2].startsWith("h") -> n * 3600; m.groupValues[2].startsWith("m") -> n * 60; else -> n }
            return Action("Timer ${m.groupValues[1]} ${m.groupValues[2]}", "Starts a timer in your Clock app.") { a ->
                runCatching { a.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)) }; "Timer started."
            }
        }
        Regex("^call (.+)$").find(q)?.let { m ->
            val who = m.groupValues[1].trim()
            val num = contactNumber(ctx, who)
            return Action("Call ${who.replaceFirstChar { it.uppercase() }}", if (num != null) "Opens the dialer with $num — you press call." else "I couldn't find that contact — I'll open the dialer.") { a ->
                runCatching { a.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${num ?: ""}"))) }; "Dialer open."
            }
        }
        Regex("^(?:search|google|look up)(?: for)? (.+)$").find(q)?.let { m ->
            return Action("Search “${m.groupValues[1]}”", "Opens a web search.") { a ->
                runCatching { a.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, m.groupValues[1])) }; "Searching."
            }
        }
        Regex("^(?:remember|note|take a note)(?: that)? (.+)$").find(q)?.let { m ->
            return Action("Save a note", m.groupValues[1]) { a -> GilbertMemory.addFact(a, m.groupValues[1]); "Noted." }
        }
        // Settings pages
        val pages = linkedMapOf("wi-?fi|internet" to android.provider.Settings.ACTION_WIFI_SETTINGS, "bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
            "battery" to Intent.ACTION_POWER_USAGE_SUMMARY, "display|screen settings" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
            "sound|ringtone" to android.provider.Settings.ACTION_SOUND_SETTINGS, "storage" to android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "location|gps" to android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS, "hotspot|mobile data|data" to android.provider.Settings.ACTION_WIRELESS_SETTINGS)
        if (q.startsWith("open") || q.contains("settings")) {
            if (Regex("launcher settings|your settings|gilbert settings|^settings$|^open settings$").containsMatchIn(q))
                return Action("Open launcher settings", "Every switch in the launcher.") { a -> a.startActivity(Intent(a, SettingsActivity::class.java)); "Settings open." }
            for ((re, act) in pages) if (Regex(re).containsMatchIn(q))
                return Action("Open ${re.split("|").first().replace("-?", "-").replaceFirstChar { it.uppercase() }} settings", "Phone settings page.") { a ->
                    runCatching { a.startActivity(Intent(act)) }; "Opened." }
        }
        // ---- launcher settings
        val sizeM = Regex("^(?:set|change|make)? ?(.+?) (?:to|=) (\\d{1,4})%?$").find(q)
        if (sizeM != null) findProp(sizeM.groupValues[1], Integer.TYPE)?.let { p ->
            val v = sizeM.groupValues[2].toInt()
            return Action("${p.label.replaceFirstChar { it.uppercase() }} → $v", "Was ${get(s, p)}.") { a -> set(Settings(a), p, v); "${p.label.replaceFirstChar { it.uppercase() }} set to $v." }
        }
        val styleM = Regex("^(?:set|change|make|switch)? ?(?:the )?(.+?) (?:to|into|=) ([a-z ]+)$").find(q)
        if (styleM != null) {
            val target = styleM.groupValues[2].trim().replace("android", "stack").replace("a-z", "list")
            findProp(styleM.groupValues[1], String::class.java)?.let { p ->
                val opt = OPTIONS[p.name]?.firstOrNull { it == target || target.startsWith(it) || it.startsWith(target) }
                if (opt != null) return Action("${p.label.replaceFirstChar { it.uppercase() }} → $opt", "Was ${get(s, p)}.") { a -> set(Settings(a), p, opt); "${p.label.replaceFirstChar { it.uppercase() }} is now $opt." }
                return Action("I can set ${p.label} to: ${OPTIONS[p.name]?.joinToString()}", "Say one of those.", needsOk = false) { "" }
            }
        }
        val on = onWords.containsMatchIn(q); val off = offWords.containsMatchIn(q); val toggle = q.startsWith("toggle")
        if (on || off || toggle || q.endsWith(" on") || q.endsWith(" off")) {
            val what = q.replace(onWords, "").replace(offWords, "").removePrefix("toggle").removeSuffix(" on").removeSuffix(" off").trim()
            findProp(what, java.lang.Boolean.TYPE)?.let { p ->
                val cur = get(s, p) as? Boolean ?: false
                val v = when { toggle -> !cur; off || q.endsWith(" off") -> false; else -> true }
                if (cur == v && !toggle) return Action("${p.label.replaceFirstChar { it.uppercase() }} is already ${if (v) "on" else "off"}", "", needsOk = false) { "" }
                return Action("Turn ${if (v) "on" else "off"} ${p.label}", "Launcher setting.") { a -> set(Settings(a), p, v); "${p.label.replaceFirstChar { it.uppercase() }} ${if (v) "on" else "off"}." }
            }
        }
        // ---- apps
        Regex("^(?:open|launch|start|go to|run) (.+)$").find(q)?.let { m ->
            val name = m.groupValues[1].removePrefix("the ").removeSuffix(" app").trim()
            appFor(ctx, name)?.let { (pkg, label) ->
                return Action("Open $label", "Launches the app.") { a -> a.packageManager.getLaunchIntentForPackage(pkg)?.let { a.startActivity(it) }; "Opening $label." }
            }
        }
        return null
    }

    private fun appFor(ctx: Context, name: String): Pair<String, String>? = runCatching {
        val pm = ctx.packageManager
        val all = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        all.firstOrNull { it.second.equals(name, true) } ?: all.firstOrNull { it.second.lowercase().startsWith(name) }
            ?: all.firstOrNull { it.second.lowercase().contains(name) }
    }.getOrNull()

    private fun contactNumber(ctx: Context, who: String): String? = runCatching {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        ctx.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$who%"), null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
