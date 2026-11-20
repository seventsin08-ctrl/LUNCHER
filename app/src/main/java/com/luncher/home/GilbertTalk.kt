package com.luncher.home

import android.content.Context
import java.util.Calendar

/**
 * Things Gilbert says: check-ins ("Have you eaten?"), care reminders, little jokes, comments on
 * what you're doing, and real updates (held notifications, battery, heat, steps, weather).
 * Hundreds of combinations so he rarely repeats himself.
 */
object GilbertTalk {
    private val recent = ArrayDeque<String>()

    private val morning = listOf(
        "Good morning! ☀️ Did you sleep well?", "Morning! Have you had breakfast yet? 🍳", "Rise and shine! What's the plan today?",
        "Good morning~ drink some water first 💧", "New day, new chances ✨ You got this!", "Morning! Don't skip breakfast, okay? 🥐",
        "Hey sleepyhead 😴 coffee or tea today?", "Good morning! I kept your notifications tidy while you slept 🌙")
    private val noon = listOf(
        "Have you eaten lunch yet? 🍜", "Lunch time! Don't forget to eat 🍱", "Hey… did you eat? Real food, not just snacks 👀",
        "Midday check-in: how's your day going?", "Take a little break and eat something yummy 🍙", "It's noon! Stretch a bit 🙆")
    private val afternoon = listOf(
        "Afternoon slump? A short walk helps 🚶", "Hey! Drink some water 💧", "How are you feeling this afternoon?",
        "Snack time? 🍪 I won't tell anyone", "You've been doing great today 💪", "Rest your eyes for 20 seconds 👀")
    private val evening = listOf(
        "Good evening 🌆 Have you had dinner?", "Did you eat dinner yet? 🍲", "Evening! How was your day?",
        "Time to slow down a little 🌙", "Proud of you for today ✨", "Remember to charge me… I mean the phone 🔋")
    private val night = listOf(
        "It's getting late… maybe rest your eyes 🌙", "Still up? Don't forget to sleep 😴", "Night owl mode 🦉 Just one more video?",
        "Your eyes need rest too 💤", "Sweet dreams soon, okay? 🌌", "Late night scroll? I'll keep watch 🫡")
    private val anytime = listOf(
        "Hi! Just checking on you 👋", "Drink some water 💧", "Have you eaten? 🍙", "How are you feeling right now?",
        "Remember to blink 😌", "Sit up straight! 🪑", "You're doing amazing ✨", "Take a deep breath… in… out… 🌬️",
        "I'm here if you need me 🤍", "Psst… your posture 👀", "Did you smile today? 🙂", "Small break? You deserve it ☕",
        "Stretch your fingers 🖐️ they work hard", "I like hanging out with you 😊", "Text someone you miss today 💌",
        "Don't forget to move a bit 🚶", "Fun fact: I'm made of 240 tiny lights ✨", "Bored? Let's tidy your Home Screen 😄",
        "You've got this, bestie 💪", "Need anything? Tap me!", "Hydration check! 💧", "Water you doing? Drink water 😆",
        "Remember: progress, not perfection 🌱", "Your phone and I are vibing today 🎶", "Eat a fruit today 🍎")
    private val watching = listOf("Good video? 🍿", "Don't forget to blink 👀", "Ooh what are we watching? 🍿",
        "One more episode? 😏", "Snacks for the show? 🍿", "Watching with you~ 📺")
    private val listening = listOf("Nice song 🎶", "This one's a bop 💃", "Volume up! 🔊", "I'm vibing too 🎧", "Good taste in music 🎵")
    private val chatting = listOf("Say hi from me 👋", "Chatting with friends? 😊", "Don't leave them on read 😆", "Social butterfly 🦋")
    private val searching = listOf("What are we looking for? 🔍", "I'll help you find it 👀", "Searching~ 🔎")

    fun line(ctx: Context, s: Settings, mood: String): String {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val health = runCatching { Gilbert.health(ctx) }.getOrNull()
        val held = Gilbert.heldCount()
        val steps = InfoData.stepsToday
        val w = InfoData.weather
        val name = ownerName(ctx)
        val pool = ArrayList<String>()
        // Real updates first.
        if (held > 0) pool += listOf("I'm holding $held notification${if (held > 1) "s" else ""} for you — tap me 📬",
            "$held quiet notification${if (held > 1) "s" else ""} waiting. No rush 😊")
        health?.let {
            if (it.battery in 0..15 && !it.charging) pool += listOf("Battery's at ${it.battery}% 🪫 plug me in?", "Low battery! Find a charger 🔌")
            if ((it.tempC ?: 0f) >= 41f) pool += listOf("Phone's getting warm 🥵 take a break?", "It's ${it.tempC!!.toInt()}° in here… let it cool down 🧊")
            if (it.storageTotalGb > 0 && it.storageFreeGb / it.storageTotalGb < 0.1f) pool += "Storage is almost full 📦 maybe clean up some files?"
        }
        if (steps in 1..1499 && h >= 15) pool += "Only $steps steps today… short walk? 🚶"
        if (steps >= 6000) pool += listOf("$steps steps today! 👟 Amazing!", "Look at you go! $steps steps 🏃")
        w?.let { pool += listOf("It's ${it.temp}° outside ${InfoData.condition(it.code).first}", if (it.temp >= 32) "It's hot today (${it.temp}°) — stay hydrated 💧" else "Weather check: ${it.temp}°, ${InfoData.condition(it.code).second.lowercase()}") }
        // What you're doing.
        when (mood) {
            "watching" -> pool += watching
            "listening" -> pool += listening
            "happy" -> pool += chatting
            "curious" -> pool += searching
        }
        // Time of day + anytime care.
        pool += when (h) { in 5..10 -> morning; in 11..13 -> noon; in 14..17 -> afternoon; in 18..21 -> evening; else -> night }
        pool += anytime
        var pick = pool.filter { it !in recent }.randomOrNull() ?: pool.random()
        if (name != null && Math.random() < 0.35) pick = "$name! $pick"
        recent.addLast(pick); while (recent.size > 12) recent.removeFirst()
        return pick
    }

    private var cachedName: String? = null
    private var nameLooked = false
    /** Your first name from your own contact card (if Contacts is allowed). */
    fun ownerName(ctx: Context): String? {
        if (nameLooked) return cachedName
        nameLooked = true
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        cachedName = runCatching {
            ctx.contentResolver.query(android.provider.ContactsContract.Profile.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Profile.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim()?.split(" ")?.firstOrNull() else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return cachedName
    }
}
