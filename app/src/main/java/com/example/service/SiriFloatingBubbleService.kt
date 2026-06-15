package com.example.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import kotlin.math.roundToInt

class SiriFloatingBubbleService : Service(), ViewModelStoreOwner {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private val mViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = mViewModelStore

    private var rmsValue = mutableStateOf(0f)
    private var assistantStateStr = mutableStateOf("IDLE")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (it.action) {
                    SiriForegroundService.ACTION_RMS_CHANGED -> {
                        val rms = it.getFloatExtra(SiriForegroundService.EXTRA_RMS_VALUE, 0f)
                        rmsValue.value = rms
                    }
                    "com.example.service.action.STATE_CHANGED" -> {
                        val state = it.getStringExtra("extra_state_name") ?: "IDLE"
                        assistantStateStr.value = state
                    }
                    SiriForegroundService.ACTION_WAKE_WORD_DETECTED -> {
                        assistantStateStr.value = "LISTENING"
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingBubble()

        val filter = IntentFilter().apply {
            addAction(SiriForegroundService.ACTION_RMS_CHANGED)
            addAction("com.example.service.action.STATE_CHANGED")
            addAction(SiriForegroundService.ACTION_WAKE_WORD_DETECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun createFloatingBubble() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 150
            y = 450
        }

        val lifecycleOwner = MyLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(this@SiriFloatingBubbleService)
            
            setContent {
                BubbleContent(
                    rms = rmsValue.value,
                    state = assistantStateStr.value,
                    onTouchMove = { dx, dy ->
                        params.x += dx.roundToInt()
                        params.y += dy.roundToInt()
                        windowManager.updateViewLayout(this, params)
                    },
                    onTap = {
                        openApp()
                    }
                )
            }
        }

        windowManager.addView(composeView, params)
    }

    private fun openApp() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("active_listen_bubble", true)
        }
        startActivity(launchIntent)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e("BubbleService", "Error unregistering receiver", e)
        }
        super.onDestroy()
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Already removed
            }
        }
    }

    class MyLifecycleOwner : SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }

        fun performRestore(savedState: android.os.Bundle?) {
            savedStateRegistryController.performRestore(savedState)
        }
    }
}

@Composable
fun BubbleContent(
    rms: Float,
    state: String,
    onTouchMove: (Float, Float) -> Unit,
    onTap: () -> Unit
) {
    val isListening = state == "LISTENING"
    val isExecuting = state == "THINKING" || state == "EXECUTING"
    val isSpeaking = state == "SPEAKING"
    
    val bubbleColor1 = when {
        isListening -> Color(0xFFF43F5E)
        isExecuting -> Color(0xFFA855F7)
        isSpeaking -> Color(0xFF06B6D4)
        else -> Color(0xFF06B6D4)
    }
    
    val bubbleColor2 = when {
        isListening -> Color(0xFFD946EF)
        isExecuting -> Color(0xFF6366F1)
        isSpeaking -> Color(0xFF3B82F6)
        else -> Color(0xFF6366F1)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "bubble_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) (1.1f + (rms / 25f).coerceAtMost(0.3f)) else if (isSpeaking) 1.12f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 350 else 1150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .size((72 * pulseScale).dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onTouchMove(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(bubbleColor1, bubbleColor2)
                )
            )
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(0xFF07051A)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = when {
                        isListening -> "🎙️ LISTEN"
                        isExecuting -> "🌀 Siri.."
                        isSpeaking -> "🗣️ TALK"
                        else -> "⚡ Siri"
                    },
                    color = bubbleColor1,
                    fontSize = 10.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}
