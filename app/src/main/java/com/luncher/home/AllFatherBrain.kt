package com.luncher.home

/**
 * All Father's reasoning (no Android, so it can be simulated and tested): what kind of
 * information this is, how important it is to John, and what he should do about it.
 * He never talks — [AllFather] runs the actions and shows them in the Data Center.
 */
object AllFatherBrain {
    enum class Cat(val label: String) {
        ANNOUNCEMENT("Announcements"), CLIENT("Clients"), SCHOOL("School"), MESSAGE("Messages"), EMAIL("Gmail"),
        MONEY("Money"), CALENDAR("Calendar"), SOCIAL("Social"), PROMO("Promos"), SYSTEM("System"), SECURITY("Security"), OTHER("Other")
    }
    data class Verdict(val cat: Cat, val important: Boolean, val score: Int, val why: String)
    data class Act(val kind: String, val text: String, val due: GilbertIntent.When? = null, val amount: Double? = null, val dueText: String = "")

    private fun has(t: String, re: String) = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(t)

    private val CHAT = setOf("messenger", "messages", "whatsapp", "telegram", "viber", "signal", "discord", "line", "sms", "messaging", "teams", "slack", "google chat", "instagram direct")
    private val MAIL = setOf("gmail", "email", "outlook", "yahoo mail", "proton mail", "spark")
    private val BANK = Regex("gcash|maya|paymaya|bpi|bdo|metrobank|unionbank|landbank|security bank|rcbc|gotyme|seabank|paypal|wise|coins\\.ph|bank", RegexOption.IGNORE_CASE)
    private val SOCIAL_APPS = Regex("^(instagram|tiktok|facebook|youtube|x|twitter|threads|pinterest|snapchat|reddit)$", RegexOption.IGNORE_CASE)
    private val SHOP_APPS = Regex("shopee|lazada|temu|shein|grab|foodpanda|zalora|amazon", RegexOption.IGNORE_CASE)

    private const val PROMO = "\\b(sale|% ?off|promo|voucher|discount|deals?|flash sale|free shipping|limited time|only today|today only|shop now|subscribe|newsletter|digest|recommended for you|you might like|top picks|cashback|coupon|exclusive offer|don't miss|last chance|unsubscribe|giveaway|win a|claim your (?:reward|prize|voucher))\\b"
    private const val ANNOUNCE = "(announcement|anunsyo|@everyone|@all\\b|attention(?: all)?|to all students|reminder to all|no classes|walang pasok|class(?:es)? (?:are )?suspended|suspension of classes|important update|memo\\b|advisory|official notice)"
    private const val DEADLINE = "\\b(due|deadline|submit|submission|ipasa|pasahan|pakipasa|pasa|bago (?:mag )?(?:mon|tue|wed|thu|fri|sat|sun|bukas)\\w*|before|until|by (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|tomorrow|tonight|today|\\d)|on or before|exam|quiz|long test|recitation|defense|presentation|report|requirements?|enroll(?:ment)?|claim)\\b"
    private const val CLIENT_HINT = "\\b(how much|magkano|quote|quotation|website|web ?site|landing page|app for|logo|menu|order|invoice|payment|down ?payment|balance|project|revision|revised|deliver|deadline|client|interested|inquir(?:y|e)|pricing|rate|commission|contract)\\b"
    private const val SCHOOL_HINT = "\\b(bsit|professor|prof\\b|ma'?am|sir\\b|class|subject|nstp|lts|research|thesis|module|activity|assignment|quiz|exam|grades?|registrar|enrollment|section|school|kolehiyo|college|university|teacher|lecture|lab|defense|recitation|presentation|reporting|groupmates?)\\b"
    private const val QUESTION = "(\\?\\s*$|\\?\\s|\\b(can you|could you|pwede (?:mo|ba)|paki|please|kindly|need you|reply|call me|tawagan mo|asan ka|where are you|are you|r u|nasaan)\\b|\\bpaki\\w+)"
    private const val URGENT = "\\b(urgent|asap|emergency|important|now na|ngayon na|agad|immediately|right away|help)\\b"
    private const val CHATTER = "^(?:\\W*(?:haha+|hehe+|lol|lmao|ok(?:ay)?|k|sige|noted|thanks?|ty|salamat|nice|wow|yes|yep|no|nope|g|gg|ayos|oo|hmm+|😂|🤣|❤️|👍|🙏|😭)\\W*)+$"

