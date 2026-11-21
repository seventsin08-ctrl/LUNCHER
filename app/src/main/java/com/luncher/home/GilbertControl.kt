package com.luncher.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.luncher.home.GilbertIntent.Kind as K

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
        "gilbertTitle" to "call me", "gilbertTalkStyle" to "talk style speaking style look when talking mode", "iconSize" to "icon size", "columns" to "columns", "rows" to "rows",
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
        "gilbertTitle" to listOf("auto", "sir", "boss", "name"),
        "gilbertTalkStyle" to listOf("jarvis", "vision"))
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
        "Add task: <thing> · show tasks · complete task 2 · remember: <note> · show notes",
        "Anything else — “explain a while loop in Taglish” — goes to his brain (needs a brain in 07 SYSTEM)",
        "Go to <window>")

    // ------------------------------------------------------------------ understanding you
    /** Turn what you said into an action (understanding is in [GilbertIntent], tested with 240+ phrasings). */
    fun parse(ctx: Context, input: String): Action? = GilbertIntent.parse(input)?.let { action(ctx, it) }

    fun action(ctx: Context, i: GilbertIntent.Intent): Action? {
        val s = Settings(ctx)
        fun cap(t: String) = t.replaceFirstChar { it.uppercase() }
        return when (i.kind) {
            K.TASK_ADD -> Action("Add task", i.text) { a -> "Task ${GilbertTasks.add(a, i.text)} added." }
            K.TASK_LIST -> Action("Tasks", "") { a ->
                val t = GilbertTasks.open(a); if (t.isEmpty()) "No open tasks." else t.joinToString("\n") { "${it.id}. ${it.title}" } }
            K.TASK_DONE -> Action("Complete task ${i.num}", "") { a -> if (GilbertTasks.complete(a, i.num ?: -1)) "Task ${i.num} complete." else "I couldn't find that open task." }
            K.NOTE_ADD -> Action("Save a note", i.text) { a -> GilbertMemory.addFact(a, i.text); "Saved, ${GilbertNarrator.ownerName(a) ?: "John"}." }
            K.NOTE_LIST -> Action("Notes", "") { a ->
                val n = GilbertMemory.facts(a).take(10); if (n.isEmpty()) "No saved notes." else n.mapIndexed { k, x -> "${k + 1}. $x" }.joinToString("\n") }
            K.FLASHLIGHT -> { val on = i.on != false
                Action("Flashlight ${if (on) "on" else "off"}", "") { a ->
                    runCatching { val cm = a.getSystemService(android.hardware.camera2.CameraManager::class.java)
                        cm.setTorchMode(cm.cameraIdList.first(), on) }.fold({ "Flashlight ${if (on) "on" else "off"}." }, { "This phone wouldn't let me use the flashlight." }) } }
            K.VOLUME -> {
                val dir = when (i.text) { "up", "max" -> AudioManager.ADJUST_RAISE; "mute" -> AudioManager.ADJUST_MUTE; "unmute" -> AudioManager.ADJUST_UNMUTE; else -> AudioManager.ADJUST_LOWER }
                val times = if (i.text == "max") 15 else 2
                Action("Volume ${i.text}", "") { a ->
                    val am = a.getSystemService(AudioManager::class.java)
                    repeat(times) { n -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, if (n == times - 1) AudioManager.FLAG_SHOW_UI else 0) }
                    when (i.text) { "mute" -> "Muted."; "unmute" -> "Sound's back."; "max" -> "Volume at maximum."; else -> "Volume ${i.text}." } } }
            K.BRIGHTNESS -> { val pct = i.num ?: 50
                Action("Brightness $pct%", "") { a ->
                    if (!android.provider.Settings.System.canWrite(a)) {
                        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${a.packageName}"))) }
                        "I need permission to change brightness — allow “Modify system settings” and ask again."
                    } else {
                        android.provider.Settings.System.putInt(a.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                        android.provider.Settings.System.putInt(a.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, pct * 255 / 100)
                        "Brightness at $pct percent."
                    } } }
            K.MEDIA -> { val key = when (i.text) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS }
                Action("Music: ${i.text}", "") { a ->
                    val am = a.getSystemService(AudioManager::class.java)
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                    when (i.text) { "play" -> "Playing."; "pause" -> "Paused."; "next" -> "Next track."; else -> "Previous track." } } }
            K.DND -> { val on = i.on != false
                Action("Do Not Disturb ${if (on) "on" else "off"}", "") { a ->
                    val nm = a.getSystemService(android.app.NotificationManager::class.java)
                    if (!nm.isNotificationPolicyAccessGranted) {
                        runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        "I need Do Not Disturb access — allow it for the launcher and ask again."
                    } else { nm.setInterruptionFilter(if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL); "Do Not Disturb ${if (on) "on" else "off"}." } } }
            K.LOCK -> Action("Lock the phone", "") { _ ->
                if (LuncherAccessibility.perform(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) "Locked." else "Turn on the launcher's gesture helper (Accessibility) so I can lock the phone." }
            K.ALARM -> { val hr = i.num ?: 7; val mn = i.num2 ?: 0
                Action("Alarm ${"%d:%02d".format(hr, mn)}", "") { a ->
                    runCatching { a.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_HOUR, hr)
                        .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, mn).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Gilbert")) }
                    "Alarm set for ${"%d:%02d".format(hr, mn)}." } }
            K.TIMER -> { val secs = i.num ?: 60
                val label = if (secs % 3600 == 0) "${secs / 3600} hour${if (secs > 3600) "s" else ""}" else if (secs % 60 == 0) "${secs / 60} minute${if (secs > 60) "s" else ""}" else "$secs seconds"
                Action("Timer $label", "") { a ->
                    runCatching { a.startActivity(Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, secs)
                        .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)) }; "Timer set for $label." } }
            K.CALL -> { val num = contactNumber(ctx, i.text) ?: i.text.takeIf { it.replace(Regex("[^0-9+]"), "").length >= 5 }
                Action("Call ${cap(i.text)}", "") { a ->
                    runCatching { a.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${num ?: ""}"))) }
                    if (num != null) "Dialer's ready — press call." else "I couldn't find ${cap(i.text)} in your contacts. Dialer's open." } }
            K.SEARCH -> Action("Search “${i.text}”", "") { a ->
                runCatching { a.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, i.text)) }; "Searching." }
            K.LAUNCHER_SETTINGS -> Action("Open launcher settings", "") { a -> a.startActivity(Intent(a, SettingsActivity::class.java)); "Settings open." }
            K.SETTINGS_PAGE -> { val act = when (i.text) {
                    "wi-fi" -> android.provider.Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                    "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY; "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS; "storage" -> android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS; else -> android.provider.Settings.ACTION_WIRELESS_SETTINGS }
                Action("Open ${cap(i.text)} settings", "") { a -> runCatching { a.startActivity(Intent(act)) }; "${cap(i.text)} settings." } }
            K.SET_NUMBER -> findProp(i.text, Integer.TYPE)?.let { p ->
                val v = i.num ?: return null
                Action("${cap(p.label)} → $v", "Was ${get(s, p)}.") { a -> set(Settings(a), p, v); "${cap(p.label)} set to $v." } }
                ?: Action("I don't have a setting called “${i.text}”", "", needsOk = false) { "" }
            K.SET_STYLE -> {
                val what = i.text.substringBefore('\u0000'); val target = i.text.substringAfter('\u0000').replace("android", "stack").replace("a-z", "list")
                findProp(what, String::class.java)?.let { p ->
                    val opt = OPTIONS[p.name]?.firstOrNull { it == target || target.startsWith(it) || it.startsWith(target) }
                    if (opt != null) Action("${cap(p.label)} → $opt", "Was ${get(s, p)}.") { a -> set(Settings(a), p, opt); "${cap(p.label)} is now $opt." }
                    else Action("${cap(p.label)} can be: ${OPTIONS[p.name]?.joinToString()}", "", needsOk = false) { "" }
                }
            }
            K.TOGGLE -> {
                val what = i.text.removeSuffix(" back").trim()
                findProp(what, java.lang.Boolean.TYPE)?.let { p ->
                    val cur = get(s, p) as? Boolean ?: false
                    val v = i.on ?: !cur
                    if (cur == v && i.on != null) Action("${cap(p.label)} is already ${if (v) "on" else "off"}", "", needsOk = false) { "" }
                    else Action("Turn ${if (v) "on" else "off"} ${p.label}", "") { a -> set(Settings(a), p, v); "${cap(p.label)} ${if (v) "on" else "off"}." }
                } ?: appFor(ctx, what)?.takeIf { i.on == true }?.let { (pkg, label) ->
                    Action("Open $label", "") { a -> a.packageManager.getLaunchIntentForPackage(pkg)?.let { a.startActivity(it) }; "Opening $label." } }
                ?: Action("I don't have a switch called “$what”", "", needsOk = false) { "" }
            }
            K.OPEN_APP -> appFor(ctx, i.text)?.let { (pkg, label) ->
                Action("Open $label", "") { a -> a.packageManager.getLaunchIntentForPackage(pkg)?.let { a.startActivity(it) }; "Opening $label." } }
                ?: Action("I couldn't find “${i.text}” on your phone", "", needsOk = false) { "" }
            K.REMIND -> { val at = GilbertPlanner.resolve(i.at)
                Action("Remind: ${i.text}", GilbertPlanner.label(at)) { a ->
                    GilbertPlanner.remind(a, i.text, at)
                    "Reminder set for ${GilbertPlanner.label(at)}: ${i.text}." + if (!GilbertPlanner.exactAllowed(a)) " It may ring a minute or two late until you allow exact alarms." else "" } }
            K.FOLLOW_ADD -> { val at = i.at?.let { GilbertPlanner.resolve(it) }
                Action("Follow up: ${i.text}", "") { a -> GilbertPlanner.follow(a, i.text, at); "Added to follow-ups${at?.let { " — I'll remind you " + GilbertPlanner.label(it) } ?: ""}." } }
            K.CALENDAR_ADD -> { val at = GilbertPlanner.resolve(i.at)
                Action("Calendar: ${i.text}", GilbertPlanner.label(at)) { a ->
                    runCatching { a.startActivity(Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                        .putExtra(android.provider.CalendarContract.Events.TITLE, cap(i.text))
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, at)
                        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, at + 3_600_000L)) }
                    "Calendar's open with “${cap(i.text)}” ${GilbertPlanner.label(at)} — tap Save." } }
            K.EMAIL_SEND -> { val who = i.text.substringBefore('\u0000'); val about = i.text.substringAfter('\u0000')
                Action("Email ${cap(who)}", about) { a ->
                    val addr = GilbertPlanner.emailFor(a, who)
                    runCatching { a.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:${addr ?: ""}"))
                        .putExtra(Intent.EXTRA_SUBJECT, cap(about))) }
                    if (addr != null) "Draft open to ${cap(who)}. Review it and press send." else "Draft open — I couldn't find ${cap(who)}'s email, so add it. Then press send." } }
            else -> null   // HELLO, BYE, HELP, WINDOW and the on-screen ones are handled by the console
        }
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
