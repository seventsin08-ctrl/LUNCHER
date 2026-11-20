package com.luncher.home

/** The looks for the Now Playing widget (Settings › Home Screen & Widgets › Music Player Style). */
object PlayerStyles {
    /** art: classic | vinyl | square | round | poster | none.  tone: dark | light | glass | album | accent */
    class Style(val key: String, val name: String, val art: String, val tone: String, val artRight: Boolean = false)

    val all: List<Style> by lazy {
        val list = ArrayList<Style>()
        list += Style("classic", "Classic", "classic", "glass")
        val tones = listOf("dark" to "Dark", "light" to "Light", "glass" to "Glass", "album" to "Album Color", "accent" to "Theme Color")
        for ((t, tn) in tones) list += Style("vinyl_$t", "Vinyl $tn", "vinyl", t)
        for ((t, tn) in tones.take(4)) list += Style("vinylr_$t", "Vinyl Right $tn", "vinyl", t, true)
        for ((t, tn) in tones) list += Style("square_$t", "Album $tn", "square", t)
        for ((t, tn) in tones.take(4)) list += Style("squarer_$t", "Album Right $tn", "square", t, true)
        for ((t, tn) in tones.take(4)) list += Style("round_$t", "Disc $tn", "round", t)
        list += Style("poster_dark", "Poster Dark", "poster", "dark")
        list += Style("poster_light", "Poster Light", "poster", "light")
        list += Style("poster_album", "Poster Album", "poster", "album")
        list += Style("minimal_dark", "Minimal Dark", "none", "dark")
        list += Style("minimal_light", "Minimal Light", "none", "light")
        list += Style("minimal_album", "Minimal Album", "none", "album")
        list
    }

    fun playerStyle(key: String): Style = when (key) {
        "vinyl" -> all.first { it.key == "vinyl_dark" }
        else -> all.firstOrNull { it.key == key } ?: all.first()
    }
}
