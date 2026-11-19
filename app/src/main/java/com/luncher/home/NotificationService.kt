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
        @Suppress("DEPRECATION")
        val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (arr != null) for (p in arr) {
            val b = p as? android.os.Bundle ?: continue
            val t = b.getCharSequence("text")?.toString() ?: continue
            @Suppress("DEPRECATION")
            val who = b.getCharSequence("sender")?.toString()
                ?: (if (android.os.Build.VERSION.SDK_INT >= 28) (b.getParcelable<android.app.Person>("sender_person"))?.name?.toString() else null)
                ?: "You"
            msgs += who to t
        }
        if (msgs.isEmpty()) {
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null) for (l in lines) msgs += "" to l.toString()
        }
        if (msgs.isEmpty()) msgs += "" to text
        val convo = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        return ChatInfo(sbn.packageName, convo?.takeIf { it.isNotBlank() } ?: title, msgs.takeLast(20),
            sbn.notification.contentIntent, sbn.postTime)
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
    val time: Long
)
