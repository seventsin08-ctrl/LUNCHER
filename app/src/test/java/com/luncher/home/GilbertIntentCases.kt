package com.luncher.home

import com.luncher.home.GilbertIntent.Kind.*

/**
 * Simulated things people say to Gilbert (typed, spoken, Taglish, with speech-recognition slips)
 * and what he must understand. Expected: kind, plus text / on / num when they matter.
 */
object GilbertIntentCases {
    class Case(val say: String, val kind: GilbertIntent.Kind?, val text: String? = null, val on: Boolean? = null, val num: Int? = null, val num2: Int? = null)
    private fun c(say: String, kind: GilbertIntent.Kind?, text: String? = null, on: Boolean? = null, num: Int? = null, num2: Int? = null) = Case(say, kind, text, on, num, num2)

    val ALL = listOf(
        // --- wake word + hello / bye
        c("System online", HELLO), c("Gilbert", HELLO), c("hey gilbert", HELLO), c("Gilbert, are you there?", HELLO),
        c("good morning", HELLO), c("that's all", BYE), c("Thanks Gilbert", BYE), c("salamat", BYE), c("never mind", BYE),
        c("ok na", BYE), c("Goodbye.", BYE), c("help", HELP), c("what can you do?", HELP), c("anong kaya mo", HELP),
        // --- windows
        c("go to chats", WINDOW, "chats"), c("show files", WINDOW, "files"), c("open the system window", WINDOW, "system"),
        c("show notes", NOTE_LIST), c("go to summary", WINDOW, "summary"), c("open mind", WINDOW, "mind"),
        // --- tasks
        c("add task: Finish my C++ activity", TASK_ADD, "Finish my C++ activity"),
        c("Gilbert, add task: Finish my C++ activity", TASK_ADD, "Finish my C++ activity"),
        c("add a task to buy bond paper", TASK_ADD, "buy bond paper"), c("new task review for the NSTP quiz", TASK_ADD, "review for the NSTP quiz"),
        c("create task: Send DevMart proposal", TASK_ADD, "Send DevMart proposal"), c("dagdag task: magreview sa math", TASK_ADD, "magreview sa math"),
        c("System online, add task call the client", TASK_ADD, "call the client"),
        c("show tasks", TASK_LIST), c("list tasks", TASK_LIST), c("what are my tasks?", TASK_LIST), c("my tasks", TASK_LIST),
        c("show me my tasks please", TASK_LIST), c("anong tasks ko", TASK_LIST), c("tasks", TASK_LIST), c("read my to-do list", TASK_LIST),
        c("complete task 1", TASK_DONE, num = 1), c("finish task 2.", TASK_DONE, num = 2), c("done with task 3", TASK_DONE, num = 3),
        c("mark task 4 as done", TASK_DONE, num = 4), c("complete task two", TASK_DONE, num = 2), c("tapos na task 5", TASK_DONE, num = 5),
        c("task number 6 done", TASK_DONE, num = 6),
        // --- notes
        c("remember: Check the DevMart mobile layout", NOTE_ADD, "Check the DevMart mobile layout"),
        c("remember that my exam is Friday", NOTE_ADD, "my exam is Friday"), c("take a note grocery client wants 5000 photos", NOTE_ADD, "grocery client wants 5000 photos"),
        c("paalala: magbayad ng tuition", NOTE_ADD, "magbayad ng tuition"), c("note: buy ink", NOTE_ADD, "buy ink"),
        c("list notes", NOTE_LIST), c("show my notes", NOTE_LIST), c("my notes", NOTE_LIST), c("read my notes", NOTE_LIST),
        // --- flashlight
        c("flashlight on", FLASHLIGHT, on = true), c("turn on the flashlight", FLASHLIGHT, on = true), c("flash light off", FLASHLIGHT, on = false),
        c("turn off flashlight please", FLASHLIGHT, on = false), c("torch on", FLASHLIGHT, on = true), c("i-on ang flashlight", FLASHLIGHT, on = true),
        c("patayin ang flashlight", FLASHLIGHT, on = false), c("can you turn the flashlight on", FLASHLIGHT, on = true),
        // --- volume / brightness
        c("volume up", VOLUME, "up"), c("turn the volume down", VOLUME, "down"), c("mute", VOLUME, "mute"), c("unmute", VOLUME, "unmute"),
        c("volume max", VOLUME, "max"), c("louder", VOLUME, "up"), c("lakasan ang volume", VOLUME, "up"), c("hinaan mo yung volume", VOLUME, "down"),
        c("brightness 40%", BRIGHTNESS, num = 40), c("set brightness to 80", BRIGHTNESS, num = 80), c("brightness up", BRIGHTNESS, num = 100),
        c("make it dimmer", BRIGHTNESS, num = 15), c("brightness low", BRIGHTNESS, num = 15),
        // --- media
        c("play", MEDIA, "play"), c("pause", MEDIA, "pause"), c("next", MEDIA, "next"), c("next song", MEDIA, "next"),
        c("skip this song", MEDIA, "next"), c("previous song", MEDIA, "previous"), c("play the next song", MEDIA, "next"), c("resume music", MEDIA, "play"),
        c("stop the music", MEDIA, "pause"), c("skip", MEDIA, "next"),
        // --- DND / lock
        c("do not disturb on", DND, on = true), c("turn on do not disturb", DND, on = true), c("turn off do not disturb", DND, on = false),
        c("dnd off", DND, on = false), c("lock the phone", LOCK), c("lock", LOCK), c("lock screen", LOCK), c("i-lock ang phone", LOCK),
        // --- alarm / timer
        c("set alarm 6:30 am", ALARM, num = 6, num2 = 30), c("set an alarm for 7 pm", ALARM, num = 19, num2 = 0), c("wake me up at 5", ALARM, num = 5, num2 = 0),
        c("alarm at 12 am", ALARM, num = 0, num2 = 0), c("alarm 9.15 p.m.", ALARM, num = 21, num2 = 15),
        c("timer 25 minutes", TIMER, num = 1500), c("set a timer for 10 minutes", TIMER, num = 600), c("timer for 30 seconds", TIMER, num = 30),
        c("start a timer for one hour", TIMER, num = 3600), c("5 minute timer", TIMER, num = 300), c("timer five mins", TIMER, num = 300),
        // --- call / search
        c("call Jia", CALL, "jia"), c("call mom please", CALL, "mom"), c("tawagan si Carlo", CALL, "carlo"), c("dial 09171234567", CALL, "09171234567"),
        c("search C++ for loops", SEARCH, "c++ for loops"), c("google how to price a website", SEARCH, "how to price a website"),
        c("look up Netlify pricing", SEARCH, "netlify pricing"), c("hanapin ang jollibee near me", SEARCH, "jollibee near me"),
        // --- settings pages
        c("open launcher settings", LAUNCHER_SETTINGS), c("settings", LAUNCHER_SETTINGS), c("open settings", LAUNCHER_SETTINGS),
        c("open wifi settings", SETTINGS_PAGE, "wi-fi"), c("open wi fi", SETTINGS_PAGE, "wi-fi"), c("bluetooth settings", SETTINGS_PAGE, "bluetooth"),
        c("open battery settings", SETTINGS_PAGE, "battery"), c("open display settings", SETTINGS_PAGE, "display"), c("open sound settings", SETTINGS_PAGE, "sound"),
        c("open location", SETTINGS_PAGE, "location"),
        // --- launcher toggles
        c("turn off the dock", TOGGLE, "dock", on = false), c("hide the sidebar", TOGGLE, "sidebar", on = false), c("enable simple mode", TOGGLE, "simple mode", on = true),
        c("turn the island off", TOGGLE, "island", on = false), c("show the app library", TOGGLE, "app library", on = true), c("toggle lyrics", TOGGLE, "lyrics", on = null),
        c("sound effects off", TOGGLE, "sound effects", on = false), c("disable haptics", TOGGLE, "haptics", on = true.not()),
        c("turn on game mode", TOGGLE, "game mode", on = true), c("can you turn off the dock please", TOGGLE, "dock", on = false),
        c("i-off ang dock", TOGGLE, "dock", on = false), c("patayin ang sidebar", TOGGLE, "sidebar", on = false), c("i-on yung lyrics", TOGGLE, "lyrics", on = true),
        c("turn on beats", TOGGLE, "beats", on = true), c("bring back the dock", TOGGLE, "dock", on = true), c("blur on", TOGGLE, "blur", on = true),
        // --- launcher styles / numbers
        c("set page transition to cube", SET_STYLE, "page transition\u0000cube"), c("change the dock style to dark", SET_STYLE, "dock style\u0000dark"),
        c("animation speed to fast", SET_STYLE, "animation speed\u0000fast"), c("set talk style to vision", SET_STYLE, "talk style\u0000vision"),
        c("make the island style glass", SET_STYLE, "island style\u0000glass"), c("switch notification style to both", SET_STYLE, "notification style\u0000both"),
        c("set icon size to 60", SET_NUMBER, "icon size", num = 60), c("columns to 5", SET_NUMBER, "columns", num = 5),
        c("change island size to 110%", SET_NUMBER, "island size", num = 110), c("rows to 6", SET_NUMBER, "rows", num = 6),
        // --- apps
        c("open Spotify", OPEN_APP, "spotify"), c("open messenger", OPEN_APP, "messenger"), c("launch TikTok", OPEN_APP, "tiktok"),
        c("open the camera app", OPEN_APP, "camera"), c("please open YouTube for me", OPEN_APP, "youtube"), c("buksan ang facebook", OPEN_APP, "facebook"),
        c("pakibuksan yung gcash", OPEN_APP, "gcash"), c("open up instagram", OPEN_APP, "instagram"), c("System online, open Spotify", OPEN_APP, "spotify"),
        c("hey gilbert open chrome", OPEN_APP, "chrome"), c("could you open canva", OPEN_APP, "canva"), c("go to gmail", OPEN_APP, "gmail"),
        c("start capcut", OPEN_APP, "capcut"), c("i-open mo ang notion", OPEN_APP, "notion"), c("pull up google maps", OPEN_APP, "google maps"),
        // --- brain questions (must NOT be grabbed as commands)
        c("Explain a while loop in Taglish", null), c("what is a truth table", null), c("how much should I charge for a website", null),
        c("write a facebook post for grain cafe", null), c("why is my code not compiling", null), c("ano ang pointer sa C++", null),
        c("tell me a joke", null), c("give me a study plan for my exam", null), c("I have 27 errors in my code", null),
        c("summarize what Jia said", null), c("how are you feeling", null), c("what time is it", null),
        // ===== round 2: fresh phrasings
        c("Gilbert can you please open spotify for me", OPEN_APP, "spotify"), c("open spotify po", OPEN_APP, "spotify"),
        c("yo gilbert open youtube", OPEN_APP, "youtube"), c("system online. open messenger.", OPEN_APP, "messenger"),
        c("uh open instagram", OPEN_APP, "instagram"), c("open the clock", OPEN_APP, "clock"), c("launch the calculator app", OPEN_APP, "calculator"),
        c("buksan mo nga ang spotify", OPEN_APP, "spotify"), c("paki open ng messenger", OPEN_APP, "messenger"),
        c("turn the flashlight off", FLASHLIGHT, on = false), c("flashlight", FLASHLIGHT, on = true), c("turn on torch light", FLASHLIGHT, on = true),
        c("increase the volume", VOLUME, "up"), c("decrease volume", VOLUME, "down"), c("volume down please", VOLUME, "down"), c("put it on silent", null),
        c("set brightness to 30 percent", BRIGHTNESS, num = 30), c("brighter", BRIGHTNESS, num = 100), c("full brightness", BRIGHTNESS, num = 100),
        c("pause the music", MEDIA, "pause"), c("play music", MEDIA, "play"), c("go back to the last song", null), c("next track", MEDIA, "next"),
        c("enable do not disturb", DND, on = true), c("do not disturbed off", DND, on = false), c("silent mode on", DND, on = true),
        c("lock my phone", LOCK), c("lock the screen please", LOCK),
        c("wake me up at 6:45 a.m.", ALARM, num = 6, num2 = 45), c("set alarm for 10 30 pm", ALARM, num = 22, num2 = 30), c("alarm 7 ng umaga", ALARM, num = 7, num2 = 0),
        c("set a 15 minute timer", null), c("timer 1 hour", TIMER, num = 3600), c("countdown 90 seconds", TIMER, num = 90), c("timer 5 minuto", TIMER, num = 300),
        c("call my mom", CALL, "my mom"), c("tawagan mo si jia", CALL, "jia"), c("search for cheap bond paper", SEARCH, "cheap bond paper"),
        c("add task finish the grocery website", TASK_ADD, "finish the grocery website"), c("add to do: pay internet bill", TASK_ADD, "pay internet bill"),
        c("new to-do: study C++ pointers", TASK_ADD, "study C++ pointers"), c("what's on my to do list", TASK_LIST), c("show me my to-dos", TASK_LIST),
        c("complete task number 3", TASK_DONE, num = 3), c("finish task #4", TASK_DONE, num = 4), c("task 2 is done", TASK_DONE, num = 2),
        c("remember to bring my ID tomorrow", NOTE_ADD, "to bring my ID tomorrow"), c("tandaan mo: may quiz bukas", NOTE_ADD, "may quiz bukas"),
        c("turn off the dynamic island", TOGGLE, "dynamic island", on = false), c("turn sound effects back on", TOGGLE, "sound effects back", on = true),
        c("disable the app library", TOGGLE, "app library", on = false), c("dock off", TOGGLE, "dock", on = false), c("hide labels", TOGGLE, "labels", on = false),
        c("change page transition to flip", SET_STYLE, "page transition\u0000flip"), c("set the dock style to glass", SET_STYLE, "dock style\u0000glass"),
        c("set columns to 4", SET_NUMBER, "columns", num = 4), c("icon size 70", null),
        c("open wifi", SETTINGS_PAGE, "wi-fi"), c("open bluetooth", SETTINGS_PAGE, "bluetooth"), c("open the battery settings", SETTINGS_PAGE, "battery"),
        c("go to files", WINDOW, "files"), c("open names", WINDOW, "names"), c("show the summary", WINDOW, "summary"),
        c("Explain recursion with an example", null), c("can you help me price a menu website", null), c("paano gumawa ng for loop", null),
        c("draft a reply to my client", null), c("is it going to rain", null), c("who is messaging me the most", null)
    )

    /** What the wake-word listener might hear → should it wake (and what's the command after it)? */
    val WAKE = listOf(
        "system online" to "", "System online open Spotify" to "open spotify", "systems online" to "", "sistem online" to "",
        "system on line" to "", "cistern online" to "", "system online, add task buy paper" to "add task buy paper",
        "hey gilbert" to "", "hey gilbert open messenger" to "open messenger", "ok gilbert" to "", "hey guilbert" to "",
        "online" to "", "on line" to "", "the system is online now" to "now", "system's online" to "",
        "what's the system" to null, "i'm online" to null, "hello" to null, "turn on the light" to null, "gilbert" to null,
        "my system is slow" to null, "go online later" to null
    )
}
