package com.luncher.home

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads notification counts for iOS-style badges. Granting this access also lets the
 * launcher see what music is playing (for the Now Playing widget).
 */
class NotificationService : NotificationListenerService() {

    companion object {
        /** Package name -> unread count. Only touched on the main thread. */
        val counts = HashMap<String, Int>()
        var onChange: (() -> Unit)? = null
        /** (package, title, text) for each newly posted, user-visible notification. */
        var onPosted: ((String, String, String) -> Unit)? = null
        /** Latest chat from each app (conversation + recent messages) for the side island's chat window. */
        val lastChat = HashMap<String, ChatInfo>()
        private val main = Handler(Looper.getMainLooper())
        /** Ongoing activities (navigation, clock, calls…) for the island, keyed by notification key. */
        val ongoing = LinkedHashMap<String, OngoingInfo>()
        var onOngoing: (() -> Unit)? = null
    }

    private fun track(sbn: StatusBarNotification) {
        val type = runCatching { IslandServices.classify(packageName, sbn) }.getOrNull()
        val info = if (type != null) runCatching { IslandServices.toInfo(type, sbn) }.getOrNull() else null
        main.post {
            if (info != null) ongoing[sbn.key] = info else if (ongoing.remove(sbn.key) == null) return@post
            onOngoing?.invoke()
        }
    }

    override fun onListenerConnected() {
        // Keeps the headphone pop-up listening while other apps are open.
        main.post { runCatching { HeadsetPopup.get(applicationContext) } }
        recount()
        runCatching { activeNotifications }.getOrNull()?.forEach { track(it) }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        recount()
        track(sbn)
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing || !sbn.isClearable) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val chat = runCatching { readChat(sbn, title, text) }.getOrNull()
        main.post {
            if (chat != null) lastChat[sbn.packageName] = chat
            onPosted?.invoke(sbn.packageName, title, text)
        }
    }
    /** Pull the conversation out of a chat notification (MessagingStyle / InboxStyle / plain). */
    private fun readChat(sbn: StatusBarNotification, title: String, text: String): ChatInfo {
        val ex = sbn.notification.extras
        val msgs = ArrayList<Pair<String, String>>()
        val media = ArrayList<ChatMedia?>()
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null) for (p in arr) {
            val b = p as? android.os.Bundle ?: continue
            val t = b.getCharSequence("text")?.toString() ?: ""
            @Suppress("DEPRECATION")
            val who = b.getCharSequence("sender")?.toString()
                ?: (if (android.os.Build.VERSION.SDK_INT >= 28) (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString() else null)
                ?: "You"
            // Photos / attachments sent in the chat (MessagingStyle "data").
            val mime = b.getString("type")
            @Suppress("DEPRECATION")
            val uri = b.getParcelable<android.net.Uri>("uri")
            val m = if (mime != null && uri != null) ChatMedia(if (mime.startsWith("image/")) loadImage(uri) else null, mime, uri) else null
            if (t.isBlank() && m == null) continue
            msgs += who to t
            media += m
        }
        if (msgs.isEmpty()) {
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null) for (l in lines) msgs += "" to l.toString()
        }
        while (media.size < msgs.size) media += null
        // A big picture (photo notification) goes with the last message.
        @Suppress("DEPRECATION")
        val pic = ex.getParcelable<android.graphics.Bitmap>(Notification.EXTRA_PICTURE)
        if (msgs.isEmpty()) { msgs += "" to text; media += null }
        if (pic != null && media.lastOrNull() == null) media[media.size - 1] = ChatMedia(scale(pic), "image/*", null)
        val convo = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        // Inline reply: the app's own "Reply" action (works for any app that offers one).
        val replyAction = sbn.notification.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val keep = minOf(20, msgs.size)
        return ChatInfo(sbn.packageName, convo?.takeIf { it.isNotBlank() } ?: title, msgs.takeLast(keep),
            sbn.notification.contentIntent, sbn.postTime, media.takeLast(keep),
            replyAction?.actionIntent, replyAction?.remoteInputs)
    }

    /** Decode a chat photo the app shared with notifications (small, to save memory). */
    private fun loadImage(uri: android.net.Uri): android.graphics.Bitmap? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 900) sample *= 2
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun scale(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= 900) return b
        val k = 900f / m
        return android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        recount()
        main.post { if (ongoing.remove(sbn.key) != null) onOngoing?.invoke() }
    }

    override fun onListenerDisconnected() {
        main.post {
            counts.clear()
            onChange?.invoke()
            ongoing.clear()
            onOngoing?.invoke()
        }
    }

    private fun recount() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        val m = HashMap<String, Int>()
        for (s in active) {
            if (!s.isClearable) continue
            if (s.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
            val n = s.notification.number.takeIf { it > 0 } ?: 1
            m[s.packageName] = (m[s.packageName] ?: 0) + n
        }
        main.post {
            counts.clear()
            counts.putAll(m)
            onChange?.invoke()
        }
    }
}

/** One chat from a notification: who/what it is, recent messages (sender, text) and how to open it. */
class ChatInfo(
    val pkg: String,
    val title: String,
    val messages: List<Pair<String, String>>,
    val open: android.app.PendingIntent?,
    val time: Long,
    /** Same length as [messages]: a photo / attachment for each message, or null. */
    val media: List<ChatMedia?> = emptyList(),
    /** The app's own reply action, so you can answer from the chat window. */
    val reply: android.app.PendingIntent? = null,
    val replyInputs: Array<android.app.RemoteInput>? = null
) {
    /** Send [text] through the app's reply action. Returns true if it was handed over. */
    fun sendReply(ctx: android.content.Context, text: String): Boolean {
        val pi = reply ?: return false
        val inputs = replyInputs ?: return false
        return runCatching {
            val data = android.os.Bundle()
            for (ri in inputs) data.putCharSequence(ri.resultKey, text)
            val i = android.content.Intent()
            android.app.RemoteInput.addResultsToIntent(inputs, i, data)
            pi.send(ctx, 0, i)
        }.isSuccess
    }
}

/** A photo or file attached to a chat message. */
class ChatMedia(val image: android.graphics.Bitmap?, val mime: String, val uri: android.net.Uri?) {
    val label get() = when {
        mime.startsWith("image/") -> "Photo"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Voice message"
        mime.contains("pdf") -> "PDF"
        else -> "Attachment"
    }
}
