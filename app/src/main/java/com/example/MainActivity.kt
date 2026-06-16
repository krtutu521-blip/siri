package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import com.example.data.*
import java.util.Locale
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.service.SiriForegroundService
import com.example.ui.theme.*
import com.example.viewmodel.AssistantState
import com.example.viewmodel.SiriViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val siriTriggeredCommand = mutableStateOf<String?>(null)

    private val brightnessReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.action.SET_WINDOW_BRIGHTNESS") {
                val percent = intent.getIntExtra("brightness_percent", 50)
                val layoutParams = window.attributes
                layoutParams.screenBrightness = (percent / 100f).coerceIn(0.01f, 1.0f)
                window.attributes = layoutParams
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let {
            val triggered = it.getBooleanExtra(SiriForegroundService.EXTRA_WAKE_WORD_TRIGGERED, false)
            val bubbleTriggered = it.getBooleanExtra("active_listen_bubble", false)
            val powerTriggered = it.getBooleanExtra("power_button_trigger", false)
            
            android.util.Log.d("MainActivity", "handleIntent: triggered = $triggered, bubbleTriggered = $bubbleTriggered, powerTriggered = $powerTriggered")
            
            if (triggered || bubbleTriggered || powerTriggered) {
                val command = it.getStringExtra(SiriForegroundService.EXTRA_INITIAL_COMMAND) ?: ""
                if (command.isNotBlank()) {
                    siriTriggeredCommand.value = command
                } else {
                    siriTriggeredCommand.value = "ACTIVATE_WITH_GREETING"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        val filter = android.content.IntentFilter("com.example.action.SET_WINDOW_BRIGHTNESS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(brightnessReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(brightnessReceiver, filter)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(CyberBg),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    SiriDashboardScreen(
                        modifier = Modifier.padding(innerPadding),
                        onOpenSystemSetting = { systemIntent ->
                            try {
                                startActivity(systemIntent)
                            } catch (e: Exception) {
                                Toast.makeText(this, "Could not open system settings panel.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        siriTriggeredCommand = siriTriggeredCommand
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(brightnessReceiver)
        } catch (e: Exception) {}
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun SiriDashboardScreen(
    modifier: Modifier = Modifier,
    onOpenSystemSetting: (Intent) -> Unit,
    siriTriggeredCommand: MutableState<String?> = mutableStateOf(null),
    viewModel: SiriViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val commandLogs by viewModel.commandLogs.collectAsState()
    val assistantState by viewModel.assistantState.collectAsState()
    val speechInputText by viewModel.speechInputText.collectAsState()
    val errorMsg by viewModel.errorMessage.collectAsState()
    val rmsDb by viewModel.rmsDb.collectAsState()
    val notificationLogs by viewModel.notificationLogs.collectAsState()
    val notificationAccessGranted by viewModel.isNotificationAccessGranted.collectAsState()
    val speakNotificationsEnabled by viewModel.speakNotificationsEnabled.collectAsState()

    // Handle incoming background wake triggers
    val triggeredCommand = siriTriggeredCommand.value
    LaunchedEffect(triggeredCommand) {
        if (triggeredCommand != null) {
            android.util.Log.d("MainActivity", "Siri launched from background. Trigger is: $triggeredCommand")
            // Vibration feedback on detection
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(150)
            }
            
            if (triggeredCommand == "ACTIVATE_WITH_GREETING" || triggeredCommand == "ACTIVATE") {
                viewModel.activateFromPowerButton()
            } else {
                viewModel.executeTextOrVoiceCommand(triggeredCommand)
            }
            siriTriggeredCommand.value = null // reset
        }
    }

    // Service Toggles
    val backgroundActive by viewModel.isBackgroundServiceRunning.collectAsState()
    val bubbleActive by viewModel.isBubbleOverlayActive.collectAsState()
    val accessibilityActive by viewModel.isAccessibilityEnabled.collectAsState()
    val batteryIgnored by viewModel.isBatteryOptimizationIgnored.collectAsState()

    // Tab State
    var currentTab by remember { mutableStateOf("home") }

    // Multi-Permission Request Launcher
    val requiredPermissions = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.all { it.value }
        if (granted) {
            Toast.makeText(context, "Permissions granted! Siri initialized.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Limited permissions. Some triggers may require manual settings.", Toast.LENGTH_LONG).show()
        }
        viewModel.checkBackgroundStates()
    }

    // Dismiss error messages gracefully
    LaunchedEffect(errorMsg) {
        errorMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.dismissError()
        }
    }

    // Check permissions initially and request battery optimization ignore
    LaunchedEffect(Unit) {
        viewModel.checkBackgroundStates()
        
        delay(1000)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(batteryIntent)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to launch battery ignore permission popup automatically", e)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBg)
        ) {
        // TOP CYBER HEADER
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .drawBehind {
                    val strokeW = 1.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.05f),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = strokeW
                    )
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Elegant Gradient Avatar
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(NeonCyan, NeonPurple)
                                )
                            )
                            .padding(2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(CyberBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan)
                            )
                        }
                    }

                    Column {
                        val textGradient = Brush.horizontalGradient(
                            colors = listOf(Color.White, Color(0xFF94A3B8))
                        )
                        Text(
                            text = "Siri AI Assistant",
                            style = TextStyle(
                                brush = textGradient,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                        )
                        Text(
                            text = "SYSTEM STATUS: ACTIVE // MULTILINGUAL",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
                
                // Status Beacon Glow
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (backgroundActive) NeonGreen else NeonPink)
                        .border(
                            2.dp, 
                            if (backgroundActive) NeonGreen.copy(alpha = 0.5f) else NeonPink.copy(alpha = 0.5f), 
                            CircleShape
                        )
                )
            }
        }

        // NAVIGATION TABS CHIPS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Home / Talk Tab
            CustomTabChip(
                label = "CORE",
                active = currentTab == "home",
                icon = Icons.Default.Mic,
                testTag = "tab_core_button",
                onClick = { currentTab = "home" }
            )
            // Smart Auto Scroll Assistant Dashboard Tab
            CustomTabChip(
                label = "SCROLLER",
                active = currentTab == "scroller",
                icon = Icons.Default.Refresh,
                testTag = "tab_scroller_button",
                onClick = { currentTab = "scroller" }
            )
            // Automations Info / Commands Tab
            CustomTabChip(
                label = "COMMANDS",
                active = currentTab == "commands",
                icon = Icons.Default.SettingsApplications,
                testTag = "tab_commands_button",
                onClick = { currentTab = "commands" }
            )
            // System permissions & triggers tab
            CustomTabChip(
                label = "AUTOMATION",
                active = currentTab == "automation",
                icon = Icons.Default.Shield,
                testTag = "tab_automation_button",
                onClick = { currentTab = "automation" }
            )
            // Health tracker dashboard tab
            CustomTabChip(
                label = "HEALTH",
                active = currentTab == "health",
                icon = Icons.Default.Favorite,
                testTag = "tab_health_button",
                onClick = { currentTab = "health" }
            )
        }

        // MAIN WORKSPACE PANELS
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    slideInHorizontally { width -> width } + fadeIn() with slideOutHorizontally { width -> -width } + fadeOut()
                },
                label = "tab_transition"
            ) { targetTab ->
                when (targetTab) {
                    "home" -> HomeAssistantPanel(
                        viewModel = viewModel,
                        assistantState = assistantState,
                        speechInputText = speechInputText,
                        rmsDb = rmsDb,
                        chatMessages = chatMessages
                    )
                    "commands" -> CommandsLibraryPanel(
                        onCommandSelected = { command ->
                            currentTab = "home"
                            viewModel.executeTextOrVoiceCommand(command)
                        }
                    )
                    "automation" -> AutomationDashboardPanel(
                        backgroundEnabled = backgroundActive,
                        bubbleEnabled = bubbleActive,
                        accessibilityEnabled = accessibilityActive,
                        batteryOptimizationIgnored = batteryIgnored,
                        notificationAccessGranted = notificationAccessGranted,
                        speakNotificationsEnabled = speakNotificationsEnabled,
                        onToggleVoiceService = { enabled ->
                            if (enabled) {
                                permissionLauncher.launch(requiredPermissions)
                            }
                            viewModel.toggleBackgroundService(enabled)
                        },
                        onToggleBubble = { enabled ->
                            if (enabled && !Settings.canDrawOverlays(context)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                onOpenSystemSetting(intent)
                            } else {
                                viewModel.toggleFloatingBubble(enabled)
                            }
                        },
                        onToggleSpeakNotifications = { enabled ->
                            viewModel.toggleSpeakNotifications(enabled)
                        },
                        onGrantSystemPermissions = {
                            permissionLauncher.launch(requiredPermissions)
                        },
                        onOpenAccessibility = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            onOpenSystemSetting(intent)
                        },
                        onOpenNotificationSettings = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            onOpenSystemSetting(intent)
                        },
                        onIgnoreBattery = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                onOpenSystemSetting(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Battery Optimization setting not supported on this standard target.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        commandLogs = commandLogs,
                        notificationLogs = notificationLogs,
                        onClearLogs = { viewModel.clearLogs() },
                        onClearNotifications = { viewModel.clearNotifications() }
                    )
                    "health" -> HealthDashboardPanel(
                        viewModel = viewModel
                    )
                    "scroller" -> AutoScrollDashboardPanel(
                        accessibilityEnabled = accessibilityActive,
                        onOpenAccessibility = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open settings, please open manually.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    // Gemini Overlay Layer
    val isSiriActive = assistantState != AssistantState.IDLE
    AnimatedVisibility(
        visible = isSiriActive,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
        ) + fadeIn(),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = spring(dampingRatio = 0.85f)
        ) + fadeOut()
    ) {
        GeminiAssistantOverlay(
            assistantState = assistantState,
            speechInputText = speechInputText,
            rmsDb = rmsDb,
            onDismiss = { viewModel.stopActiveListening() }
        )
    }
}
}

