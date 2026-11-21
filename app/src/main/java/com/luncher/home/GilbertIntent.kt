package com.luncher.home

/**
 * What did you ask Gilbert to do? Pure text understanding (no Android), so it can be tested
 * with hundreds of phrasings — English, Tagalog / Taglish and speech-recognition slips.
 * [GilbertControl] turns the result into a real action.
 */
object GilbertIntent {
    data class Intent(val kind: Kind, val text: String = "", val on: Boolean? = null, val num: Int? = null, val num2: Int? = null, val at: When? = null)

    /** A time someone said: "in 10 minutes", "at 5 pm", "tomorrow at 8", "on friday". */
    data class When(val inMin: Int? = null, val hour: Int? = null, val min: Int = 0, val day: Int = 0, val weekday: Int? = null,
                    val month: Int? = null, val date: Int? = null) {
        val isEmpty get() = inMin == null && hour == null && day == 0 && weekday == null && date == null
    }

    enum class Kind {
        HELLO, BYE, HELP, WINDOW,
        TASK_ADD, TASK_LIST, TASK_DONE, NOTE_ADD, NOTE_LIST,
        FLASHLIGHT, VOLUME, BRIGHTNESS, MEDIA, DND, LOCK, ALARM, TIMER, CALL, SEARCH,
        SETTINGS_PAGE, LAUNCHER_SETTINGS, SET_NUMBER, SET_STYLE, TOGGLE, OPEN_APP,
        REMIND, REMINDER_LIST, FOLLOW_ADD, FOLLOW_LIST, SEND_TO_TASK, SHOW_IT,
        CALENDAR, CALENDAR_ADD, EMAIL_CHECK, EMAIL_SEND, PHOTOS, FILES, AGENDA
    }

    // ------------------------------------------------------------------ times
    private val WEEK = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val TIME_RE = Regex("\\b(?:at |by |sa |ng )?(\\d{1,2})(?:[:.](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?|am|pm|ng umaga|ng gabi|ng hapon|o'?clock)?\\b")

    /** Find a time in [q]; returns it and [q] with the time words removed. */
    fun parseWhen(q: String): Pair<When, String> {
        var rest = " $q "
        var w = When()
        Regex("\\b(?:in|after|within|mamaya(?:ng)?|pagkatapos ng) (\\w+|an?|half an?) ?(minutes?|mins?|hours?|hrs?|minuto|oras)\\b").find(rest)?.let { m ->
            val n = if (m.groupValues[1].startsWith("half")) 30 else (m.groupValues[1].toIntOrNull() ?: mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "five" to 5, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "isa" to 1, "dalawa" to 2)[m.groupValues[1]])
            if (n != null) {
                val unitH = m.groupValues[2].startsWith("h") || m.groupValues[2] == "oras"
                w = w.copy(inMin = if (m.groupValues[1].startsWith("half")) 30 else if (unitH) n * 60 else n)
                rest = rest.replace(m.value, " ")
            }
        }
        if (Regex("\\b(?:tomorrow|bukas)\\b").containsMatchIn(rest)) { w = w.copy(day = 1); rest = rest.replace(Regex("\\b(?:tomorrow|bukas)\\b"), " ") }
        Regex("\\b(?:on |this |next |sa )?(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\b").find(rest)?.let { m ->
            w = w.copy(weekday = WEEK.indexOf(m.groupValues[1]) + 1); rest = rest.replace(m.value, " ")
        }
        when {
            Regex("\\b(?:tonight|mamayang gabi)\\b").containsMatchIn(rest) -> { w = w.copy(hour = 20); rest = rest.replace(Regex("\\b(?:tonight|mamayang gabi)\\b"), " ") }
            Regex("\\bthis (?:afternoon)\\b|\\bmamayang hapon\\b").containsMatchIn(rest) -> { w = w.copy(hour = 15); rest = rest.replace(Regex("\\bthis afternoon\\b|\\bmamayang hapon\\b"), " ") }
            Regex("\\b(?:this )?morning\\b").containsMatchIn(rest) -> { w = w.copy(hour = 8); rest = rest.replace(Regex("\\b(?:this |in the )?morning\\b"), " ") }
            Regex("\\b(?:this )?evening\\b").containsMatchIn(rest) -> { w = w.copy(hour = 19); rest = rest.replace(Regex("\\b(?:this |in the )?evening\\b"), " ") }
        }
        if (w.inMin == null) TIME_RE.findAll(rest).firstOrNull { m ->
            val suffix = m.groupValues[3]; val hasAt = m.value.trimStart().let { it.startsWith("at ") || it.startsWith("by ") || it.startsWith("sa ") || it.startsWith("ng ") }
            (suffix.isNotEmpty() || hasAt || m.groupValues[2].isNotEmpty()) && (m.groupValues[1].toIntOrNull() ?: 99) in 0..23
        }?.let { m ->
            var h = m.groupValues[1].toInt(); val mn = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues[3].replace(".", "")
            if ((ap == "pm" || ap.contains("gabi") || ap.contains("hapon")) && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (ap.isEmpty() && !ap.contains("o") && h in 1..6 && w.hour == null) h += 12   // "at 5" usually means 5 pm
            if (w.hour != null && w.hour!! >= 12 && h < 12) h += 12                           // "tonight at 8"
            w = w.copy(hour = h, min = mn); rest = rest.replace(m.value, " ")
        }
        if (w.inMin == null && w.hour == null && Regex("\\b(?:later|mamaya)\\b").containsMatchIn(rest)) { w = w.copy(inMin = 60); rest = rest.replace(Regex("\\b(?:later|mamaya)\\b"), " ") }
        return w to rest.replace(Regex("\\s+"), " ").trim(' ', ',', '.')
    }