    fun classify(app: String, who: String, text: String): Verdict {
        val a = app.lowercase().trim(); val w = who.trim(); val t = text.trim()
        val all = "$w $t"
        val isChat = a in CHAT || a.contains("messenger") || a.contains("message")
        val isMail = a in MAIL || a.contains("mail")
        // ---- security first (codes, logins) — never stored in plain text by the store
        if (has(all, "\\b(otp|one[- ]time (?:pin|password|code)|verification code|security code|2fa|login attempt|new sign-?in|suspicious)\\b") || has(t, "\\b\\d{6}\\b.*\\b(code|otp)\\b|\\b(code|otp)\\b.*\\b\\d{4,8}\\b"))
            return Verdict(Cat.SECURITY, true, 85, "security / login code")
        // ---- money
        if (BANK.containsMatchIn(a) || BANK.containsMatchIn(w) && has(t, "₱|php|received|sent|paid|payment|transfer|balance")) {
            val imp = has(t, "received|you got|credited|deposit|payment (?:due|reminder)|due|overdue|declined|failed|low balance|bill")
            return Verdict(Cat.MONEY, imp, if (imp) 80 else 25, if (imp) "money in / bill" else "money out / note")
        }
        // ---- promos (shops, newsletters, digests)
        if (has(all, PROMO) || SHOP_APPS.containsMatchIn(a) && !has(t, "(out for delivery|delivered|arriving|order (?:#|no|number)|your order|parcel|rider)"))
            return Verdict(Cat.PROMO, false, 5, "promotion")
        if (SHOP_APPS.containsMatchIn(a)) return Verdict(Cat.OTHER, true, 55, "delivery update")
        // ---- calendar & clock
        if (a.contains("calendar") || a == "clock" || has(a, "alarm")) return Verdict(Cat.CALENDAR, true, 75, "scheduled")
        // ---- system
        if (a == "android system" || a == "system" || a == "settings" || a.contains("system ui") || a == "battery" || a == "i manager" || a == "phone manager") {
            val m = Regex("(\\d{1,3}) ?%").find(t)?.groupValues?.get(1)?.toIntOrNull()
            val imp = (m != null && m <= 20) || has(t, "storage (?:is )?(?:almost )?full|low storage|overheat|security update|not charging|virus|malware")
            return Verdict(Cat.SYSTEM, imp, if (imp) 70 else 10, if (imp) "phone needs attention" else "system note")
        }
        // ---- announcements (any app)
        if (has(all, ANNOUNCE) || isChat && has(w, "\\b(updates|announcements?|official|admin)\\b"))
            return Verdict(Cat.ANNOUNCEMENT, true, if (has(t, DEADLINE)) 90 else 75, "announcement")
        // ---- email
        if (isMail) {
            if (SHOP_APPS.containsMatchIn(w)) return Verdict(Cat.EMAIL, false, 15, "shop update")
            val imp = has(all, SCHOOL_HINT) || has(all, CLIENT_HINT) || has(all, DEADLINE) ||
                has(all, "\\b(interview|offer|application|invoice|receipt|account|password|security|confirm|verify|job|gig|freelance|hiring|opportunity|budget|proposal)\\b")
            return Verdict(Cat.EMAIL, imp, if (imp) 70 else 15, if (imp) "email that needs you" else "low-value email")
        }
        // ---- social
        if (SOCIAL_APPS.containsMatchIn(a) && !isChat) {
            val dm = has(t, "\\b(sent you a message|messaged you|replied to your story|mentioned you|tagged you)\\b") && !has(t, "reel|post|video")
            return Verdict(Cat.SOCIAL, dm, if (dm) 55 else 10, if (dm) "someone reached out" else "social activity")
        }
        // ---- chats: client > school > personal
        if (isChat || a.isEmpty()) {
            if (has(t, CHATTER) || has(t, "^\\s*(sent (?:a|an) (?:sticker|gif|reel|photo|video|voice message|attachment)|reacted|liked)")) {
                val client0 = has(w, "client|caf[eé]|shop|store|business|lead")
                return Verdict(if (client0) Cat.CLIENT else Cat.MESSAGE, false, 15, "small talk")
            }
            if (has(w, "^(globe|smart|tnt|tm|dito|sun|gomo|globe at home|pldt)$")) return Verdict(Cat.OTHER, has(t, "expir|disconnect|due"), 20, "carrier notice")
            val clientName = has(w, "\\b(client|caf[eé]|coffee|shop|store|resto|restaurant|business|lead|company|inc|corp|enterprise|co\\.)\\b")
            val clientWork = has(t, CLIENT_HINT) && !has(w, "bsit|class|section")
            val client = clientName || clientWork
            val school = has(t, SCHOOL_HINT) || has(w, SCHOOL_HINT) && (has(t, DEADLINE) || has(t, QUESTION) || has(t, URGENT))
            var score = 30
            if (has(t, QUESTION)) score += 25
            if (has(t, DEADLINE)) score += 25
            if (has(t, URGENT)) score += 30
            if (has(t, "\\b(john|@john)\\b")) score += 15
            if (clientWork) score += 25 else if (clientName) score += 10
            if (has(t, "₱|php ?\\d")) score += 10
            val cat = if (client) Cat.CLIENT else if (school) Cat.SCHOOL else Cat.MESSAGE
            return Verdict(cat, score >= 50, score.coerceAtMost(100), when { client -> "client"; has(t, URGENT) -> "urgent"; has(t, DEADLINE) -> "deadline"; has(t, QUESTION) -> "asks you"; else -> "chat" })
        }
        val imp = has(all, DEADLINE) || has(all, URGENT)
        return Verdict(Cat.OTHER, imp, if (imp) 60 else 20, "other")
    }

