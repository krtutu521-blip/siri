package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.os.Build
import android.content.Context

class SiriAccessibilityService : AccessibilityService() {

    private var powerButtonDownTime: Long = 0
    private var volumeDownButtonDownTime: Long = 0
    
    private val keyHandler = Handler(Looper.getMainLooper())
    
    private val powerButtonRunnable = Runnable {
        Log.d("SiriAccessibility", "LONG PRESS DETECTED ON POWER BUTTON (Siri assistant)")
        triggerAssistantOverlay()
    }
    
    private val volumeDownButtonRunnable = Runnable {
        Log.d("SiriAccessibility", "LONG PRESS DETECTED ON VOLUME DOWN BUTTON (Siri assistant)")
        triggerAssistantOverlay()
    }

    private fun triggerAssistantOverlay() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }

        val launchIntent = Intent(this, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("power_button_trigger", true)
        }
        startActivity(launchIntent)
        Log.d("SiriAccessibility", "Long press successfully triggered MainActivity overlay.")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        
        Log.d("SiriAccessibility", "onKeyEvent: code=$keyCode, action=$action")
        
        // 1. Check Power Button Long Press
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    powerButtonDownTime = System.currentTimeMillis()
                    keyHandler.removeCallbacks(powerButtonRunnable)
                    keyHandler.postDelayed(powerButtonRunnable, 2000) // 2 seconds
                }
                return false // Pass down so system doesn't immediately lock screen
            } else if (action == KeyEvent.ACTION_UP) {
                keyHandler.removeCallbacks(powerButtonRunnable)
                val duration = System.currentTimeMillis() - powerButtonDownTime
                if (duration >= 2000) {
                    return true // Consume event
                }
                return false
            }
        }
        
        // 2. Check Volume Down Button hold as fallback (incredible user accessibility feature requested!)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    volumeDownButtonDownTime = System.currentTimeMillis()
                    keyHandler.removeCallbacks(volumeDownButtonRunnable)
                    keyHandler.postDelayed(volumeDownButtonRunnable, 2000) // 2 seconds
                }
                return false
            } else if (action == KeyEvent.ACTION_UP) {
                keyHandler.removeCallbacks(volumeDownButtonRunnable)
                val duration = System.currentTimeMillis() - volumeDownButtonDownTime
                if (duration >= 2000) {
                    return true // Consume volume event so it doesn't decrease volume on release
                }
                return false
            }
        }
        
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (packageName != null) {
                currentPackage = packageName
                Log.d("SiriAccessibility", "Current foreground package updated: $packageName")
            }
        }
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("SiriAccessibility", "Accessibility Service Connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("SiriAccessibility", "Accessibility Service Unbound (Disconnected)")
        sendAccessibilityDisconnectNotification()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun sendAccessibilityDisconnectNotification() {
        try {
            val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // Create target intent opening accessibility settings directory
            val settingsIntent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 1235, settingsIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            val channelId = "SiriAccessibilityServiceChannel_v2"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Siri Automation Service Status Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
                notificationManager.createNotificationChannel(channel)
            }
            
            val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setContentTitle("Siri Automation Disconnected")
                .setContentText("Tap to reconnect and restore voice Whatsapp/SMS messaging.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
                
            notificationManager.notify(7721, notification)
        } catch (e: Exception) {
            Log.e("SiriAccessibility", "Failed to send disconnect notification", e)
        }
    }

    companion object {
        private var instance: SiriAccessibilityService? = null
        var currentPackage: String? = null

        val isRunning: Boolean
            get() = instance != null

        /**
         * Simulates a smooth vertical swipe from bottom to top of the screen to change reels.
         */
        fun swipeToNextReel(): Boolean {
            val service = instance ?: return false
            val displayMetrics = service.resources.displayMetrics
            val height = displayMetrics.heightPixels
            val width = displayMetrics.widthPixels
            
            val x = width / 2f
            val fromY = height * 0.75f
            val toY = height * 0.25f
            
            val path = Path()
            path.moveTo(x, fromY)
            path.lineTo(x, toY)
            
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            return service.dispatchGesture(gestureBuilder.build(), null, null)
        }

        /**
         * Simulates typing text into an active input field
         */
        fun typeText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                focusedNode.recycle()
                return success
            }
            return false
        }

        /**
         * Clicks a button in the active window by its text or content description
         */
        fun clickNodeByText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    return clicked
                } else {
                    // Try parent clicking if the text node itself is not clickable
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            node.recycle()
                            return clicked
                        }
                        parent = parent.parent
                    }
                }
                node.recycle()
            }
            return false
        }

        /**
         * Performs a click gesture at specific screen coordinates
         */
        fun performClickAt(x: Float, y: Float): Boolean {
            val service = instance ?: return false
            val path = Path()
            path.moveTo(x, y)
            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            return service.dispatchGesture(gestureBuilder.build(), null, null)
        }

        /**
         * Clicks a button in the active window by its content description
         */
        fun clickNodeByDescription(desc: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            return clickNodeByDescRecursive(root, desc.lowercase())
        }

        private fun clickNodeByDescRecursive(node: AccessibilityNodeInfo?, desc: String): Boolean {
            if (node == null) return false
            val contentDesc = node.contentDescription?.toString()?.lowercase()
            if (contentDesc != null && contentDesc.contains(desc)) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return clicked
                } else {
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            return clicked
                        }
                        parent = parent.parent
                    }
                }
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (clickNodeByDescRecursive(child, desc)) {
                    return true
                }
            }
            return false
        }

        /**
         * Performs vertical scroll forwards or backwards
         */
        fun scroll(forward: Boolean): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            val success = scrollRecursive(root, forward)
            try {
                root.recycle()
            } catch (e: Exception) {}
            return success
        }

        private fun scrollRecursive(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
            if (node == null) return false
            if (node.isScrollable) {
                val action = if (forward) {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                val success = node.performAction(action)
                if (success) return true
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (scrollRecursive(child, forward)) {
                    try {
                        child?.recycle()
                    } catch (e: Exception) {}
                    return true
                }
                try {
                    child?.recycle()
                } catch (e: Exception) {}
            }
            return false
        }

        /**
         * Read screen text content recursively
         */
        fun getScreenContentText(): String {
            val service = instance ?: return "Accessibility Service is not connected."
            val root = service.rootInActiveWindow ?: return "Screen is empty or not accessible."
            val stringBuilder = StringBuilder()
            extractTextRecursive(root, stringBuilder)
            try {
                root.recycle()
            } catch (e: Exception) {}
            return if (stringBuilder.isEmpty()) "No readable text found on screen." else stringBuilder.toString()
        }

        private fun extractTextRecursive(node: AccessibilityNodeInfo?, sb: StringBuilder) {
            if (node == null) return
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if (!text.isNullOrBlank()) {
                sb.append(text).append("\n")
            } else if (!desc.isNullOrBlank()) {
                sb.append("[").append(desc).append("]\n")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                extractTextRecursive(child, sb)
                try {
                    child?.recycle()
                } catch (e: Exception) {}
            }
        }
    }
}
