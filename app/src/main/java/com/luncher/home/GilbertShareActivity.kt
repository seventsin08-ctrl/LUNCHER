package com.luncher.home

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * "Share → Gilbert": share any photo (e.g. from Messenger) and Gilbert keeps and scans it.
 * If that app had a "Sent a photo" waiting in his Files, the photo goes on it.
 */
class GilbertShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (intent?.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        }
        // Which app shared it (to match its "Sent a photo")?
        val from = runCatching { referrer?.host }.getOrNull()?.takeIf { it != packageName }
        val label = from?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        var n = 0
        for (u in uris.take(10)) {
            // Read it now, while we're allowed to.
            val b = PocherFiles.decode(this, u) ?: continue
            PocherFiles.addPhoto(this, b, label, label ?: "Shared", "Shared to Gilbert")
            n++
        }
        android.widget.Toast.makeText(this, if (n > 0) "Gilbert is scanning $n image${if (n > 1) "s" else ""}" else "Couldn't read that image",
            android.widget.Toast.LENGTH_SHORT).show()
        if (n > 0) {
            GilbertConsole.startWin = 5
            startActivity(Intent(this, GilbertChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
