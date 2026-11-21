package com.luncher.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Gilbert's photo watcher. Messenger (and some other apps) never put the actual photo in their
 * notification — just "Sent a photo". But when a chat app SAVES a photo to the phone (Messenger:
 * Settings › Photos & media › Save photos), it lands in a folder like Pictures/Messenger. Gilbert
 * watches for those and puts the photo on the matching "Sent a photo" in his Files window.
 */
object PhotoWatcher {
    private var observer: ContentObserver? = null
    private var lastSeen = 0L      // seconds (MediaStore DATE_ADDED)
    private val main = Handler(Looper.getMainLooper())

    /** Folder name → the app it belongs to. */
    private val APPS = listOf("Messenger", "Instagram", "WhatsApp", "Telegram", "Viber", "Facebook", "Snapchat",
        "Discord", "Line", "Signal", "TikTok", "X", "Twitter")

    fun permission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun allowed(ctx: Context) = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        if (observer != null || !allowed(app)) return
        lastSeen = System.currentTimeMillis() / 1000 - 5
        val o = object : ContentObserver(main) {
            private val run = Runnable { check(app) }
            override fun onChange(selfChange: Boolean, uri: Uri?) { main.removeCallbacks(run); main.postDelayed(run, 1500) }
        }
        runCatching { app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o) }
            .onSuccess { observer = o }
    }

    private fun check(ctx: Context) {
        Thread {
            runCatching {
                val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
                    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA)
                ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                    "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf(lastSeen.toString()),
                    "${MediaStore.Images.Media.DATE_ADDED} ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0); val added = c.getLong(1); val path = c.getString(2) ?: ""
                        lastSeen = maxOf(lastSeen, added)
                        val segs = path.split('/', '\\').map { it.trim() }
                        val app = APPS.firstOrNull { a -> segs.any { it.equals(a, true) } } ?: continue
                        val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        val b = PocherFiles.decode(ctx, uri) ?: continue
                        PocherFiles.addPhoto(ctx, b, app, app, "Saved photo")
                    }
                }
            }
        }.start()
    }
}