    // ------------------------------------------------------------------ actions
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "due Friday 5 PM" / "by Friday" / "before Nov 28" / "bukas" → (when, words). */
    fun dueIn(text: String): Pair<GilbertIntent.When?, String>? {
        val t = text.lowercase()
        val m = Regex("\\b(?:due(?: on| by| at)?|deadline(?: is| on)?|by|before|until|on or before|bago(?: mag)?|ipasa(?: na)?(?: sa)?|pasahan(?: ay| sa)?|moved to|at)\\s+((?:(?:this|next|on)\\s+)?(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*(?:\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))?|tomorrow(?:\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))?|tonight|today(?:\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))?|bukas|mamaya|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)(?:\\s+tomorrow|\\s+today)?|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}|\\d{1,2}/\\d{1,2})").find(t)
            ?: Regex("\\b(tomorrow|bukas|tonight|mamayang gabi)\\b").find(t)?.let { return GilbertIntent.parseWhen(it.value).first to it.value }
            ?: return null
        val phrase = m.groupValues[1].trim()
        Regex("^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})$").find(phrase)?.let { d ->
            return GilbertIntent.When(hour = 9, month = MONTHS.indexOf(d.groupValues[1]) + 1, date = d.groupValues[2].toInt()) to phrase
        }
        Regex("^(\\d{1,2})/(\\d{1,2})$").find(phrase)?.let { d ->
            val mo = d.groupValues[1].toInt(); val da = d.groupValues[2].toInt()
            if (mo in 1..12 && da in 1..31) return GilbertIntent.When(hour = 9, month = mo, date = da) to phrase
        }
        val w = GilbertIntent.parseWhen(phrase.replace(Regex("^(this|next|on) "), "")).first
        return (if (w.isEmpty) null else w) to phrase
    }

