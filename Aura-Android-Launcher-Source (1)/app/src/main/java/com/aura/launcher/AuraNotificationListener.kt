package com.aura.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class AuraNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var lastMediaJson: JSONObject? = null
        @Volatile var lastBookJson: JSONObject? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

        if (pkg == "com.spotify.music" || pkg == "com.apple.android.music" || pkg == "com.google.android.apps.youtube.music") {
            if (title.isNotEmpty()) {
                lastMediaJson = JSONObject().apply {
                    put("title", title)
                    put("artist", text)
                    put("source", pkg)
                    put("isPlaying", true)
                }
            }
        }

        if (pkg == "com.amazon.kindle" || pkg == "com.google.android.apps.books") {
            if (title.isNotEmpty()) {
                lastBookJson = JSONObject().apply {
                    put("title", title)
                    put("author", if (text.isNotEmpty()) text else "Kindle")
                }
            }
        }
    }
}
