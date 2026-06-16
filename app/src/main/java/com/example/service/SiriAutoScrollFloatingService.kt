package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPink
import kotlin.math.roundToInt

class SiriAutoScrollFloatingService : Service(), ViewModelStoreOwner {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private val mViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = mViewModelStore

    companion object {
        private const val NOTIFICATION_ID = 4843
        private const val CHANNEL_ID = "SiriAutoScrollServiceChannel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Show as foreground service to comply with Android background execution guidelines
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())

        createFloatingBubble()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Siri Auto Scroll Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 940, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Siri Auto Scroller Active")
            .setContentText("Instagram reels auto scroll helper is running.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
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
            x = 300
            y = 600
        }

        val lifecycleOwner = MyLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(this@SiriAutoScrollFloatingService)
            
            setContent {
                AutoScrollBubbleContent(
                    onTouchMove = { dx, dy ->
                        params.x += dx.roundToInt()
                        params.y += dy.roundToInt()
                        try {
                            windowManager.updateViewLayout(this, params)
                        } catch (e: Exception) {
                            Log.e("AutoScrollFloating", "Error updating view layout", e)
                        }
                    }
                )
            }
        }

        try {
            windowManager.addView(composeView, params)
        } catch (e: Exception) {
            Log.e("AutoScrollFloating", "Failed to add overlay view", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("AutoScrollFloating", "Error removing view", e)
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
fun AutoScrollBubbleContent(
    onTouchMove: (Float, Float) -> Unit
) {
    val scrollState by SiriAutoScrollController.scrollState.collectAsState()
    val timerDuration by SiriAutoScrollController.timerDuration.collectAsState()
    val secondsRemaining by SiriAutoScrollController.secondsRemaining.collectAsState()

    // Neon colors
    val neonBlue = Color(0xFF00E5FF)
    val neonPink = Color(0xFFFF2A7A)
    val neonPurple = Color(0xFFA110FF)
    
    val ringColor = if (scrollState == AutoScrollState.SCROLLING) neonBlue else neonPink
    val progress = if (timerDuration > 0) secondsRemaining.toFloat() / timerDuration.toFloat() else 1f

    Row(
        modifier = Modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onTouchMove(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xE4080721),
                        Color(0xE4140D3E)
                    )
                )
            )
            .border(
                1.5.dp,
                Brush.linearGradient(
                    colors = listOf(
                        ringColor.copy(alpha = 0.6f),
                        neonPurple.copy(alpha = 0.4f)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Glowing animated countdown circle
        Box(
            modifier = Modifier
                .size(42.dp)
                .drawBehind {
                    // Draw outer glow
                    drawCircle(
                        color = ringColor.copy(alpha = 0.1f),
                        radius = size.width / 2f + 4.dp.toPx()
                    )
                    
                    // Draw grey track
                    drawCircle(
                        color = Color.White.copy(alpha = 0.1f),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    
                    // Draw active progress ring arc
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx())
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${secondsRemaining}s",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Title and status text
        Column(
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Auto Scrolling",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when (scrollState) {
                    AutoScrollState.SCROLLING -> "Active"
                    AutoScrollState.PAUSED -> "Paused"
                    else -> "Stopped"
                },
                color = if (scrollState == AutoScrollState.SCROLLING) neonBlue else neonPink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Control Buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Play / Pause toggle
            if (scrollState == AutoScrollState.SCROLLING) {
                IconButton(
                    onClick = { SiriAutoScrollController.pause() },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "Pause",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = { SiriAutoScrollController.resume() },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Resume",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Stop button
            IconButton(
                onClick = { SiriAutoScrollController.stop() },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = neonPink,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