    private fun amount(t: String): Double? = Regex("(?:₱|php|p)\\s?([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    /** The short "what to do" from a message: "can you send the revised menu by Friday" → "Send the revised menu". */
    fun taskTitle(who: String, text: String): String {
        var t = text.replace(Regex("^(?:announcement|anunsyo|reminder|@everyone|@all|attention)\\s*[:!-]*\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:hi|hello|good (?:morning|afternoon|evening))[^,.!]*[,.!]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:hi|hello)\\s+\\w+[,!.]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:can|could|would) you(?: please)?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:please|pls|kindly|paki-?)\\s*", RegexOption.IGNORE_CASE), "")
        // Keep the sentence that has the deadline in it.
        t.split(Regex("(?<=[.!?])\\s+")).firstOrNull { has(it, DEADLINE) }?.let { t = it }
        t = t.replace(Regex("\\s*\\b(?:due|by|before|until|on or before|deadline)\\b.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:tomorrow|today|tonight|bukas|mamaya|later|now)\\b.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[?!.]+$"), "").replace(Regex("\\s+(?:thanks|thank you|ty|po)\\b.*$", RegexOption.IGNORE_CASE), "").trim()
        if (t.length < 3) t = "Reply to ${who.substringBefore(" (")}"
        return t.replaceFirstChar { it.uppercase() }.take(80)
    }

    fun actions(app: String, who: String, text: String, v: Verdict): List<Act> {
        val out = ArrayList<Act>()
        val name = who.substringBefore(" (").trim()
        when (v.cat) {
            Cat.SECURITY -> out += Act("FLAG", "Login / code from ${app.ifBlank { name }} — don't share it")
            Cat.PROMO -> out += Act("MUTE", "$name — promotion filed away")
            Cat.MONEY -> {
                val amt = amount(text)
                if (has(text, "received|you got|credited|deposit")) out += Act("MONEY_IN", "${amt?.let { "₱" + "%,.2f".format(it) } ?: "Money"} received${Regex("from ([A-Z][\\w.]*(?: [A-Z][\\w.]*)*)").find(text)?.let { " from " + it.groupValues[1] } ?: ""}", amount = amt)
                else if (has(text, "sent|paid|payment of|debited|purchase")) out += Act("MONEY_OUT", "${amt?.let { "₱" + "%,.2f".format(it) } ?: "Money"} out", amount = amt)
                if (has(text, "due|overdue|bill|reminder")) dueIn(text).let { d -> out += Act("TASK", "Pay $name bill", d?.first, dueText = d?.second ?: "") }
            }
            Cat.SYSTEM -> if (v.important) out += Act("FLAG", text.take(60))
            Cat.CALENDAR -> out += Act("SCHEDULE", text.take(70))
            Cat.ANNOUNCEMENT -> {
                out += Act("PIN", "$name: ${text.replace(Regex("^(?:announcement|anunsyo)\\s*[:!-]*\\s*", RegexOption.IGNORE_CASE), "").take(70)}")
                if (has(text, DEADLINE) && !has(text, "no classes|walang pasok|suspended")) dueIn(text)?.let { d -> out += Act("TASK", taskTitle(who, text), d.first, dueText = d.second) }
                else if (has(text, DEADLINE)) Regex("([A-Z][\\w ]{2,40}?) (?:is )?due ([^.]+)", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                    val d = dueIn("due " + m.groupValues[2]); out += Act("TASK", m.groupValues[1].trim().replaceFirstChar { it.uppercase() }, d?.first, dueText = d?.second ?: m.groupValues[2])
                }
                if (has(text, "moved to|resched|changed to|now at")) dueIn(text)?.let { d -> out += Act("REMINDER", "$name — ${text.take(50)}", d.first, dueText = d.second) }
            }
            Cat.CLIENT -> if (v.important) {
                if (has(text, "how much|magkano|quote|quotation|pricing|rate|interested|inquir")) {
                    out += Act("LEAD", "$name wants ${Regex("\\b(website|web ?site|landing page|app|logo|menu|system)\\b", RegexOption.IGNORE_CASE).find(text)?.value?.lowercase() ?: "a quote"}")
                    out += Act("TASK", "Send $name a quote")
                } else dueIn(text).let { d -> out += Act("TASK", taskTitle(who, text), d?.first, dueText = d?.second ?: "") }
                amount(text)?.let { if (has(text, "paid|sent|down ?payment|transferred")) out += Act("MONEY_IN", "₱" + "%,.2f".format(it) + " from $name (check it arrived)", amount = it) }
            }
            Cat.SCHOOL, Cat.MESSAGE, Cat.EMAIL, Cat.OTHER -> if (v.important) {
                val d = dueIn(text)
                if (has(text, "moved to|resched|changed to|now at|postponed to") && d != null) out += Act("REMINDER", "$name: ${text.take(50)}", d.first, dueText = d.second)
                else if (has(text, DEADLINE) && d != null) out += Act("TASK", taskTitle(who, text), d.first, dueText = d.second)
                else if (has(text, QUESTION) || has(text, URGENT)) out += Act("REPLY", "Reply to $name")
            }
            Cat.SOCIAL -> if (v.important) out += Act("REPLY", "Reply to $name")
        }
        if (out.isEmpty()) out += Act("FILE", "${v.cat.label} · ${if (v.important) "important" else "unimportant"}")
        return out.distinctBy { it.kind + it.text }
    }
}