// HOME SPEECH & CONVERSATION PANEL
@Composable
fun HomeAssistantPanel(
    viewModel: SiriViewModel,
    assistantState: AssistantState,
    speechInputText: String,
    rmsDb: Float,
    chatMessages: List<com.example.data.ChatMessage>
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var terminalText by remember { mutableStateOf("") }

    // AutoScroll chats whenever they arrive
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // Chat Console Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.02f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
        ) {
            if (chatMessages.isEmpty()) {
                // Empty State with Terminal Cyber Tips
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "info",
                        tint = NeonCyan.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "TERMINAL READY",
                        color = NeonCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Try speaking: \"Siri mummy ko call lagao\" or \"Siri WhatsApp me Pankaj ko message bhejo kal ghar jana hai\"",
                        color = DarkText.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(chatMessages) { chat ->
                        val isUser = chat.sender == "user"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = if (isUser) 16.dp else 2.dp,
                                            bottomEnd = if (isUser) 2.dp else 16.dp
                                        )
                                    )
                                    .background(
                                        if (isUser) {
                                            Brush.linearGradient(
                                                colors = listOf(Color(0xFF1A1A1A), Color(0xFF252525))
                                            )
                                        } else {
                                            Brush.linearGradient(
                                                colors = listOf(NeonCyan.copy(alpha = 0.1f), NeonPurple.copy(alpha = 0.1f))
                                            )
                                        }
                                    )
                                    .border(
                                        1.dp,
                                        if (isUser) Color.White.copy(alpha = 0.05f) else NeonCyan.copy(alpha = 0.2f),
                                        RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp,
                                            bottomStart = if (isUser) 16.dp else 2.dp,
                                            bottomEnd = if (isUser) 2.dp else 16.dp
                                        )
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                    .widthIn(max = 260.dp)
                                    .testTag(if (isUser) "user_chat_bubble" else "siri_chat_bubble")
                            ) {
                                Text(
                                    text = chat.text,
                                    color = if (isUser) Color(0xFFE2E8F0) else NeonCyan,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Speech Script Indicator
        AnimatedVisibility(
            visible = speechInputText.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NeonCyan.copy(alpha = 0.08f))
                    .border(1.dp, NeonCyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(NeonCyan)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = speechInputText,
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // CENTRAL NEON WAVE AND CONTROLLER CORE
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            contentAlignment = Alignment.Center
        ) {
            SiriVoiceWaveform(
                rmsDb = rmsDb,
                isListening = assistantState == AssistantState.LISTENING,
                isExecuting = assistantState == AssistantState.THINKING || assistantState == AssistantState.EXECUTING,
                modifier = Modifier.fillMaxSize()
            )

            // Dynamic Ambient Backlight Glow
            val isSiriActive = assistantState != AssistantState.IDLE
            Box(
                modifier = Modifier
                    .size(if (isSiriActive) 140.dp else 110.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                (if (isSiriActive) NeonPink else NeonCyan).copy(alpha = 0.2f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // Pulsing Glowing Microphone Button
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = if (isSiriActive) 1.25f else 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Box(
                modifier = Modifier
                    .size(if (isSiriActive) 80.dp else 70.dp)
                    .pointerInput(Unit) {}
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = if (isSiriActive) listOf(NeonPink, NeonPurple) else listOf(NeonCyan, NeonPurple)
                        )
                    )
                    .clickable {
                        if (assistantState != AssistantState.IDLE) {
                            viewModel.stopActiveListening()
                        } else {
                            viewModel.activateFromPowerButton()
                        }
                    }
                    .testTag("microphone_pulsing_button"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isSiriActive) 72.dp else 62.dp)
                        .clip(CircleShape)
                        .background(CyberBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (assistantState == AssistantState.LISTENING) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "microphone",
                        tint = if (isSiriActive) NeonPink else NeonCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Direct Text Keyboard input fallback
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = terminalText,
                onValueChange = { terminalText = it },
                placeholder = {
                    Text(
                        "Command enter karein (Hindi/English)...",
                        color = DarkText.copy(alpha = 0.4f),
                        fontSize = 12.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = NeonCyan.copy(alpha = 0.6f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.White.copy(alpha = 0.04f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f)
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_keyboard_input"),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (terminalText.isNotBlank()) {
                                viewModel.executeTextOrVoiceCommand(terminalText)
                                terminalText = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "send",
                            tint = NeonCyan
                        )
                    }
                }
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Clear Chat Action
            IconButton(
                onClick = { viewModel.clearChat() },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "clear logs",
                    tint = NeonPink
                )
            }
        }
    }
}

// PRE-SUPPORTED COMMANDS LIBRARY PANEL
@Composable
fun CommandsLibraryPanel(
    onCommandSelected: (String) -> Unit
) {
    val categories = listOf(
        CommandGroup(
            "💬 WhatsApp Control",
            listOf(
                "Siri WhatsApp me Pankaj ko message bhejo",
                "Siri Rahul ko WhatsApp message karo kal subah jaldi aana hai",
                "Siri WhatsApp kholo"
            )
        ),
        CommandGroup(
            "📞 Dialing & SMS",
            listOf(
                "Siri mummy ko call lagao",
                "Siri Pankaj ko phone karo",
                "Siri SMS bhejo kal milte hain"
            )
        ),
        CommandGroup(
            "🚀 Opening Applications",
            listOf(
                "Siri YouTube open karo",
                "Siri Chrome open karo",
                "Siri Instagram kholo"
            )
        ),
        CommandGroup(
            "⚙️ System Controls",
            listOf(
                "Siri flashlight on karo",
                "Siri volume 80 percent karo",
                "Siri Bluetooth open karo",
                "Siri WiFi kholo"
            )
        ),
        CommandGroup(
            "⏰ Reminders & Alarms",
            listOf(
                "Siri kal subah 6 baje alarm lagao",
                "Siri 5 baje yaad dilana"
            )
        ),
        CommandGroup(
            "🗺️ Maps & Directions",
            listOf(
                "Siri Google Maps kholo",
                "Siri Patna ka route dikhao"
            )
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "⚡ ASTRO COMMANDS ARCHIVE",
                color = NeonCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap on any multilingual cyberpunk command script to auto-execute it in context.",
                color = DarkText.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        items(categories) { category ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = category.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                for (command in category.commands) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .clickable { onCommandSelected(command) }
                            .padding(12.dp)
                            .testTag("preset_command_item")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ArrowForwardIos,
                                contentDescription = "forward",
                                tint = NeonCyan.copy(alpha = 0.5f),
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = command,
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

// CYBERPUNK AUTOMATION CONTROL PANEL
fun getAutoStartIntent(context: Context): Intent? {
    val intents = listOf(
        // Xiaomi / MIUI
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // Samsung
        Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        // Oppo / ColorOS
        Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        // Realme
        Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        // Vivo / Funtouch
        Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
    )
    
    for (intent in intents) {
        try {
            if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                return intent
            }
        } catch (e: Exception) {}
    }
    
    // Fallback to app detail settings
    return try {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
fun AutomationDashboardPanel(
    backgroundEnabled: Boolean,
    bubbleEnabled: Boolean,
    accessibilityEnabled: Boolean,
    batteryOptimizationIgnored: Boolean,
    notificationAccessGranted: Boolean,
    speakNotificationsEnabled: Boolean,
    onToggleVoiceService: (Boolean) -> Unit,
    onToggleBubble: (Boolean) -> Unit,
    onToggleSpeakNotifications: (Boolean) -> Unit,
    onGrantSystemPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onIgnoreBattery: () -> Unit,
    commandLogs: List<VoiceCommandLog>,
    notificationLogs: List<NotificationLog>,
    onClearLogs: () -> Unit,
    onClearNotifications: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // SYSTEM DIAGNOSTICS & HARD WARNING ALERT CARDS
        if (!batteryOptimizationIgnored || !accessibilityEnabled || !notificationAccessGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NeonPink.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeonPink.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "warning",
                                tint = NeonPink,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "BACKGROUND RESTRICTIONS DETECTED",
                                color = NeonPink,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                        if (!batteryOptimizationIgnored) {
                            Text(
                                "• Android Battery Optimization is ACTIVE. The wake-word microphone will stop working when the screen goes black. Please tap 'WHITELIST' below.",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        if (!accessibilityEnabled) {
                            Text(
                                "• Accessibility Automation is DISABLED. Automated commands (such as Whatsapp, SMS, and Clicks) cannot run.",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        if (!notificationAccessGranted) {
                            Text(
                                "• Notification Listener Service is BLOCKED. Siri cannot monitor, auto-read or speak WhatsApp/SMS aloud in real-time. Tap 'LISTEN' below.",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "⚡ AUTOMATION CORE COCKPIT",
                color = NeonCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Configure low-level android system hooks for always-listening Siri wake-word tracking.",
                color = DarkText.copy(alpha = 0.6f),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }

        // TRIGGER CONTROL AND SERVICE STATUS SWITCHES
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "BACKGROUND SERVICE HOOKS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                // Foreground Always-On Listener
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Background Siri Awake Service",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Keeps wake-word microphone open in the foreground.",
                            color = DarkText.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = backgroundEnabled,
                        onCheckedChange = { onToggleVoiceService(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.35f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF1E1E1E)
                        ),
                        modifier = Modifier.testTag("switch_background_listening")
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.05f))

                // Floating Bubble overlay
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Cyber Bubble Overlay",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Messenger-style draggable overlay trigger.",
                            color = DarkText.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = bubbleEnabled,
                        onCheckedChange = { onToggleBubble(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.35f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF1E1E1E)
                        ),
                        modifier = Modifier.testTag("switch_cyber_bubble")
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.05f))

                // Notifications announcements toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Real-Time Voice Announcements",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Auto-reads WhatsApp, SMS, and System logs aloud.",
                            color = DarkText.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = speakNotificationsEnabled,
                        onCheckedChange = { onToggleSpeakNotifications(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonCyan,
                            checkedTrackColor = NeonCyan.copy(alpha = 0.35f),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0xFF1E1E1E)
                        ),
                        modifier = Modifier.testTag("switch_speak_notifications")
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.05f))

                // Accessibility Status Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Accessibility Automation Engine",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Required for automated typing & physical clicks.",
                            color = DarkText.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (accessibilityEnabled) NeonGreen.copy(alpha = 0.15f) else NeonPink.copy(alpha = 0.15f))
                            .border(
                                1.dp,
                                if (accessibilityEnabled) NeonGreen else NeonPink,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (accessibilityEnabled) "ENABLED" else "DISABLED",
                            color = if (accessibilityEnabled) NeonGreen else NeonPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // QUICK PRIVILEGE PORTAL PANELS
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "PRIVILEGE PROMPT ONBOARDING",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Standard Microphone, Contact & Phone Calls Bundle Launcher Action
                CustomSettingRow(
                    title = "Register Core Permissions",
                    desc = "Requests Microphone, Phone, SMS, and Contacts bundle at once.",
                    buttonText = "GRANT",
                    testTag = "onboard_grant_microphone",
                    onAction = onGrantSystemPermissions
                )

                CustomSettingRow(
                    title = "Connect Accessibility Service",
                    desc = "Activate 'Siri AI Assistant' service in system accessibility directory.",
                    buttonText = "BIND",
                    testTag = "onboard_bind_accessibility",
                    onAction = onOpenAccessibility
                )

                CustomSettingRow(
                    title = "Connect Notification Listener",
                    desc = "Allows monitoring & speaking aloud incoming messages automatically.",
                    buttonText = "LISTEN",
                    testTag = "onboard_bind_notifications",
                    onAction = onOpenNotificationSettings
                )

                CustomSettingRow(
                    title = "Disable Battery Limits",
                    desc = "Prevents OS from killing Siri always-listening service recursively.",
                    buttonText = "WHITELIST",
                    testTag = "onboard_whitelist_battery",
                    onAction = onIgnoreBattery
                )
            }
        }

        // DEVICE SPECIFIC AUTOSTART SETTINGS GUIDE
        item {
            var selectedOem by remember { mutableStateOf("") }
            val oemContext = LocalContext.current
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "⚙️ OEM DEVICE SPECIFIC AUTO-START GUIDE",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Xiaomi, Samsung, Realme, Oppo, and Vivo devices block background processes when swiped from recents. Whitelist this app below:",
                    color = DarkText.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val oems = listOf("Xiaomi", "Samsung", "Oppo", "Vivo")
                    oems.forEach { oem ->
                        val isSelected = selectedOem == oem
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (isSelected) NeonCyan else Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .clickable { selectedOem = if (isSelected) "" else oem }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(oem, color = if (isSelected) NeonCyan else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                if (selectedOem.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.03f))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Steps for $selectedOem devices:",
                                color = NeonPurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val guideText = when (selectedOem) {
                                "Xiaomi" -> "1. Open Security App -> Permissions -> Autostart\n2. Turn ON 'Siri AI Assistant'\n3. In App Info -> Battery saver -> set to 'No restrictions'"
                                "Samsung" -> "1. Go to Settings -> Apps -> Siri AI Assistant\n2. Battery -> Set to 'Unrestricted'\n3. Add to 'Never sleeping apps' in Device Care"
                                "Oppo" -> "1. Press & hold Siri App -> Tap App Info\n2. Battery usage -> Enable 'Allow background activity'\n3. Turn ON 'Allow Auto-launch' toggle"
                                "Vivo" -> "1. Open iManager assistant -> App Manager -> Autostart Manager\n2. Locate 'Siri AI Assistant' and toggle ON Autostart\n3. Switch background high power consumption to ON"
                                else -> "1. Open Settings -> Apps -> Siri AI Assistant\n2. Disable Battery limit restrictions\n3. Enable Autostart toggles"
                            }
                            Text(guideText, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
                
                Button(
                    onClick = {
                        val intent = getAutoStartIntent(oemContext)
                        if (intent != null) {
                            try {
                                oemContext.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(oemContext, "AutoStart panel not found directly. Please configure via your phone Security Manager program.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPurple.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, NeonPurple.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                ) {
                    Text("LAUNCH DEVICE AUTO-START SETTINGS", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // RECENT PARSED LOGS TERMINAL
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📜 REALTIME EXECUTION LOGGER",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(
                        onClick = onClearLogs,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "clear logs",
                            tint = NeonPink,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (commandLogs.isEmpty()) {
                    Text(
                        "No command records inside local Room tables yet.",
                        color = DarkText.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        commandLogs.take(15).forEach { log ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.03f))
                                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "INPUT: \"${log.commandText}\"",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = log.detectedIntent,
                                            color = if (log.successfullyExecuted) NeonGreen else NeonPink,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "OUT: ${log.executionResult}",
                                        color = DarkText.copy(alpha = 0.6f),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // RECENT NOTIFICATION ANNOUNCEMENTS TERMINAL
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📢 MESSAGES & NOTIFICATION LOGS",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(
                        onClick = onClearNotifications,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "clear notifications",
                            tint = NeonPink,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (notificationLogs.isEmpty()) {
                    Text(
                        "No WhatsApp, SMS or System notifications received yet.",
                        color = DarkText.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        notificationLogs.take(15).forEach { log ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.03f))
                                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${log.appName.uppercase()} - ${log.senderName}",
                                            color = NeonCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "SPOKEN",
                                            color = NeonGreen,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = log.messageText,
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 11.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp)),
                                        color = DarkText.copy(alpha = 0.4f),
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// SHARED COMPOSE ELEMENT: NAVIGATION TAB CHIP
@Composable
fun CustomTabChip(
    label: String,
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (active) NeonCyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp,
                if (active) NeonCyan.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(24.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) NeonCyan else DarkText.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = if (active) Color.White else DarkText.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )
        }
    }
}

// SHARED COMPOSE ELEMENT: CUSTOM SETTING ROW
@Composable
fun CustomSettingRow(
    title: String,
    desc: String,
    buttonText: String,
    testTag: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = desc,
                color = DarkText.copy(alpha = 0.5f),
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = CyberSurfaceVariant),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            modifier = Modifier
                .height(34.dp)
                .testTag(testTag)
        ) {
            Text(
                text = buttonText,
                color = NeonCyan,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

// SINE VECTOR WAVE COMPOSABLE DESIGN
@Composable
fun SiriVoiceWaveform(
    rmsDb: Float,
    isListening: Boolean,
    isExecuting: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val targetAmplitude = if (isListening) {
        (rmsDb.coerceAtLeast(1f) * 1.6f).coerceAtMost(36f)
    } else if (isExecuting) {
        12f
    } else {
        3f
    }
    
    val amplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "amplitude"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val points = 80
        val pathCurrent = Path()

        val colors = listOf(NeonCyan, NeonPurple, NeonPink)
        
        for (waveIndex in 0..2) {
            val scaleX = 2f * Math.PI.toFloat() / width
            val alpha = when(waveIndex) {
                0 -> 0.7f
                1 -> 0.4f
                else -> 0.2f
            }
            val frequencyMultiplier = 1f + waveIndex * 0.3f
            val phaseOffset = waveIndex * 1.0f

            pathCurrent.reset()

            for (i in 0..points) {
                val x = i * (width / points)
                val resolvedPhase = phase * frequencyMultiplier + phaseOffset
                val sine = kotlin.math.sin(x * scaleX * 1.4f + resolvedPhase)
                val boundaryFade = kotlin.math.sin((i / points.toFloat()) * Math.PI.toFloat())
                val y = centerY + sine * amplitude * boundaryFade

                if (i == 0) {
                    pathCurrent.moveTo(x, y)
                } else {
                    pathCurrent.lineTo(x, y)
                }
            }

            drawPath(
                path = pathCurrent,
                color = colors[waveIndex].copy(alpha = alpha),
                style = Stroke(width = (4.5f - waveIndex * 1.2f).dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

data class CommandGroup(
    val name: String,
    val commands: List<String>
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoScrollDashboardPanel(
    accessibilityEnabled: Boolean,
    onOpenAccessibility: () -> Unit
) {
    val isActive by com.example.service.SiriAutoScrollController.isActive.collectAsState()
    val scrollState by com.example.service.SiriAutoScrollController.scrollState.collectAsState()
    val timerDuration by com.example.service.SiriAutoScrollController.timerDuration.collectAsState()
    val secondsRemaining by com.example.service.SiriAutoScrollController.secondsRemaining.collectAsState()

    var customTimeInput by remember { mutableStateOf("") }
    val presetTimes = listOf(5, 10, 15, 20, 25, 30, 45, 60)
    val chunkedPresets = presetTimes.chunked(4)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Accessibility Warning Card if offline
        if (!accessibilityEnabled) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(NeonPink.copy(alpha = 0.1f))
                        .border(1.5.dp, NeonPink, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "ACCESSIBILITY PERMISSION REQUIRED",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Smart Auto Scroller needs Accessibility permissions to execute smooth swipe gestures on Instagram and YouTube Shorts.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onOpenAccessibility,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                        modifier = Modifier.testTag("scroller_permission_btn")
                    ) {
                        Text("Grant Permission", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Main Timer Ring Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF0F0E2A),
                                Color(0xFF1B154D)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "AUTO SCROLLER CONTROLLER",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    // Large Glowing Progress Countdown Indicator
                    val ringColor = if (scrollState == com.example.service.AutoScrollState.SCROLLING) NeonCyan else NeonPink
                    val progress = if (timerDuration > 0) secondsRemaining.toFloat() / timerDuration.toFloat() else 1f

                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .drawBehind {
                                // Draw glow circle behind
                                drawCircle(
                                    color = ringColor.copy(alpha = 0.06f),
                                    radius = size.width / 2f + 10.dp.toPx()
                                )
                                // Draw track
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.05f),
                                    style = Stroke(width = 6.dp.toPx())
                                )
                                // Draw progress arc
                                drawArc(
                                    color = ringColor,
                                    startAngle = -90f,
                                    sweepAngle = 360f * progress,
                                    useCenter = false,
                                    style = Stroke(width = 6.dp.toPx())
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (scrollState == com.example.service.AutoScrollState.STOPPED) "OFF" else "${secondsRemaining}s",
                                color = Color.White,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Next Reel",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Status pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ringColor.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = scrollState.name,
                            color = ringColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Buttons Block (Enable, Pause, Resume, Stop)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        if (scrollState == com.example.service.AutoScrollState.STOPPED) {
                            Button(
                                onClick = { com.example.service.SiriAutoScrollController.start() },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scroller_start_button")
                            ) {
                                Text("Enable Scroller", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            if (scrollState == com.example.service.AutoScrollState.SCROLLING) {
                                Button(
                                    onClick = { com.example.service.SiriAutoScrollController.pause() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("scroller_pause_button")
                                ) {
                                    Text("Pause", color = Color.White)
                                }
                            } else {
                                Button(
                                    onClick = { com.example.service.SiriAutoScrollController.resume() },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("scroller_resume_button")
                                ) {
                                    Text("Resume", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }

                            Button(
                                onClick = { com.example.service.SiriAutoScrollController.stop() },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonPink),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .testTag("scroller_stop_button")
                            ) {
                                Text("Stop", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Preset Duration Selectors
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SELECT AUTO SWIPE INTERVAL",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                // Grid of preset timings (4 column design)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chunkedPresets.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { seconds ->
                                val isSelected = timerDuration == seconds && scrollState != com.example.service.AutoScrollState.STOPPED
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isSelected) NeonCyan.copy(alpha = 0.15f)
                                            else Color.White.copy(alpha = 0.04f)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) NeonCyan else Color.White.copy(alpha = 0.08f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            com.example.service.SiriAutoScrollController.setTimerDuration(seconds)
                                            if (scrollState != com.example.service.AutoScrollState.STOPPED) {
                                                com.example.service.SiriAutoScrollController.start(seconds)
                                            }
                                        }
                                        .padding(vertical = 10.dp)
                                        .testTag("scroller_preset_${seconds}s"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${seconds}s",
                                        color = if (isSelected) NeonCyan else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Custom Timer Input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customTimeInput,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() }) {
                                customTimeInput = input
                            }
                        },
                        label = { Text("Custom Seconds", color = Color.White.copy(alpha = 0.6f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            cursorColor = NeonCyan
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scroller_custom_input")
                    )

                    Button(
                        onClick = {
                            val parsedSecs = customTimeInput.toIntOrNull()
                            if (parsedSecs != null && parsedSecs > 0) {
                                com.example.service.SiriAutoScrollController.setTimerDuration(parsedSecs)
                                if (scrollState != com.example.service.AutoScrollState.STOPPED) {
                                    com.example.service.SiriAutoScrollController.start(parsedSecs)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .height(56.dp)
                            .testTag("scroller_custom_set_btn")
                    ) {
                        Text("Set", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Info / Guide Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SUPPORTED APPLICATIONS",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                
                listOf(
                    "Instagram Feed & Reels",
                    "YouTube Shorts (Voice Control)",
                    "Facebook Feed & Reels"
                ).forEach { appName ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(NeonCyan)
                        )
                        Text(
                            text = appName,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Privacy Guard: Scroller ceases operations automatically when target application is closed or screen is locked.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun HealthDashboardPanel(
    viewModel: SiriViewModel
) {
    val weeklyStats by viewModel.weeklyHealthStats.collectAsState()
    val points by viewModel.recentLocationPoints.collectAsState()

    val todayStats = weeklyStats.lastOrNull()
    val steps = todayStats?.steps ?: 0
    val distanceKm = todayStats?.distanceKm ?: 0.0
    val minutes = todayStats?.walkingTimeMinutes ?: 0
    val calories = todayStats?.caloriesBurned ?: 0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Daily Activity Ring / Status Header Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                NeonCyan.copy(alpha = 0.05f),
                                NeonPurple.copy(alpha = 0.02f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            colors = listOf(NeonCyan.copy(alpha = 0.2f), Color.Transparent)
                        ),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Modern Circle Progress Canvas
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawArc(
                                color = Color.White.copy(alpha = 0.05f),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                            )
                            val sweep = (steps.toFloat() / 10000f * 360f).coerceIn(0f, 360f)
                            drawArc(
                                brush = Brush.sweepGradient(listOf(NeonCyan, NeonPurple)),
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = steps.toString(),
                                color = NeonCyan,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "/ 10,000",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TODAY'S ACTIVITY",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Keep moving! Siri background tracking is automatically measuring your daily walking patterns.",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Metrics Grid (2x2)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricMiniCard(
                        title = "Distance",
                        value = "${String.format(Locale.US, "%.2f", distanceKm)} KM",
                        icon = Icons.Default.DirectionsWalk,
                        color = NeonCyan,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniCard(
                        title = "Active Time",
                        value = "$minutes Min",
                        icon = Icons.Default.Schedule,
                        color = NeonPurple,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricMiniCard(
                        title = "Calories Burned",
                        value = "$calories kcal",
                        icon = Icons.Default.Whatshot,
                        color = NeonPink,
                        modifier = Modifier.weight(1f)
                    )
                    MetricMiniCard(
                        title = "GPS Trace Points",
                        value = "${points.size} recorded",
                        icon = Icons.Default.GpsFixed,
                        color = NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Canvas Weekly Fitness Progress Line Chart
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.02f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "WEEKLY STEPS PROGRESS",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        val widthSum = size.width
                        val heightSum = size.height
                        
                        val visualStats = if (weeklyStats.size < 2) {
                            listOf(
                                DailyHealthStats("Mon", 2300, 1.7, 10, 92),
                                DailyHealthStats("Tue", 4500, 3.4, 25, 180),
                                DailyHealthStats("Wed", 3100, 2.3, 15, 124),
                                DailyHealthStats("Thu", 6700, 5.0, 40, 268),
                                DailyHealthStats("Fri", steps, distanceKm, minutes, calories)
                            )
                        } else {
                            weeklyStats
                        }

                        val maxSteps = (visualStats.maxOfOrNull { it.steps } ?: 1000).coerceAtLeast(3000)
                        val pointsCount = visualStats.size
                        
                        val pointsList = mutableListOf<Offset>()
                        visualStats.forEachIndexed { index, stat ->
                            val x = index * (widthSum / (pointsCount - 1).coerceAtLeast(1))
                            val y = heightSum - (stat.steps.toFloat() / maxSteps.toFloat() * heightSum)
                            pointsList.add(Offset(x, y))
                        }

                        drawLine(
                            color = Color.White.copy(alpha = 0.04f),
                            start = Offset(0f, heightSum / 2),
                            end = Offset(widthSum, heightSum / 2),
                            strokeWidth = 1f
                        )

                        val fillPath = Path().apply {
                            moveTo(0f, heightSum)
                            pointsList.forEach { offset ->
                                lineTo(offset.x, offset.y)
                            }
                            lineTo(widthSum, heightSum)
                            close()
                        }
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(NeonCyan.copy(alpha = 0.2f), Color.Transparent)
                            )
                        )

                        for (i in 0 until pointsList.size - 1) {
                            drawLine(
                                color = NeonPurple,
                                start = pointsList[i],
                                end = pointsList[i + 1],
                                strokeWidth = 3.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }

                        if (pointsList.isNotEmpty()) {
                            drawCircle(
                                color = NeonCyan,
                                radius = 5.dp.toPx(),
                                center = pointsList.last()
                            )
                        }
                    }
                }
            }
        }
        
        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun MetricMiniCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.02f))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
    }
}

// GEMINI-STYLE ASSISTANT FULL-SCREEN OVERLAY

@Composable
fun GeminiAssistantOverlay(
    assistantState: AssistantState,
    speechInputText: String,
    rmsDb: Float,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFA090C15)) // Deep glassmorphic translucent cyber overlay
            .pointerInput(Unit) {} // Prevent taps passing through to workspace
    ) {
        // Dynamic Radial Gradient glow based on state
        val colorAccent = when (assistantState) {
            AssistantState.LISTENING -> Color(0xFF00F2FE) // Neon Cyan
            AssistantState.THINKING -> Color(0xFF9E00FF)  // Purple
            AssistantState.SPEAKING -> Color(0xFFFF2E93)  // Pink
            AssistantState.EXECUTING -> Color(0xFF00FF87) // Safe Neon Green
            else -> Color(0xFF00F2FE)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colorAccent.copy(alpha = 0.22f),
                            Color.Transparent
                        ),
                        radius = 280.dp.value
                    )
                )
        )

        // Close Button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 24.dp)
                .testTag("gemini_overlay_close")
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            // State Title Text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val stateText = when (assistantState) {
                    AssistantState.LISTENING -> "Sun raha hoon..."
                    AssistantState.THINKING -> "Samajh raha hoon..."
                    AssistantState.SPEAKING -> "Bol raha hoon..."
                    AssistantState.EXECUTING -> "Execute kar raha hoon..."
                    else -> "Hi Sir, how can I help?"
                }

                Text(
                    text = stateText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.5).sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "SIRI AI COGNITION ENGINE",
                    color = colorAccent.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            // Central Pulsing Glowing AI Orb
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                GeminiAiOrb(state = assistantState, rmsDb = rmsDb)
            }

            // Real-Time Speech Text Bar / Waveform section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (assistantState == AssistantState.LISTENING || assistantState == AssistantState.SPEAKING) {
                    SiriVoiceWaveform(
                        rmsDb = rmsDb,
                        isListening = assistantState == AssistantState.LISTENING,
                        isExecuting = assistantState == AssistantState.THINKING || assistantState == AssistantState.EXECUTING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .padding(bottom = 20.dp)
                    )
                }

                val speechDisplay = if (speechInputText.isBlank() || speechInputText == "Listening...") {
                    "Bolna shuru kijiye..."
                } else {
                    speechInputText
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.04f)
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colorAccent)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = speechDisplay,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (speechInputText.isBlank() || speechInputText == "Listening...") Color.White.copy(alpha = 0.45f) else Color.White,
                            lineHeight = 22.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GeminiAiOrb(
    state: AssistantState,
    rmsDb: Float
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    // Outer spin rotation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Breathing pulse scale
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val colorAccent = when (state) {
        AssistantState.LISTENING -> Color(0xFF00F2FE) // Neon Cyan
        AssistantState.THINKING -> Color(0xFF9E00FF)  // Purple
        AssistantState.SPEAKING -> Color(0xFFFF2E93)  // Pink
        AssistantState.EXECUTING -> Color(0xFF00FF87) // Safe Neon Green
        else -> Color(0xFF00F2FE)
    }

    val dynamicSize = when (state) {
        AssistantState.LISTENING -> 140.dp + (rmsDb.coerceAtLeast(0f) * 4).dp
        AssistantState.SPEAKING -> 135.dp + (rmsDb.coerceAtLeast(0f) * 2).dp
        AssistantState.THINKING -> 150.dp
        else -> 125.dp
    }

    val orbSize by animateDpAsState(
        targetValue = dynamicSize,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "orbSize"
    )

    Box(
        modifier = Modifier
            .size(orbSize)
            .scale(pulseScale)
            .drawBehind {
                val brush = Brush.radialGradient(
                    colors = listOf(
                        colorAccent.copy(alpha = 0.38f),
                        colorAccent.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    radius = size.minDimension / 1.3f
                )
                drawCircle(brush = brush, radius = size.minDimension / 2f)
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer aesthetic rotating orbital rings
        Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val strokeW = 2.5f.dp.toPx()
            val baseRect = androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)
            
            // Draw dual offset arcs to form a futuristic sliced orbital ring
            val orbitalPath = Path().apply {
                addArc(
                    oval = baseRect,
                    startAngleDegrees = rotation,
                    sweepAngleDegrees = 110f
                )
                addArc(
                    oval = baseRect,
                    startAngleDegrees = rotation + 180f,
                    sweepAngleDegrees = 110f
                )
            }
            
            drawPath(
                path = orbitalPath,
                color = colorAccent.copy(alpha = 0.8f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            // Dynamic volume signal micro dots on orbit
            if (state == AssistantState.LISTENING) {
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(
                        x = (size.width / 2) + (size.width / 2) * kotlin.math.cos(Math.toRadians(rotation.toDouble())).toFloat(),
                        y = (size.height / 2) + (size.height / 2) * kotlin.math.sin(Math.toRadians(rotation.toDouble())).toFloat()
                    )
                )
            }
        }

        // Inner solid core orb with gradient
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(colorAccent, colorAccent.copy(alpha = 0.65f))
                    ),
                    shape = CircleShape
                )
                .border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (state) {
                    AssistantState.LISTENING -> Icons.Default.Mic
                    AssistantState.THINKING -> Icons.Default.Refresh // Standard pulsing loader
                    AssistantState.SPEAKING -> Icons.Default.VolumeUp
                    AssistantState.EXECUTING -> Icons.Default.PlayArrow
                    else -> Icons.Default.Info
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
