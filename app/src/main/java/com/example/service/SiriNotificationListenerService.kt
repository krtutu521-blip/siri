package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.SiriApplication
import com.example.data.NotificationLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SiriNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification Listener service connected successfully")
        isRunning = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification Listener disconnected")
        isRunning = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName
        
        // Filter out common system services static notifications to avoid spamming
        if (packageName == "android" || packageName == "com.android.systemui" || packageName == "com.google.android.googlequicksearchbox") {
            return
        }

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString("android.title") ?: extras.getCharSequence("android.title")?.toString() ?: "Sender Unknown"
        val text = extras.getCharSequence("android.text")?.toString() ?: extras.getString("android.text") ?: ""

        if (text.isBlank() || text == "Listening...") return

        val appLabel = getAppName(this, packageName)

        Log.d(TAG, "Notification received from app label: $appLabel, title: $title, text: $text")

        // Save to Database
        serviceScope.launch {
            try {
                val siriDao = SiriApplication.instance.database.siriDao
                siriDao.insertNotificationLog(
                    NotificationLog(
                        appName = appLabel,
                        packageName = packageName,
                        senderName = title,
                        messageText = text
                    )
                )

                // Check user preferences. If Speak notifications is enabled, announce via broadcast
                val prefs = getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
                val isSpeakEnabled = prefs.getBoolean("speak_notifications_enabled", true)
                if (isSpeakEnabled) {
                    val spokenAnnouncement = buildAnnouncementString(appLabel, title, text)
                    speakAnnouncement(spokenAnnouncement, packageName, title, text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log or process notification", e)
            }
        }
    }

    private fun buildAnnouncementString(appLabel: String, sender: String, text: String): String {
        val app = appLabel.lowercase()
        return when {
            app.contains("whatsapp") -> {
                "Sir, WhatsApp par $sender ka message aaya hai. Message hai - $text. Kya main iska reply kar du?"
            }
            app.contains("telegram") -> {
                "Sir, Telegram par $sender ka message aaya hai. Message hai - $text. Kya main iska reply kar du?"
            }
            app.contains("instagram") -> {
                "Sir, Instagram par $sender ka message aaya hai. Message hai - $text. Kya main iska reply kar du?"
            }
            app.contains("messenger") -> {
                "Sir, Messenger par $sender ka message aaya hai. Message hai - $text. Kya main iska reply kar du?"
            }
            app.contains("sms") || app.contains("message") || app.contains("mms") || app.contains("messaging") || app.contains("airtel") -> {
                "Sir, SMS par $sender ka message aaya hai. Message hai - $text. Kya main iska reply kar du?"
            }
            else -> {
                "Sir, $appLabel par $sender ka message aaya hai. Message hai - $text. Kya main iska reply kar du?"
            }
        }
    }

    private fun speakAnnouncement(message: String, packageName: String, sender: String, text: String) {
        val intent = Intent(ACTION_ANNOUNCE).apply {
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_TEXT, text)
        }
        sendBroadcast(intent)
    }

    private fun getAppName(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.split(".").lastOrNull() ?: "App"
        }
    }

    companion object {
        private const val TAG = "NotificationListener"
        const val ACTION_ANNOUNCE = "com.example.service.action.ANNOUNCE_NOTIFICATION"
        const val EXTRA_MESSAGE = "extra_announce_message"
        const val EXTRA_PACKAGE = "extra_package_name"
        const val EXTRA_SENDER = "extra_sender_name"
        const val EXTRA_TEXT = "extra_text_content"
        var isRunning = false
    }
}
