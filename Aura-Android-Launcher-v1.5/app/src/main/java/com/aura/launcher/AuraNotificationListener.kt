package com.aura.launcher

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class AuraNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile var isListenerConnected: Boolean = false
        @Volatile var lastMediaJson: JSONObject? = null
        @Volatile var lastMediaPackage: String? = null
        @Volatile var lastBookJson: JSONObject? = null
        @Volatile var lastBookPackage: String? = null
        private val notifLock = Any()
        private val recentNotifications = ArrayList<JSONObject>()

        private val MEDIA_PACKAGES = setOf(
            "com.spotify.music",
            "com.spotify.lite",
            "com.apple.android.music",
            "com.google.android.apps.youtube.music",
            "com.sec.android.app.music",
            "com.google.android.youtube",
            "com.amazon.mp3",
            "com.aspiro.tidal",
            "deezer.android.app",
            "com.soundcloud.android",
            "com.audible.application"
        )

        private val BOOK_PACKAGES = setOf(
            "com.amazon.kindle",
            "com.google.android.apps.books",
            "com.audible.application",
            "com.kobo.android"
        )

        fun getRecentNotificationsJson(): String {
            return try {
                val snapshot = synchronized(notifLock) { ArrayList(recentNotifications) }
                val arr = JSONArray()
                for (item in snapshot) {
                    arr.put(item)
                }
                arr.toString()
            } catch (_: Throwable) {
                "[]"
            }
        }
    }

    override fun onListenerConnected() {
        try {
            super.onListenerConnected()
        } catch (_: Throwable) {
            // ignore
        }
        isListenerConnected = true
    }

    override fun onListenerDisconnected() {
        isListenerConnected = false
        lastMediaJson = null
        lastMediaPackage = null
        lastBookJson = null
        lastBookPackage = null
        try {
            super.onListenerDisconnected()
        } catch (_: Throwable) {
            // ignore
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val pkg = sbn.packageName ?: return
            if (pkg == applicationContext.packageName) return
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()

            if (pkg in MEDIA_PACKAGES) {
                if (title.isNotEmpty()) {
                    lastMediaPackage = pkg
                    lastMediaJson = JSONObject().apply {
                        put("title", title)
                        put("artist", text)
                        put("source", pkg)
                        put("isPlaying", true)
                    }
                }
                return
            }

            if (pkg in BOOK_PACKAGES) {
                if (title.isNotEmpty()) {
                    lastBookPackage = pkg
                    lastBookJson = JSONObject().apply {
                        put("title", title)
                        put("author", if (text.isNotEmpty()) text else "Leitura")
                    }
                }
                return
            }

            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            if (isOngoing || (title.isEmpty() && text.isEmpty())) return

            val appLabel = try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info)?.toString()?.trim().orEmpty()
            } catch (_: Throwable) {
                pkg
            }

            val preview = when {
                title.isNotEmpty() && text.isNotEmpty() -> "$title: $text"
                title.isNotEmpty() -> title
                else -> text
            }

            val entryId = sbn.key ?: "$pkg-${sbn.postTime}"
            val entry = JSONObject().apply {
                put("id", entryId)
                put("packageName", pkg)
                put("app", if (appLabel.isNotEmpty()) appLabel else pkg)
                put("preview", preview.take(140))
                put("postedAtMs", sbn.postTime)
            }

            synchronized(notifLock) {
                recentNotifications.removeAll { it.optString("id") == entryId }
                recentNotifications.add(0, entry)
                while (recentNotifications.size > 12) {
                    recentNotifications.removeAt(recentNotifications.size - 1)
                }
            }
        } catch (_: Throwable) {
            // Nunca derruba o processo do sistema/launcher por erro de extra de notificação
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            val pkg = sbn.packageName
            if (pkg != null) {
                if (pkg == lastMediaPackage) {
                    lastMediaJson = null
                    lastMediaPackage = null
                }
                if (pkg == lastBookPackage) {
                    lastBookJson = null
                    lastBookPackage = null
                }
            }
            val key = sbn.key ?: return
            synchronized(notifLock) {
                recentNotifications.removeAll { it.optString("id") == key }
            }
        } catch (_: Throwable) {
            // ignore
        }
    }
}
