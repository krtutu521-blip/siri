package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AutoScrollState {
    STOPPED,
    SCROLLING,
    PAUSED
}

object SiriAutoScrollController {
    private const val TAG = "AutoScrollController"

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive

    private val _scrollState = MutableStateFlow(AutoScrollState.STOPPED)
    val scrollState: StateFlow<AutoScrollState> = _scrollState

    private val _timerDuration = MutableStateFlow(25) // Selected timer in seconds
    val timerDuration: StateFlow<Int> = _timerDuration

    private val _secondsRemaining = MutableStateFlow(25)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining

    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (_scrollState.value != AutoScrollState.SCROLLING) return

            val remaining = _secondsRemaining.value
            if (remaining <= 1) {
                // Perform the scroll!
                Log.d(TAG, "Timer reached zero, triggering swipe.")
                val swiped = performAutoSwipe()
                Log.d(TAG, "Swipe gesture result: $swiped")

                // Reset remaining and post next
                _secondsRemaining.value = _timerDuration.value
            } else {
                _secondsRemaining.value = remaining - 1
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun init(ctx: Context) {
        this.context = ctx.applicationContext
    }

    fun setTimerDuration(seconds: Int) {
        _timerDuration.value = seconds
        if (_scrollState.value == AutoScrollState.STOPPED) {
            _secondsRemaining.value = seconds
        }
    }

    fun start(seconds: Int? = null) {
        if (seconds != null) {
            _timerDuration.value = seconds
        }
        _secondsRemaining.value = _timerDuration.value
        _scrollState.value = AutoScrollState.SCROLLING
        _isActive.value = true

        handler.removeCallbacks(countdownRunnable)
        handler.postDelayed(countdownRunnable, 1000)

        context?.let { ctx ->
            val intent = Intent(ctx, SiriAutoScrollFloatingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    fun pause() {
        if (_scrollState.value == AutoScrollState.SCROLLING) {
            _scrollState.value = AutoScrollState.PAUSED
            handler.removeCallbacks(countdownRunnable)
        }
    }

    fun resume() {
        if (_scrollState.value == AutoScrollState.PAUSED) {
            _scrollState.value = AutoScrollState.SCROLLING
            handler.removeCallbacks(countdownRunnable)
            handler.postDelayed(countdownRunnable, 1000)
        }
    }

    fun stop() {
        _scrollState.value = AutoScrollState.STOPPED
        _isActive.value = false
        handler.removeCallbacks(countdownRunnable)

        context?.let { ctx ->
            val intent = Intent(ctx, SiriAutoScrollFloatingService::class.java)
            ctx.stopService(intent)
        }
    }

    private fun performAutoSwipe(): Boolean {
        val ctx = context ?: return false
        val keyguardManager = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isLocked = keyguardManager?.isKeyguardLocked ?: false
        if (isLocked) {
            Log.d(TAG, "Keyguard is locked, skipping swipe.")
            return false
        }

        // Only scroll if target app is active
        val currentPkg = SiriAccessibilityService.currentPackage ?: ""
        val targetApps = listOf("com.instagram.android", "com.google.android.youtube", "com.facebook.katana")
        if (currentPkg.isNotEmpty() && !targetApps.contains(currentPkg)) {
            Log.d(TAG, "Target app not in foreground ($currentPkg), skipping swipe.")
            return false
        }

        return SiriAccessibilityService.swipeToNextReel()
    }
}