    // ------------------------------------------------------------------ clean up what was heard
    private val WAKE = Regex("^(?:hey |ok(?:ay)? |hi )?(?:gilbert|gil|system(?:s)? ?on ?line|sistem online)\\b[\\s,.:!-]*")
    private val FILLER = Regex("^(?:(?:please|pls|plz|can you|could you|would you|will you|kindly|i want you to|i want to|i need you to|i'd like you to|go ahead and|just|now|uhm?|um+|uh+|so|okay|ok|hey|yo|pwede(?: mo)?(?: bang)?|paki(?:-| )?|pakisuyo|sige|mo|nga|naman)[\\s,]+)+")
    private val TAIL = Regex("(?:[\\s,]+(?:please|pls|for me|now|right now|po|nga|naman|na|mo|thanks|thank you|gilbert|asap))+[.!?]*$")

    fun normalize(input: String): String {
        var q = input.trim().lowercase()
            .replace('’', '\'').replace(Regex("[“”\"]"), "")
            .replace(Regex("\\s+"), " ")
        repeat(3) { q = q.replace(WAKE, "").replace(FILLER, "") }
        q = q.replace(TAIL, "").trim(' ', ',', '.', '!', '?')
        // Common speech-recognition splits / spellings.
        q = q.replace(Regex("\\bflash light\\b"), "flashlight").replace(Regex("\\bwi fi\\b|\\bwifi\\b"), "wi-fi")
            .replace(Regex("\\bblue tooth\\b"), "bluetooth").replace(Regex("\\bdo not disturbed\\b"), "do not disturb")
            .replace(Regex("\\btorch light\\b"), "flashlight").replace(Regex("\\bnot\\b(?= disturb)"), "not")
        return q
    }

    // Tagalog verbs → English intent words.
    private val OPEN_TL = Regex("^(?:buksan|i-?open|open|pabukas|ibukas)\\b(?: mo)?(?: nga)?(?: (?:ang|ng|yung|'yung|ung|si|sa))?\\s*")
    private val ON_TL = Regex("^(?:i-?on|buhayin|sindihan|paandarin)\\b(?: mo)?(?: nga)?(?: (?:ang|yung|ung))?\\s*")
    private val OFF_TL = Regex("^(?:i-?off|patayin|isara|alisin|tanggalin)\\b(?: mo)?(?: nga)?(?: (?:ang|yung|ung))?\\s*")

    private fun word(n: String): Int? = n.toIntOrNull() ?: mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "isa" to 1, "dalawa" to 2, "tatlo" to 3, "apat" to 4, "lima" to 5, "a" to 1, "an" to 1)[n]

    // ------------------------------------------------------------------ wake word
    private val WAKE_RE = Regex("(?:s[iy]st[ae]ms?'?s?|sister|cistern|system's)\\s*(?:is\\s*)?(?:on\\s*-?\\s*l[iy]ne?|online|onlin|on lie|on lion|on time|on lane)")
    /** Did they say the wake phrase? Returns what came after it ("" if nothing), or null. */
    fun wake(heard: String): String? {
        val t = heard.lowercase().trim()
        Regex("\\b(?:hey|hi|okay|ok) (?:gilbert|gil bert|guilbert|gilberts?)\\b").find(t)?.let { return t.substring(it.range.last + 1).trim(' ', ',', '.') }
        WAKE_RE.find(t)?.let { return t.substring(it.range.last + 1).trim(' ', ',', '.') }
        val w = t.split(Regex("\\s+"))
        if (w.size <= 4 && (w.firstOrNull() == "online" || t.startsWith("on line"))) return t.substringAfter("line").trim(' ', ',', '.')
        return null
    }

    // ------------------------------------------------------------------ understanding
    fun parse(input: String): Intent? {
        val raw = input.trim()
        // Short goodbyes / greetings first (before filler words get stripped: "ok na", "salamat po").
        val plain = raw.lowercase().trim(' ', ',', '.', '!', '?')
        if (Regex("^(?:that'?s all|that is all|goodbye|bye|good ?night|stop listening|never ?mind|nothing|salamat(?: po)?|thanks?(?: gilbert)?|thank you(?: gilbert)?|wala na|ok na|okay na|sige na|tama na)$").matches(plain)) return Intent(Kind.BYE)
        // "pakibuksan" = paki + buksan
        val q0 = normalize(raw).replace(Regex("^paki-?(?=[a-z])"), "")
        if (q0.isEmpty()) return Intent(Kind.HELLO)
        // Tagalog open/on/off → English.
        val q = q0.replace(OPEN_TL, "open ").replace(ON_TL, "turn on ").replace(OFF_TL, "turn off ").replace(Regex("\\s+"), " ").trim()

        // ---- conversation
        if (Regex("^(?:that'?s all|that is all|goodbye|bye|good ?night|stop listening|never ?mind|nothing|salamat|thanks?|thank you|wala na|ok na)$").matches(q)) return Intent(Kind.BYE)
        if (Regex("^(?:hi|hello|hey|yo|you there|are you there|gilbert|good (?:morning|afternoon|evening))$").matches(q)) return Intent(Kind.HELLO)
        if (Regex("^(?:help|help me|what can you do|what do you do|commands|ano(?:ng)? (?:kaya|magagawa)(?: mo)?)$").matches(q)) return Intent(Kind.HELP)
        // ---- planner: reminders, follow-ups, "send this to tasks", "show it"
        if (Regex("^(?:(?:show|list|read|what are)(?: me)?(?: my)? reminders|(?:my )?reminders|mga paalala ko)$").matches(q)) return Intent(Kind.REMINDER_LIST)
        Regex("^(?:remind me|set (?:a )?reminder|reminder|paalalahanan mo ako|ipaalala mo|ipaalala)\\b(?: (?:to|na|that|about|ng|sa|for)\\b)?\\s*[:,-]?\\s*(.*)$").find(q)?.let { m ->
            val (w, what0) = parseWhen(m.groupValues[1])
            val what = what0.replace(Regex("^(?:to|na|that|about|ng|sa|for|i need to)\\s+"), "").replace(Regex("\\s+(?:to|at|on|na)$"), "").trim()
            return Intent(Kind.REMIND, what.ifEmpty { "your reminder" }, at = w)
        }
        if (Regex("^(?:(?:show|list|read|what are)(?: me)?(?: my)? reminders|(?:my )?reminders|mga paalala ko)$").matches(q)) return Intent(Kind.REMINDER_LIST)
        Regex("^(?:follow up(?: with| on)?|follow-up(?: with| on)?|add (?:to )?(?:the )?follow[- ]?ups?|to be followed|i need to follow up(?: with| on)?|i-?follow up(?: si| kay)?)\\s*[:,-]?\\s*(\\S.*)$").find(q)?.let { m ->
            val (w, what) = parseWhen(m.groupValues[1])
            return Intent(Kind.FOLLOW_ADD, what, at = w.takeIf { !it.isEmpty })
        }
        if (Regex("^(?:(?:show|list|what are)(?: me)?(?: my| the)? follow[- ]?ups?|(?:my )?follow[- ]?ups?|who do i need to follow up(?: with)?|what do i need to follow up(?: on)?|to be followed)$").matches(q)) return Intent(Kind.FOLLOW_LIST)
        if (Regex("^(?:send|add|save|put|move|make|turn)(?: (?:this|that|it))? (?:to|into|in|as)(?: (?:a|my|the))? (?:task|tasks|to-?do|to-?do list|task list)$|^(?:send|save|add) (?:this|that|it)$|^(?:i-?save|isave) (?:mo )?(?:ito|yan)(?: sa tasks?)?$").matches(q)) return Intent(Kind.SEND_TO_TASK)
        if (Regex("^(?:show (?:it|this|that|me)(?: on (?:my|the) screen)?|show it to me|put it on (?:my|the) screen|display it|ipakita mo|ipakita)$").matches(q)) return Intent(Kind.SHOW_IT)
        if (Regex("^(?:(?:show|open)(?: me)?(?: my| the)? )?(?:agenda|planner|plan for today|my day)$").matches(q)) return Intent(Kind.AGENDA)
        // ---- calendar
        Regex("^(?:add|put|schedule|create|book|set)(?: an?)?(?: (?:event|meeting|appointment))?(?: to| on| in)? (?:my |the )?calendar\\s*[:,-]?\\s*(.*)$|^(?:schedule|book) (?:an? )?(.+)$").find(q)?.let { m ->
            val body = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val (w, what) = parseWhen(body)
            if (what.isNotEmpty() || !w.isEmpty) return Intent(Kind.CALENDAR_ADD, what.replace(Regex("^(?:a |an |for |to )"), "").ifEmpty { "Event" }, at = w)
        }
        if (Regex("\\b(?:calendar|schedule|events?|iskedyul)\\b").containsMatchIn(q) && Regex("^(?:what|what's|whats|show|check|read|do i have|any|my|ano|anong)").containsMatchIn(q) ||
            Regex("^(?:do i have (?:anything|any plans|class|classes|meetings?)|what do i have|anything (?:today|tomorrow))").containsMatchIn(q)) {
            return Intent(Kind.CALENDAR, if (Regex("\\b(?:tomorrow|bukas)\\b").containsMatchIn(q)) "tomorrow" else if (Regex("\\bweek\\b").containsMatchIn(q)) "week" else "today")
        }
        // ---- email
        if (Regex("\\b(?:e-?mails?|gmail|inbox|mail)\\b").containsMatchIn(q) && Regex("^(?:check|read|any|do i have|show|what|what's|whats|new|summarize|i-?check)").containsMatchIn(q)) return Intent(Kind.EMAIL_CHECK)
        Regex("^(?:send (?:an )?e-?mail to|e-?mail|write (?:an )?e-?mail to|mag-?email kay) ([^,]+?)(?:\\s+(?:about|saying|that|re|regarding|tungkol sa)\\s+(.+))?$").find(q)?.let { m ->
            return Intent(Kind.EMAIL_SEND, m.groupValues[1].trim() + "\u0000" + m.groupValues[2].trim())
        }
        // ---- photos & files
        if (Regex("^(?:show|open|find|see|view|ipakita)(?: me)?(?: my| the| mga)? (?:recent |latest |last )?(?:photos?|pictures?|pics|images?|screenshots?|gallery|litrato)(?: ko)?$").matches(q)) return Intent(Kind.PHOTOS, if (q.contains("screenshot")) "screenshots" else "")
        if (Regex("^(?:show|open|find|see|list)(?: me)?(?: (?:my|the))? (?:recent |latest )?(?:downloads|documents|docs)$|^(?:show|open|find|see|list)(?: me)? my (?:recent |latest )?files$").matches(q)) return Intent(Kind.FILES)

        if (Regex("^(?:show|list|read)(?: me)?(?: my)? notes$").matches(q)) return Intent(Kind.NOTE_LIST)
        Regex("^(?:go to|show|open|switch to|pumunta sa)(?: the)? (mind|now|summary|chats?|names|notes|files|system|updates)(?: window| tab)?$").find(q)?.let {
            return Intent(Kind.WINDOW, it.groupValues[1].removeSuffix("s").let { w -> if (w == "chat") "chats" else if (w == "note") "notes" else if (w == "file") "files" else if (w == "name") "names" else if (w == "update") "mind" else w })
        }

        // ---- tasks & notes
        if (Regex("^(?:create|add|make|new|gawa)(?: me| ng)?(?: a| an)?(?: new)? (?:task|to-?do)$").matches(q)) return Intent(Kind.TASK_ADD, "")
        if (Regex("^(?:set|start|create|make)(?: me)?(?: a| an)? (?:timer|countdown)$|^timer$").matches(q)) return Intent(Kind.TIMER)
        Regex("^(?:add|new|create|make|dagdag|idagdag|lagay)(?: me)?(?: a| ng| ang)? (?:task|to[- ]?do|gawain)(?: list)?(?:\\s*(?:[:,-]|to|that|na)\\s*|\\s+)(\\S.*)$").find(q)?.let { m ->
            val orig = Regex("(?:task|to[- ]?do|gawain)(?: list)?\\s*(?:[:,-]|to|that|na)?\\s*", RegexOption.IGNORE_CASE).split(raw, 2).getOrNull(1)?.trim()
            return Intent(Kind.TASK_ADD, (orig?.takeIf { it.isNotEmpty() } ?: m.groupValues[1]).trim(' ', '.', ','))
        }
        if (Regex("^(?:(?:show|list|read|what are|what's|whats|tell me)(?: me)?(?: on)?(?: my| the)? (?:open )?(?:tasks|to-?dos?|to do list|to-do list)(?: list)?|(?:my )?tasks|ano(?:ng)? (?:tasks|gawain) ko|mga (?:tasks|gawain) ko)$").matches(q)) return Intent(Kind.TASK_LIST)
        Regex("^(?:complete|finish|finished|done(?: with)?|tick off|check off|mark|tapos(?: na)?|natapos)(?: (?:na|ko na|ang|yung))?(?: (?:task|to-?do|gawain))?(?: number| no\\.?)? #?(\\w+)(?: (?:as )?(?:done|complete|completed))?$").find(q)?.let { m ->
            word(m.groupValues[1])?.let { return Intent(Kind.TASK_DONE, num = it) }
        }
        Regex("^(?:task|to-?do)(?: number| no\\.?| #)? (\\w+) (?:is )?(?:done|complete|completed|finished|tapos na)$").find(q)?.let { m ->
            word(m.groupValues[1])?.let { return Intent(Kind.TASK_DONE, num = it) }
        }
        Regex("^(?:remember|save (?:a )?note|note|take (?:a )?note|make a note|paalala|tandaan|i-?note)(?: (?:that|na|mo))?\\s*[:,-]?\\s+(\\S.*)$").find(q)?.let { m ->
            if (!Regex("^(?:my |the )?notes?$").matches(m.groupValues[1])) {
                val orig = Regex("^.*?(?:remember|note|paalala|tandaan)(?: (?:that|na|mo))?\\s*[:,-]?\\s*", RegexOption.IGNORE_CASE).replaceFirst(raw, "").trim()
                return Intent(Kind.NOTE_ADD, orig.ifEmpty { m.groupValues[1] }.trim(' ', '.'))
            }
        }
        if (Regex("^(?:(?:show|list|read|what are)(?: me)?(?: my| the)? notes|(?:my )?notes|mga notes ko)$").matches(q)) return Intent(Kind.NOTE_LIST)

        // ---- phone functions
        if (Regex("\\b(?:flashlight|torch|flash)\\b").containsMatchIn(q) && !q.startsWith("open ")) {
            val off = Regex("\\b(?:off|turn off|disable|stop|patay)\\b").containsMatchIn(q)
            return Intent(Kind.FLASHLIGHT, on = !off)
        }
        if (Regex("\\b(?:volume|lakasan|hinaan|louder|quieter|softer|mute|unmute)\\b").containsMatchIn(q) && !q.contains("notification")) {
            val dir = when {
                Regex("\\bunmute\\b").containsMatchIn(q) -> "unmute"
                Regex("\\bmute\\b|\\bsilent\\b").containsMatchIn(q) -> "mute"
                Regex("\\b(?:max|maximum|full|highest)\\b").containsMatchIn(q) -> "max"
                Regex("\\b(?:up|louder|increase|raise|higher|lakasan|lakas)\\b").containsMatchIn(q) -> "up"
                else -> "down"
            }
            return Intent(Kind.VOLUME, dir)
        }
        if (Regex("\\b(?:brightness|liwanag|brighter|dimmer)\\b").containsMatchIn(q)) {
            val n = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull()
            val pct = n ?: when {
                Regex("\\b(?:max|full|brighter|up|increase|liwanagan)\\b").containsMatchIn(q) -> 100
                Regex("\\b(?:low|min|dimmer|down|decrease|dim)\\b").containsMatchIn(q) -> 15
                else -> 50
            }
            return Intent(Kind.BRIGHTNESS, num = pct.coerceIn(1, 100))
        }
        Regex("^(play|pause|resume|stop the music|stop music|next|skip|previous|prev|go back|last)(?: (?:the |this |that )?(?:song|music|track|tugtog|kanta))?$").find(q)?.let { m ->
            val k = when (m.groupValues[1]) { "play", "resume" -> "play"; "pause", "stop the music", "stop music" -> "pause"; "next", "skip" -> "next"; else -> "previous" }
            return Intent(Kind.MEDIA, k)
        }
        if (Regex("^(?:next|skip)(?: (?:the )?(?:song|track))?|(?:play )?(?:the )?next (?:song|track)$").matches(q)) return Intent(Kind.MEDIA, "next")
        if (Regex("\\b(?:do not disturb|don't disturb|dnd|focus mode|silent mode)\\b").containsMatchIn(q)) {
            return Intent(Kind.DND, on = !Regex("\\b(?:off|disable|stop|end|turn off|patay)\\b").containsMatchIn(q))
        }
        if (Regex("^(?:lock|i-?lock)(?: (?:the|my|ang))?(?: (?:phone|screen|cellphone|cp))?$").matches(q)) return Intent(Kind.LOCK)
        Regex("(?:alarm|wake me(?: up)?|gisingin mo ako)(?: (?:for|at|ng|sa))? (\\d{1,2})(?:[:. ](\\d{2}))? ?(a\\.?m\\.?|p\\.?m\\.?|am|pm|ng umaga|ng gabi|ng hapon)?").find(q)?.let { m ->
            var hr = m.groupValues[1].toInt(); val mn = m.groupValues[2].toIntOrNull() ?: 0
            val ap = m.groupValues[3].replace(".", "")
            if ((ap == "pm" || ap.contains("gabi") || ap.contains("hapon")) && hr < 12) hr += 12
            if (ap == "am" && hr == 12) hr = 0
            if (hr in 0..23 && mn in 0..59) return Intent(Kind.ALARM, num = hr, num2 = mn)
        }
        Regex("(?:timer|countdown)(?: (?:for|of|ng))? (\\w+) ?(seconds?|secs?|minutes?|mins?|hours?|hrs?|segundo|minuto|oras)").find(q)?.let { m ->
            val n = word(m.groupValues[1]) ?: return@let
            val u = m.groupValues[2]
            val secs = when { u.startsWith("h") || u == "oras" -> n * 3600; u.startsWith("m") -> n * 60; else -> n }
            if (secs in 1..86_400) return Intent(Kind.TIMER, u, num = secs)
        }
        Regex("^(\\w+) (seconds?|minutes?|mins?|hours?) timer$").find(q)?.let { m ->
            val n = word(m.groupValues[1]) ?: return@let
            val u = m.groupValues[2]
            return Intent(Kind.TIMER, u, num = if (u.startsWith("h")) n * 3600 else if (u.startsWith("m")) n * 60 else n)
        }
        Regex("^(?:call|phone|dial|ring|tawagan)(?: mo)?(?: nga)?(?: (?:si|kay|ang|sa))? (.+)$").find(q)?.let { return Intent(Kind.CALL, it.groupValues[1].trim()) }
        Regex("^(?:search|google|look up|search for|hanapin|i-?search|search the web for)(?: (?:for|ang|yung))? (.+)$").find(q)?.let { return Intent(Kind.SEARCH, it.groupValues[1].trim()) }

        // ---- settings pages
        if (Regex("^(?:open )?(?:the )?(?:launcher|your|gilbert'?s?|home ?screen) settings$|^(?:open )?settings$").matches(q)) return Intent(Kind.LAUNCHER_SETTINGS)
        val pages = listOf("wi-fi" to "wi-?fi|internet|wireless", "bluetooth" to "bluetooth", "battery" to "battery|baterya",
            "display" to "display|screen settings", "sound" to "sound|ringtone|tunog", "storage" to "storage",
            "location" to "location|gps", "data" to "mobile data|data|hotspot")
        if (q.startsWith("open ") || q.endsWith("settings")) for ((name, re) in pages)
            if (Regex("^(?:open )?(?:the |my )?(?:$re)(?: settings| page)?$").matches(q)) return Intent(Kind.SETTINGS_PAGE, name)

        // ---- launcher settings
        Regex("^(?:set|change|make|put|adjust)? ?(?:the |my )?(.+?) (?:to|into|=|at|sa) (\\d{1,4}) ?%?$").find(q)?.let { return Intent(Kind.SET_NUMBER, it.groupValues[1].trim(), num = it.groupValues[2].toInt()) }
        Regex("^(?:make|set|change) (?:the |my )?(.+? style) ([a-z]+)$").find(q)?.let {
            return Intent(Kind.SET_STYLE, it.groupValues[1].trim() + "\u0000" + it.groupValues[2])
        }
        val SETTING = Regex("\\b(?:style|transition|effect|speed|animation|face|title|call me|theme|look|mode|size|columns?|rows?|scale|opacity|corners?|labels?|dock|island|library|notification|player|talk|voice|swipe)\\b")
        if (!Regex("^(?:go|come|went|listen|talk|send|reply|move|switch back|is|are|was|will|draft|write|what|how|why|who|when) ").containsMatchIn(q) &&
            (Regex("^(?:set|change|switch|make|use) ").containsMatchIn(q) || SETTING.containsMatchIn(q.substringBefore(" to ")))) Regex("^(?:set|change|make|switch|put|use)? ?(?:the |my )?(.+?) (?:to|into|=|as) ([a-z][a-z -]*)$").find(q)?.let {
            val target = it.groupValues[1].trim()
            if (!target.startsWith("turn") && target.isNotEmpty()) return Intent(Kind.SET_STYLE, target, num = null).copy(text = target + "\u0000" + it.groupValues[2].trim())
        }
        val onRe = Regex("^(?:turn on|switch on|enable|activate|show|bring back|unhide)\\b\\s*(?:the |my )?")
        val offRe = Regex("^(?:turn off|switch off|disable|deactivate|hide|stop|remove|kill|get rid of)\\b\\s*(?:the |my )?")
        Regex("^(?:turn|switch) (?:the |my )?(.+?) (on|off)$").find(q)?.let { return Intent(Kind.TOGGLE, it.groupValues[1].trim(), on = it.groupValues[2] == "on") }
        when {
            onRe.containsMatchIn(q) -> return Intent(Kind.TOGGLE, q.replace(onRe, "").trim(), on = true)
            offRe.containsMatchIn(q) -> return Intent(Kind.TOGGLE, q.replace(offRe, "").trim(), on = false)
            q.startsWith("toggle ") -> return Intent(Kind.TOGGLE, q.removePrefix("toggle ").removePrefix("the ").trim(), on = null)
            Regex(" (on|off)$").containsMatchIn(q) && !q.startsWith("open ") -> return Intent(Kind.TOGGLE, q.substringBeforeLast(" ").trim(), on = q.endsWith(" on"))
        }

        // ---- apps
        Regex("^(?:open|launch|start|run|go to|load|bring up|pull up|show me)(?: up)? (?:the |my )?(.+?)(?: app| application)?$").find(q)?.let {
            return Intent(Kind.OPEN_APP, it.groupValues[1].trim())
        }
        return null
    }
}
