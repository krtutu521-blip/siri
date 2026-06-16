package com.example.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.SiriApplication
import com.example.data.*
import com.example.service.SiriForegroundService
import com.example.service.SiriFloatingBubbleService
import com.example.service.SiriAccessibilityService
import com.example.service.SiriNotificationListenerService
import android.content.ComponentName
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.SimpleDateFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.*

enum class AssistantState {
    IDLE, LISTENING, THINKING, EXECUTING, SPEAKING
}

class SiriViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val context = application.applicationContext
    private val siriDao = (application as SiriApplication).database.siriDao

    // Exposed flows for interactive UI
    val chatMessages: StateFlow<List<ChatMessage>> = siriDao.getAllChatMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val commandLogs: StateFlow<List<VoiceCommandLog>> = siriDao.getAllCommandLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notificationLogs: StateFlow<List<NotificationLog>> = siriDao.getAllNotificationLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weeklyHealthStats: StateFlow<List<DailyHealthStats>> = siriDao.getAllWeeklyHealthStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentLocationPoints: StateFlow<List<LocationPoint>> = siriDao.getRecentLocationPoints()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _speechInputText = MutableStateFlow("")
    val speechInputText: StateFlow<String> = _speechInputText.asStateFlow()

    private val _rmsDb = MutableStateFlow(0f)
    val rmsDb: StateFlow<Float> = _rmsDb.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // System configurations & service state trackers
    private val _isBackgroundServiceRunning = MutableStateFlow(false)
    val isBackgroundServiceRunning: StateFlow<Boolean> = _isBackgroundServiceRunning.asStateFlow()

    private val _isBubbleOverlayActive = MutableStateFlow(false)
    val isBubbleOverlayActive: StateFlow<Boolean> = _isBubbleOverlayActive.asStateFlow()

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isBatteryOptimizationIgnored = MutableStateFlow(true)
    val isBatteryOptimizationIgnored: StateFlow<Boolean> = _isBatteryOptimizationIgnored.asStateFlow()

    private val _isNotificationAccessGranted = MutableStateFlow(false)
    val isNotificationAccessGranted: StateFlow<Boolean> = _isNotificationAccessGranted.asStateFlow()

    private val _speakNotificationsEnabled = MutableStateFlow(true)
    val speakNotificationsEnabled: StateFlow<Boolean> = _speakNotificationsEnabled.asStateFlow()

    // Engines
    private var textToSpeech: TextToSpeech? = null
    private var localSpeechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    // Broadcast receiver for background inputs
    private val siriReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SiriForegroundService.ACTION_WAKE_WORD_DETECTED -> {
                    val initialCommand = intent.getStringExtra(SiriForegroundService.EXTRA_INITIAL_COMMAND) ?: ""
                    Log.d("SiriViewModel", "Wake Word detected broadcast with command: $initialCommand")
                    val lowerCmd = initialCommand.lowercase().trim()
                    if (lowerCmd.endsWith("siri") || lowerCmd.endsWith("hey siri") || lowerCmd.endsWith("hello siri")) {
                        startActiveListening()
                    } else {
                        executeTextOrVoiceCommand(initialCommand)
                    }
                }
                SiriForegroundService.ACTION_RMS_CHANGED -> {
                    val rms = intent.getFloatExtra(SiriForegroundService.EXTRA_RMS_VALUE, 0f)
                    _rmsDb.value = rms
                }
                SiriNotificationListenerService.ACTION_ANNOUNCE -> {
                    val msg = intent?.getStringExtra(SiriNotificationListenerService.EXTRA_MESSAGE) ?: ""
                    val pkg = intent?.getStringExtra(SiriNotificationListenerService.EXTRA_PACKAGE) ?: ""
                    val sender = intent?.getStringExtra(SiriNotificationListenerService.EXTRA_SENDER) ?: ""
                    if (msg.isNotEmpty() && _speakNotificationsEnabled.value) {
                        Log.d("SiriViewModel", "TTS received notification announce: $msg, pkg: $pkg, sender: $sender")
                        NotificationReplyState.lastPackageName = pkg
                        NotificationReplyState.lastSender = sender
                        NotificationReplyState.isWaitingForReplyOption = true
                        NotificationReplyState.isWaitingForMessageBody = false
                        speak(msg, "SiriNotificationAnnounceConfirm")
                    }
                }
            }
        }
    }

    init {
        // Init TTS
        textToSpeech = TextToSpeech(context, this)

        // Read preferences for speaking notifications
        val prefs = context.getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
        _speakNotificationsEnabled.value = prefs.getBoolean("speak_notifications_enabled", true)

        // Check if various background components are running
        checkBackgroundStates()

        // Sync assistant state changes to floating bubble
        viewModelScope.launch {
            _assistantState.collect { state ->
                val stateIntent = Intent("com.example.service.action.STATE_CHANGED").apply {
                    putExtra("extra_state_name", state.name)
                }
                context.sendBroadcast(stateIntent)
            }
        }

        // Register receiver for background speech service callbacks
        val filter = IntentFilter().apply {
            addAction(SiriForegroundService.ACTION_WAKE_WORD_DETECTED)
            addAction(SiriForegroundService.ACTION_RMS_CHANGED)
            addAction(SiriNotificationListenerService.ACTION_ANNOUNCE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(siriReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(siriReceiver, filter)
        }

        // Periodically refresh state checks
        handler.postDelayed(object : Runnable {
            override fun run() {
                checkBackgroundStates()
                handler.postDelayed(this, 3000)
            }
        }, 3000)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.setLanguage(Locale.US)
            }
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _assistantState.value = AssistantState.SPEAKING
                }
                override fun onDone(utteranceId: String?) {
                    _assistantState.value = AssistantState.IDLE
                    if (utteranceId == "SiriNotificationAnnounceConfirm" || utteranceId == "SiriAskForReplyBody" || utteranceId == "SiriPowerButtonActivation") {
                        handler.post {
                            startActiveListening()
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _assistantState.value = AssistantState.IDLE
                }
            })
        }
    }

    fun checkBackgroundStates() {
        _isBackgroundServiceRunning.value = isServiceRunning(SiriForegroundService::class.java)
        _isBubbleOverlayActive.value = isServiceRunning(SiriFloatingBubbleService::class.java)
        _isAccessibilityEnabled.value = SiriAccessibilityService.isRunning
        _isNotificationAccessGranted.value = isNotificationServiceEnabled(context)
        
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            _isBatteryOptimizationIgnored.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        } catch (e: Exception) {
            _isBatteryOptimizationIgnored.value = true
        }
    }

    private fun isNotificationServiceEnabled(context: Context): Boolean {
        val cn = ComponentName(context, SiriNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    fun toggleSpeakNotifications(enabled: Boolean) {
        val prefs = context.getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("speak_notifications_enabled", enabled).apply()
        _speakNotificationsEnabled.value = enabled
    }

    fun clearNotifications() {
        viewModelScope.launch {
            siriDao.clearNotificationLogs()
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    // Toggle background service
    fun toggleBackgroundService(enabled: Boolean) {
        val prefs = context.getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("foreground_service_enabled", enabled).apply()

        val intent = Intent(context, SiriForegroundService::class.java)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
        handler.postDelayed({ checkBackgroundStates() }, 500)
    }

    // Toggle floating bubble
    fun toggleFloatingBubble(enabled: Boolean) {
        if (enabled && !Settings.canDrawOverlays(context)) {
            _errorMessage.value = "Need Overlay Permission (Display over other apps)!"
            return
        }

        val prefs = context.getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("bubble_service_enabled", enabled).apply()

        val intent = Intent(context, SiriFloatingBubbleService::class.java)
        if (enabled) {
            context.startService(intent)
        } else {
            context.stopService(intent)
        }
        handler.postDelayed({ checkBackgroundStates() }, 500)
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    // Speech synthesis helper
    fun speak(text: String, utteranceId: String = "SiriSpeakUtterance") {
        textToSpeech?.stop()
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun activateFromPowerButton() {
        Log.d("SiriViewModel", "activateFromPowerButton trigger started")
        _assistantState.value = AssistantState.SPEAKING
        _speechInputText.value = "Preparing..."
        speak("Ha Sir, boliye kya karna hai?", "SiriPowerButtonActivation")
    }

    // Active listening trigger
    fun startActiveListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _errorMessage.value = "Speech Recognition not available on this device."
            return
        }

        handler.post {
            localSpeechRecognizer?.destroy()
            localSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _assistantState.value = AssistantState.LISTENING
                        _speechInputText.value = "Listening..."
                        _rmsDb.value = 0f
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        _rmsDb.value = rmsdB
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        Log.e("SiriViewModel", "Active listening error code: $error")
                        _assistantState.value = AssistantState.IDLE
                        _speechInputText.value = ""
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotEmpty()) {
                            _speechInputText.value = text
                            executeTextOrVoiceCommand(text)
                        } else {
                            _assistantState.value = AssistantState.IDLE
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let {
                            _speechInputText.value = it
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            localSpeechRecognizer?.startListening(intent)
        }
    }

    fun stopActiveListening() {
        localSpeechRecognizer?.stopListening()
        _assistantState.value = AssistantState.IDLE
    }

    // Main Brain Execution
    fun executeTextOrVoiceCommand(command: String) {
        if (command.isBlank()) return

        viewModelScope.launch {
            _assistantState.value = AssistantState.THINKING
            _speechInputText.value = command
            
            // 1. Add user message to local chat history
            siriDao.insertChatMessage(ChatMessage(sender = "user", text = command))

            // INTERCEPT Conversational Notification Reply Loop
            if (NotificationReplyState.isWaitingForReplyOption) {
                val trimCmd = command.lowercase(Locale.getDefault()).trim()
                if (trimCmd == "haan" || trimCmd == "ha" || trimCmd == "yes" || trimCmd == "yeah" || trimCmd == "han" || trimCmd.contains("haan") || trimCmd.contains("reply")) {
                    NotificationReplyState.isWaitingForReplyOption = false
                    NotificationReplyState.isWaitingForMessageBody = true
                    val replyPrompt = "Kya reply bhejna hai?"
                    siriDao.insertChatMessage(ChatMessage(sender = "siri", text = replyPrompt))
                    speak(replyPrompt, "SiriAskForReplyBody")
                    return@launch
                } else {
                    NotificationReplyState.clear()
                }
            } else if (NotificationReplyState.isWaitingForMessageBody) {
                val replyBody = command
                val pkg = NotificationReplyState.lastPackageName ?: "com.whatsapp"
                val sender = NotificationReplyState.lastSender ?: "Pankaj"
                NotificationReplyState.isWaitingForMessageBody = false
                NotificationReplyState.isWaitingForReplyOption = false
                val startMsg = "Thik hai sir, message bhej raha hoon."
                siriDao.insertChatMessage(ChatMessage(sender = "siri", text = startMsg))
                speak(startMsg)
                if (pkg.contains("whatsapp") || pkg.contains("w4b")) {
                    executeWhatsAppAutomation(sender, replyBody, "Reply WhatsApp notification")
                } else if (pkg.contains("telegram")) {
                    executeAppMessageAutomation("org.telegram.messenger", sender, replyBody, "Reply Telegram notification")
                } else if (pkg.contains("instagram")) {
                    executeAppMessageAutomation("com.instagram.android", sender, replyBody, "Reply Instagram notification")
                } else if (pkg.contains("messenger")) {
                    executeAppMessageAutomation("com.facebook.orca", sender, replyBody, "Reply Messenger notification")
                } else {
                    executeSmsAutomation(sender, replyBody, "Reply SMS notification")
                }
                return@launch
            }

            // 2. Intercept using direct on-device execution (sub-500ms rapid trigger)
            if (checkAndExecuteLocalCommand(command)) {
                Log.d("SiriViewModel", "Command executed directly on device locally: $command")
                return@launch
            }

            // 3. Fetch API Key and prepare prompt
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                simulateOfflineExecution(command)
                return@launch
            }

            try {
                val recentMessages = chatMessages.value.takeLast(10)
                val chatContextPrompt = recentMessages.joinToString("\n") { "${it.sender}: ${it.text}" }

                val finalSystemInstruction = """
You are Siri AI Assistant, a futuristic cyberpunk voice assistant for Android phones.
You can understand Hindi, Bhojpuri, Hinglish, and English.
Analyze the user's voice command in context and output VALID JSON ONLY.

The JSON schema must be exactly:
{
  "intent": "WHATSAPP" | "CALL" | "SMS" | "APP" | "SYSTEM" | "MAPS" | "MEDIA" | "ALARM" | "REMINDER" | "CHAT",
  "params": {
    "contact": "Contact name or phone number",
    "message": "The message body/text to write",
    "appName": "YouTube, Chrome, Telegram, Instagram, etc.",
    "setting": "wifi, bluetooth, flashlight, brightness, volume, silent, airplane",
    "value": "on, off, or a number percentage like 50 if relevant",
    "time": "alarm/reminder time, e.g., '06:00' or '17:00'",
    "route": "destination like 'Patna' or 'Delhi'"
  },
  "spokenResponse": "A highly futuristic, helpful vocal response in the SAME language style (Hinglish/Bhojpuri/Hindi/English) as the user asked."
}

Recent Chat Context:
$chatContextPrompt

Analyze command: "$command"
                """.trimIndent()

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = "Please parse this command: $command")))),
                    systemInstruction = Content(parts = listOf(Part(text = finalSystemInstruction))),
                    generationConfig = GenerationConfig(temperature = 0.3, responseMimeType = "application/json")
                )

                val response = withContext(Dispatchers.IO) {
                    GeminiClient.service.generateContent(apiKey, request)
                }

                val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (rawText != null) {
                    parseAndExecuteJson(rawText, command)
                } else {
                    handleExecutionFailure(command, "Empty response from Gemini Core")
                }

            } catch (e: Exception) {
                Log.e("SiriViewModel", "Failed to contact Gemini framework API", e)
                fallbackParserOffline(command)
            }
        }
    }

    private suspend fun parseAndExecuteJson(jsonOutput: String, rawCommand: String) {
        try {
            var cleanedJson = jsonOutput.trim()
            if (cleanedJson.startsWith("```")) {
                cleanedJson = cleanedJson.replace("```json", "").replace("```", "").trim()
            }

            val adapter = moshi.adapter(ParsedCommand::class.java)
            val parsed = adapter.fromJson(cleanedJson)

            if (parsed != null) {
                _assistantState.value = AssistantState.EXECUTING
                executeCommandIntent(parsed, rawCommand)
            } else {
                handleExecutionFailure(rawCommand, "JSON Schema Mismatch")
            }
        } catch (e: Exception) {
            Log.e("SiriViewModel", "JSON Parsing failed", e)
            fallbackParserOffline(rawCommand)
        }
    }

    private suspend fun executeCommandIntent(parsed: ParsedCommand, rawCommand: String) {
        val intentType = parsed.intent
        val params = parsed.params ?: ParsedParams()
        val speechResponse = parsed.spokenResponse ?: "Okay, working on it."

        var executionMessage = "Executed successfully"
        var isSuccess = true

        try {
            when (intentType) {
                "WHATSAPP" -> {
                    val contact = params.contact ?: "someone"
                    val msg = params.message ?: "hello"
                    executeWhatsAppAutomation(contact, msg, rawCommand)
                    return // Handled inside sub-routine
                }
                "CALL" -> {
                    val contact = params.contact ?: "someone"
                    executePhoneCallAutomation(contact, rawCommand)
                    return // Handled inside sub-routine
                }
                "SMS" -> {
                    val contact = params.contact ?: "someone"
                    val msg = params.message ?: "hello"
                    executeSmsAutomation(contact, msg, rawCommand)
                    return // Handled inside sub-routine
                }
                "APP" -> {
                    val appName = (params.appName ?: "").lowercase()
                    val packageName = getPackageNameForAppLabel(context, appName)
                    val launchIntent = if (packageName != null) {
                        context.packageManager.getLaunchIntentForPackage(packageName)
                    } else {
                        null
                    }

                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                        executionMessage = "Opened launcher package: $packageName"
                    } else {
                        val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$appName")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(searchIntent)
                        executionMessage = "Opened fallback Google Search view for $appName"
                    }
                }
                "SYSTEM" -> {
                    val setting = (params.setting ?: "").lowercase()
                    val value = (params.value ?: "").lowercase()

                    when (setting) {
                        "wifi" -> {
                            try {
                                val wifiSettingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(wifiSettingsIntent)
                                executionMessage = "Opened System WiFi Settings Panel"
                            } catch (e: Exception) {
                                executionMessage = "WiFi error: ${e.message}"
                            }
                        }
                        "bluetooth" -> {
                            try {
                                val bluetoothSettingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(bluetoothSettingsIntent)
                                executionMessage = "Opened Bluetooth settings panel"
                            } catch (e: Exception) {
                                executionMessage = "Bluetooth error: ${e.message}"
                            }
                        }
                        "flashlight" -> {
                            try {
                                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                val cameraId = cameraManager.cameraIdList.firstOrNull()
                                if (cameraId != null) {
                                    cameraManager.setTorchMode(cameraId, value == "on")
                                    executionMessage = "Flashlight toggled $value"
                                } else {
                                    executionMessage = "Flashlight hardware unavailable"
                                }
                            } catch (e: Exception) {
                                executionMessage = "Torch accessibility error: ${e.message}"
                            }
                        }
                        "volume" -> {
                            try {
                                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                val percent = value.replace("%", "").trim().toIntOrNull() ?: 50
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val targetVol = (maxVol * (percent / 100f)).toInt()
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                                executionMessage = "Volume level set to $percent%"
                            } catch (e: Exception) {
                                executionMessage = "Audio error: ${e.message}"
                            }
                        }
                        "brightness" -> {
                            try {
                                val percent = value.replace("%", "").trim().toIntOrNull() ?: 50
                                setScreenBrightness(context, percent)
                                executionMessage = "Brightness leveled at $percent%"
                            } catch (e: Exception) {
                                executionMessage = "Brightness error: ${e.message}"
                            }
                        }
                        "silent" -> {
                            try {
                                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                if (value == "on") {
                                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                                    executionMessage = "Silent Mode enabled"
                                } else {
                                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                                    executionMessage = "Silent Mode disabled"
                                }
                            } catch (e: Exception) {
                                executionMessage = "Ringer mode write restriction: ${e.message}"
                            }
                        }
                        else -> {
                            executionMessage = "Unknown system control setting"
                        }
                    }
                }
                "MAPS" -> {
                    val dest = params.route ?: ""
                    val mapUri = if (dest.isNotEmpty()) {
                        Uri.parse("google.navigation:q=${Uri.encode(dest)}")
                    } else {
                        Uri.parse("geo:0,0?q=maps")
                    }
                    val mapIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        setPackage("com.google.android.apps.maps")
                    }
                    context.startActivity(mapIntent)
                    executionMessage = "Navigating toward $dest"
                }
                "MEDIA" -> {
                    val value = (params.value ?: "").lowercase()
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val keyCode = when {
                        value.contains("pause") || value.contains("stop") -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                        value.contains("next") -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        value.contains("prev") -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    }
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                    audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
                    executionMessage = "Media button event triggered: $value"
                }
                "ALARM" -> {
                    val timeString = params.time ?: "06:00"
                    val parts = timeString.split(":")
                    val hr = parts.getOrNull(0)?.toIntOrNull() ?: 6
                    val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hr)
                        putExtra(AlarmClock.EXTRA_MINUTES, min)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "Set by Siri AI")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(alarmIntent)
                    executionMessage = "System alarm created for $hr:$min"
                }
                "REMINDER" -> {
                    executionMessage = "Reminder recorded and scheduled: ${params.message} at ${params.time}"
                }
                "CHAT" -> {
                    executionMessage = "Siri Chat: $speechResponse"
                }
                else -> {
                    executionMessage = "Interactive friendly response completed"
                }
            }
        } catch (e: Exception) {
            Log.e("SiriViewModel", "Failed to execute parsed action", e)
            executionMessage = "Error: ${e.localizedMessage}"
            isSuccess = false
        }

        siriDao.insertCommandLog(
            VoiceCommandLog(
                commandText = rawCommand,
                detectedIntent = intentType,
                executionResult = executionMessage,
                successfullyExecuted = isSuccess
            )
        )

        siriDao.insertChatMessage(ChatMessage(sender = "siri", text = speechResponse))

        _assistantState.value = AssistantState.SPEAKING
        speak(speechResponse)
    }

    private suspend fun handleExecutionFailure(command: String, error: String) {
        siriDao.insertCommandLog(
            VoiceCommandLog(
                commandText = command,
                detectedIntent = "ERROR",
                executionResult = error,
                successfullyExecuted = false
            )
        )
        val helpMessage = "Hinglish me kripya phir se bolein, hum samajh nahi paaye."
        siriDao.insertChatMessage(ChatMessage(sender = "siri", text = error))
        speak(helpMessage)
        _assistantState.value = AssistantState.IDLE
    }

    private suspend fun fallbackParserOffline(commandText: String) {
        val lowerText = commandText.lowercase()
        val parsed: ParsedCommand
        
        when {
            lowerText.contains("whatsapp") -> {
                val hasContact = if (lowerText.contains("ko")) lowerText.substringAfter("siri ").substringBefore("ko").trim() else "Pankaj"
                val messageText = if (lowerText.contains("karo")) lowerText.substringAfter("karo").trim() else "kal ghar jana hai"
                parsed = ParsedCommand(
                    intent = "WHATSAPP",
                    params = ParsedParams(contact = hasContact, message = messageText),
                    spokenResponse = "$hasContact ko WhatsApp par sandesh taiyaar baa, bhej di?"
                )
            }
            lowerText.contains("call") || lowerText.contains("phone") || lowerText.contains("phone karo") -> {
                val hasContact = if (lowerText.contains("ko")) lowerText.substringBefore("ko").replace("siri", "").trim() else "mummy"
                parsed = ParsedCommand(
                    intent = "CALL",
                    params = ParsedParams(contact = hasContact),
                    spokenResponse = "Theek bano, $hasContact ko phone laga rahi hoon."
                )
            }
            lowerText.contains("kholo") || lowerText.contains("open") -> {
                val targetApp = when {
                    lowerText.contains("youtube") -> "YouTube"
                    lowerText.contains("chrome") -> "Chrome"
                    lowerText.contains("instagram") -> "Instagram"
                    lowerText.contains("telegram") -> "Telegram"
                    lowerText.contains("camera") -> "camera"
                    else -> "YouTube"
                }
                parsed = ParsedCommand(
                    intent = "APP",
                    params = ParsedParams(appName = targetApp),
                    spokenResponse = "Acchha, $targetApp khol rahi hoon."
                )
            }
            lowerText.contains("brightness") -> {
                parsed = ParsedCommand(
                    intent = "SYSTEM",
                    params = ParsedParams(setting = "brightness", value = "50"),
                    spokenResponse = "Siri brightness pachas percent kar di hai."
                )
            }
            lowerText.contains("flashlight") || lowerText.contains("torch") -> {
                val isOn = if (lowerText.contains("band") || lowerText.contains("off")) "off" else "on"
                parsed = ParsedCommand(
                    intent = "SYSTEM",
                    params = ParsedParams(setting = "flashlight", value = isOn),
                    spokenResponse = "Flashlight ko $isOn kar diya gaya hai."
                )
            }
            lowerText.contains("alarm") -> {
                parsed = ParsedCommand(
                    intent = "ALARM",
                    params = ParsedParams(time = "06:00"),
                    spokenResponse = "Subah chheh baje ka alarm lag gaya hai."
                )
            }
            else -> {
                parsed = ParsedCommand(
                    intent = "CHAT",
                    params = ParsedParams(),
                    spokenResponse = "Pranam! Hum Siri baani. Hamse Bhojpuri, Hindi ya Hinglish me batiya sakataru. WhatsApp, call aur flashlight sab automatic control karab!"
                )
            }
        }
        executeCommandIntent(parsed, commandText)
    }

    private suspend fun simulateOfflineExecution(commandText: String) {
        fallbackParserOffline(commandText)
    }

    fun clearChat() {
        viewModelScope.launch {
            siriDao.clearChatMessages()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            siriDao.clearCommandLogs()
        }
    }

    // --- ON-DEVICE CYBERPUNK AUTOMATION & DEVICE CONTROLS ---

    private fun getPackageNameForAppLabel(context: Context, appLabel: String): String? {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val normalizedQuery = appLabel.lowercase().replace(" ", "").trim()
        
        for (appInfo in packages) {
            val label = appInfo.loadLabel(pm).toString().lowercase().replace(" ", "").trim()
            if (label == normalizedQuery) {
                return appInfo.packageName
            }
        }
        
        for (appInfo in packages) {
            val label = appInfo.loadLabel(pm).toString().lowercase().replace(" ", "").trim()
            if (label.contains(normalizedQuery) || normalizedQuery.contains(label)) {
                return appInfo.packageName
            }
        }
        
        val queryLower = appLabel.lowercase()
        return when {
            queryLower.contains("youtube") -> "com.google.android.youtube"
            queryLower.contains("chrome") -> "com.android.chrome"
            queryLower.contains("whatsapp") -> "com.whatsapp"
            queryLower.contains("instagram") -> "com.instagram.android"
            queryLower.contains("telegram") -> "org.telegram.messenger"
            queryLower.contains("facebook") -> "com.facebook.katana"
            queryLower.contains("camera") -> "com.android.camera"
            queryLower.contains("maps") -> "com.google.android.apps.maps"
            queryLower.contains("gmail") -> "com.google.android.gm"
            queryLower.contains("drive") -> "com.google.android.apps.docs"
            else -> null
        }
    }

    private fun getContactPhoneNumber(context: Context, contactName: String): String? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$contactName%")
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        return it.getString(numberIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SiriViewModel", "Contacts resolver failed", e)
        }
        return null
    }

    private fun setScreenBrightness(context: Context, percent: Int) {
        val brightness = (percent * 255 / 100).coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            } catch (e: Exception) {
                Log.e("SiriViewModel", "Failed to write system brightness", e)
            }
        } else {
            val intent = Intent("com.example.action.SET_WINDOW_BRIGHTNESS").apply {
                putExtra("brightness_percent", percent)
            }
            context.sendBroadcast(intent)
        }
    }

    private suspend fun recordAndSpeakLocalResult(
        rawCommand: String,
        intentType: String,
        execMsg: String,
        isSuccess: Boolean,
        spokenResponse: String
    ) {
        siriDao.insertCommandLog(
            VoiceCommandLog(
                commandText = rawCommand,
                detectedIntent = intentType,
                executionResult = execMsg,
                successfullyExecuted = isSuccess
            )
        )
        siriDao.insertChatMessage(ChatMessage(sender = "siri", text = spokenResponse))
        _assistantState.value = AssistantState.SPEAKING
        speak(spokenResponse)
    }

    private suspend fun executeWhatsAppAutomation(contact: String, message: String, rawCommand: String) {
        if (SiriAccessibilityService.isRunning) {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage("com.whatsapp")
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                
                val startMsg = "WhatsApp khol rahi hoon, $contact ko message bhej rahi hoon."
                recordAndSpeakLocalResult(rawCommand, "WHATSAPP", "Opened WhatsApp, execution sequence triggered.", true, startMsg)
                
                handler.postDelayed({
                    viewModelScope.launch {
                        var clickedSearch = SiriAccessibilityService.clickNodeByText("Search")
                        if (!clickedSearch) clickedSearch = SiriAccessibilityService.clickNodeByDescription("Search")
                        if (!clickedSearch) clickedSearch = SiriAccessibilityService.clickNodeByDescription("खोजें")
                        if (!clickedSearch) clickedSearch = SiriAccessibilityService.clickNodeByText("खोजें")
                        
                        delay(1200)
                        SiriAccessibilityService.typeText(contact)
                        
                        delay(1500)
                        val clickedContact = SiriAccessibilityService.clickNodeByText(contact)
                        
                        delay(1500)
                        SiriAccessibilityService.typeText(message)
                        
                        delay(1500)
                        var clickedSend = SiriAccessibilityService.clickNodeByDescription("Send")
                        if (!clickedSend) clickedSend = SiriAccessibilityService.clickNodeByDescription("भेजें")
                        if (!clickedSend) clickedSend = SiriAccessibilityService.clickNodeByText("Send")
                        if (!clickedSend) clickedSend = SiriAccessibilityService.clickNodeByText("भेजें")
                        
                        if (clickedSend) {
                            speak("WhatsApp par $contact ko sandesh bhej diya hai!")
                        } else {
                            speak("Sandesh tyar kar diya hai, kripya send button dabayein.")
                        }
                    }
                }, 1800)
            } else {
                val errorMsg = "WhatsApp install nahi hai hardware me."
                recordAndSpeakLocalResult(rawCommand, "WHATSAPP", "WhatsApp not installed, aborted gesture mapping.", false, errorMsg)
            }
        } else {
            try {
                val uri = Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(message)}")
                val whatsappIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(whatsappIntent)
                val responseMsg = "$contact ko sandesh prefill kar ke khol diya hai."
                recordAndSpeakLocalResult(rawCommand, "WHATSAPP", "Prefilled WhatsApp fallback opened.", true, responseMsg)
            } catch (e: Exception) {
                recordAndSpeakLocalResult(rawCommand, "WHATSAPP", "WhatsApp direct launch failed: ${e.message}", false, "WhatsApp kholne me error aayi.")
            }
        }
    }

    private suspend fun executePhoneCallAutomation(contact: String, rawCommand: String) {
        val phoneNumber = getContactPhoneNumber(context, contact)
        val finalNumber = phoneNumber ?: contact
        val callIntent: Intent
        val isDirect: Boolean
        
        if (phoneNumber != null) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$finalNumber"))
                isDirect = true
            } else {
                callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$finalNumber"))
                isDirect = false
            }
        } else {
            if (contact.matches(Regex("\\+?\\d+"))) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$contact"))
                    isDirect = true
                } else {
                    callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contact"))
                    isDirect = false
                }
            } else {
                callIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                isDirect = false
            }
        }
        
        try {
            callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(callIntent)
            val actionLabel = if (isDirect) "laga rahi hoon" else "dialer par taiyar hai"
            val responseMsg = "$contact ko phone $actionLabel."
            recordAndSpeakLocalResult(rawCommand, "CALL", "Call intent triggered: $finalNumber", true, responseMsg)
        } catch (e: Exception) {
            recordAndSpeakLocalResult(rawCommand, "CALL", "Phone call action failed: ${e.message}", false, "Phone call milane me problem aayi.")
        }
    }

    private suspend fun executeSmsAutomation(contact: String, message: String, rawCommand: String) {
        val phoneNumber = getContactPhoneNumber(context, contact)
        val finalNumber = phoneNumber ?: contact
        
        try {
            val smsUri = Uri.parse("smsto:$finalNumber")
            val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(smsIntent)
            val responseMsg = "$contact ko SMS compose taiyar kar diya hai."
            recordAndSpeakLocalResult(rawCommand, "SMS", "SMS compose prefilled: $finalNumber", true, responseMsg)
        } catch (e: Exception) {
            recordAndSpeakLocalResult(rawCommand, "SMS", "SMS compose launch failed: ${e.message}", false, "SMS kholne me problem aayi.")
        }
    }

    private suspend fun checkAndExecuteLocalCommand(command: String): Boolean {
        val lowerText = command.lowercase().trim()
        
        // 1. Screen reading / Screen context understanding features
        if ((lowerText.contains("screen") || lowerText.contains("parde") || lowerText.contains("display") || lowerText.contains("mobile")) &&
            (lowerText.contains("kya") || lowerText.contains("padho") || lowerText.contains("likha") || lowerText.contains("read") || lowerText.contains("what") || lowerText.contains("hai"))) {
            if (!SiriAccessibilityService.isRunning) {
                val responseMsg = "Siri automation accessibility status check failed. Settings me isse enable karein."
                recordAndSpeakLocalResult(command, "SYSTEM", "Accessibility Service is offline", false, responseMsg)
                return true
            }
            val textOnScreen = SiriAccessibilityService.getScreenContentText()
            val textToAnnounce = if (textOnScreen.isBlank() || textOnScreen.startsWith("No readable text") || textOnScreen.startsWith("Screen is empty")) {
                "Screen par mujhe koi readable content nahi mila... lagta hai screen khali hai ya graphical image hai."
            } else {
                val excerpt = if (textOnScreen.length > 220) textOnScreen.substring(0, 215) + "..." else textOnScreen
                "Screen par ye likha hai: $excerpt"
            }
            recordAndSpeakLocalResult(command, "READ_SCREEN", "Read screen: ${textOnScreen.take(50)}", true, textToAnnounce)
            return true
        }

        // 2. Click button command
        if (lowerText.contains("click") || lowerText.contains("tap") || lowerText.contains("touch") || lowerText.contains("dabao") || lowerText.contains("press")) {
            if (!SiriAccessibilityService.isRunning) {
                val responseMsg = "Siri Automation is disabled. Settings se accessibility enable karein."
                recordAndSpeakLocalResult(command, "SYSTEM", "Accessibility Service is offline", false, responseMsg)
                return true
            }
            val targetLabel = lowerText
                .replace("siri", "")
                .replace("click करो", "")
                .replace("click karo", "")
                .replace("click", "")
                .replace("tap", "")
                .replace("touch", "")
                .replace("dabao", "")
                .replace("button", "")
                .replace("par", "")
                .replace("press", "")
                .trim()
            if (targetLabel.isNotEmpty()) {
                val success = SiriAccessibilityService.clickNodeByText(targetLabel) || SiriAccessibilityService.clickNodeByDescription(targetLabel)
                val responseMsg = if (success) {
                    " Maine $targetLabel button par click kar diya hai."
                } else {
                    " Maine screen me $targetLabel dhoondhne ki koshish ki, lekin koi click target nahi mila."
                }
                recordAndSpeakLocalResult(command, "CLICK_NODE", "Click elements matching label: $targetLabel", success, responseMsg)
                return true
            }
        }

        // --- INSTAGRAM AUTO SCROLLER COMMANDS ---
        if (lowerText.contains("auto scroll") || lowerText.contains("autoscroll") || lowerText.contains("reel change") || lowerText.contains("change karo") || lowerText.contains("scroller")) {
            if (!SiriAccessibilityService.isRunning) {
                val responseMsg = "Auto Scroll ke liye Siri Accessibility Service offline hai. Settings se isse bind karein."
                recordAndSpeakLocalResult(command, "AUTO_SCROLL", "Accessibility service is offline", false, responseMsg)
                return true
            }

            // D. Stop auto scroll
            if (lowerText.contains("stop") || lowerText.contains("band") || lowerText.contains("khatam")) {
                com.example.service.SiriAutoScrollController.stop()
                val responseMsg = "Ji Sir, Instagram auto scroll stop kar diya hai."
                recordAndSpeakLocalResult(command, "AUTO_SCROLL", "Stop auto scroll", true, responseMsg)
                return true
            }

            // E. Pause auto scroll
            if (lowerText.contains("pause") || lowerText.contains("ruk") || lowerText.contains("roko")) {
                com.example.service.SiriAutoScrollController.pause()
                val responseMsg = "Ji Sir, auto scroll pause kar diya hai."
                recordAndSpeakLocalResult(command, "AUTO_SCROLL", "Pause auto scroll", true, responseMsg)
                return true
            }

            // F. Resume auto scroll
            if (lowerText.contains("resume") || lowerText.contains("chalao") || lowerText.contains("shuru")) {
                com.example.service.SiriAutoScrollController.resume()
                val responseMsg = "Ji Sir, auto scroll resume kar diya hai."
                recordAndSpeakLocalResult(command, "AUTO_SCROLL", "Resume auto scroll", true, responseMsg)
                return true
            }

            // G. Start/configure auto scroll
            if (lowerText.contains("start") || lowerText.contains("chalu") || lowerText.contains("karo") || lowerText.contains("second") || lowerText.contains("sec")) {
                var duration = 15 // Default
                val regex = "(\\d+)\\s*(second|sec|s)".toRegex()
                val match = regex.find(lowerText)
                if (match != null) {
                    val secStr = match.groupValues[1]
                    duration = secStr.toIntOrNull() ?: 15
                } else {
                    val numRegex = "(\\d+)".toRegex()
                    val numMatch = numRegex.find(lowerText)
                    if (numMatch != null) {
                        duration = numMatch.groupValues[1].toIntOrNull() ?: 15
                    }
                }

                com.example.service.SiriAutoScrollController.start(duration)
                val responseMsg = "Ji Sir, Instagram auto scroll start kar raha hoon. Har $duration second par reel change hogi."
                recordAndSpeakLocalResult(command, "AUTO_SCROLL", "Start auto scroll with duration $duration s", true, responseMsg)
                return true
            }
        }

        // 3. Scroll simulation
        if (lowerText.contains("scroll") || lowerText.contains("niche") || lowerText.contains("upar") || lowerText.contains("down") || lowerText.contains("up")) {
            if (!SiriAccessibilityService.isRunning) {
                val responseMsg = "Scroll ke liye accessibility features enable check fail bano."
                recordAndSpeakLocalResult(command, "SYSTEM", "Accessibility Service is offline", false, responseMsg)
                return true
            }
            val isForward = lowerText.contains("niche") || lowerText.contains("down") || lowerText.contains("scroll")
            val directionStr = if (isForward) "down" else "up"
            val success = SiriAccessibilityService.scroll(isForward)
            val responseMsg = if (success) {
                "Maine screen ko $directionStr scroll kar diya hai."
            } else {
                "Scroll karne yogya scrollable view nahi mila."
            }
            recordAndSpeakLocalResult(command, "SCROLL_SCREEN", "Scroll screen viewport: $directionStr", success, responseMsg)
            return true
        }

        // 4. YouTube Shorts special command
        if ((lowerText.contains("youtube") && lowerText.contains("shorts")) || lowerText.contains("shorts")) {
            try {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage("com.google.android.youtube")
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(2500) // wait for app launch to load
                    if (SiriAccessibilityService.isRunning) {
                        // Click Shorts tab inside Youtube
                        SiriAccessibilityService.clickNodeByText("Shorts")
                        SiriAccessibilityService.clickNodeByDescription("Shorts")
                    }
                    val responseMsg = "YouTube app kholkar Shorts check kar diya bano."
                    recordAndSpeakLocalResult(command, "APP_LAUNCH", "Triggered Youtube Shorts", true, responseMsg)
                } else {
                    val fallbackUri = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallbackUri)
                    recordAndSpeakLocalResult(command, "APP_LAUNCH", "Youtube not installed, fallback shorts URL", true, "YouTube installed nahi hai, web browser me Shorts khol rahi hoon.")
                }
            } catch (e: Exception) {
                recordAndSpeakLocalResult(command, "APP_LAUNCH", "Shorts launch exception: ${e.message}", false, "YouTube Shorts open karne me problem aayi.")
            }
            return true
        }

        // Health stats queries command intercept
        if (lowerText.contains("health") || lowerText.contains("chala") || lowerText.contains("walk") || lowerText.contains("steps") || lowerText.contains("kilometer") || lowerText.contains("report") || lowerText.contains("kitna km")) {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val stats = siriDao.getHealthStatsForDate(date)
            val steps = stats?.steps ?: 0
            val distance = String.format(Locale.US, "%.1f", stats?.distanceKm ?: 0.0)
            val minutes = stats?.walkingTimeMinutes ?: 0
            val responseMsg = "Sir, aaj aapne $distance kilometer walk kiya hai. Total walking time $minutes minutes raha aur $steps steps complete hue hain."
            recordAndSpeakLocalResult(command, "HEALTH", "Queried daily health report.", true, responseMsg)
            return true
        }

        // App launch
        if (lowerText.contains("kholo") || lowerText.contains("open") || lowerText.contains("chalao") || lowerText.contains("kholey") || lowerText.contains("launch")) {
            val appLabel = lowerText
                .replace("open", "")
                .replace("kholo", "")
                .replace("chalao", "")
                .replace("kholey", "")
                .replace("launch", "")
                .replace("siri", "")
                .trim()
            
            val resolvedPackage = getPackageNameForAppLabel(context, appLabel) ?: when {
                lowerText.contains("whatsapp") -> "com.whatsapp"
                lowerText.contains("youtube") -> "com.google.android.youtube"
                lowerText.contains("telegram") -> "org.telegram.messenger"
                lowerText.contains("instagram") -> "com.instagram.android"
                lowerText.contains("facebook") -> "com.facebook.katana"
                lowerText.contains("chrome") -> "com.android.chrome"
                lowerText.contains("maps") -> "com.google.android.apps.maps"
                lowerText.contains("camera") -> "com.android.camera"
                else -> null
            }
            
            val responseMsg = "Theek hai, ${if (appLabel.isEmpty() && resolvedPackage != null) resolvedPackage.split(".").last() else appLabel} khol rahi hoon."
            var execMsg = ""
            var isSuccess = false
            
            if (resolvedPackage != null) {
                val intent = context.packageManager.getLaunchIntentForPackage(resolvedPackage)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    execMsg = "Direct package launch: $resolvedPackage"
                    isSuccess = true
                } else {
                    execMsg = "Launch intent was null for $resolvedPackage"
                    isSuccess = false
                }
            } else if (appLabel.isNotEmpty()) {
                val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$appLabel")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(searchIntent)
                execMsg = "No local app package found, fallback Google search."
                isSuccess = true
            }
            
            if (resolvedPackage != null || appLabel.isNotEmpty()) {
                recordAndSpeakLocalResult(command, "APP", execMsg, isSuccess, responseMsg)
                return true
            }
        }
        
        // WhatsApp messages matching
        if (lowerText.contains("whatsapp") && (lowerText.contains("message") || lowerText.contains("sandesh") || lowerText.contains("bhej") || lowerText.contains("karo"))) {
            var contact = "Pankaj"
            var message = "kal ghar jana hai"
            val cleanStr = lowerText.replace("siri", "").replace("whatsapp", "").trim()
            if (cleanStr.contains("ko")) {
                val parts = cleanStr.split("ko")
                val left = parts[0].replace("me", "").replace("par", "").trim()
                if (left.isNotEmpty()) {
                    contact = left
                }
                val right = parts.getOrNull(1)?.replace("message karo", "")?.replace("message bhejo", "")?.replace("message", "")?.replace("bhejo", "")?.replace("karo", "")?.trim() ?: ""
                if (right.isNotEmpty()) {
                    message = right
                }
            }
            executeWhatsAppAutomation(contact, message, command)
            return true
        }

        // Call matching
        if (lowerText.contains("call") || lowerText.contains("phone") || lowerText.contains("dial") || lowerText.contains("lagao")) {
            var contact = "mummy"
            val cleanStr = lowerText.replace("siri", "").replace("call", "").replace("phone", "").replace("lagao", "").replace("karo", "").trim()
            if (cleanStr.contains("ko")) {
                contact = cleanStr.substringBefore("ko").trim()
            } else if (cleanStr.isNotEmpty()) {
                contact = cleanStr
            }
            executePhoneCallAutomation(contact, command)
            return true
        }

        // SMS matching
        if (lowerText.contains("sms") || (lowerText.contains("message") && lowerText.contains("bhejo") && !lowerText.contains("whatsapp"))) {
            var contact = "Pankaj"
            var message = "kal milte hain"
            val cleanStr = lowerText.replace("siri", "").replace("sms", "").trim()
            if (cleanStr.contains("ko")) {
                contact = cleanStr.substringBefore("ko").trim()
                val right = cleanStr.substringAfter("ko").replace("message", "").replace("bhejo", "").replace("karo", "").trim()
                if (right.isNotEmpty()) {
                    message = right
                }
            }
            executeSmsAutomation(contact, message, command)
            return true
        }

        // Torch toggle
        if (lowerText.contains("flashlight") || lowerText.contains("torch") || lowerText.contains("jala") || lowerText.contains("bhuta") || lowerText.contains("jalao")) {
            val isOn = !(lowerText.contains("band") || lowerText.contains("off") || lowerText.contains("bhuta") || lowerText.contains("bujhao"))
            val resultOnOff = if (isOn) "on" else "off"
            
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, isOn)
                    val responseMsg = "Flashlight ko $resultOnOff kar diya hai."
                    recordAndSpeakLocalResult(command, "SYSTEM", "Flashlight set to $resultOnOff", true, responseMsg)
                } else {
                    recordAndSpeakLocalResult(command, "SYSTEM", "No flashlight hardware", false, "Mujhe flashlight hardware nahi mila.")
                }
            } catch (e: Exception) {
                recordAndSpeakLocalResult(command, "SYSTEM", "Flashlight error: ${e.message}", false, "Flashlight chalane me koi problem aayi.")
            }
            return true
        }

        // Volume control trigger
        if (lowerText.contains("volume") || lowerText.contains("awaaj") || lowerText.contains("sound")) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val isDown = lowerText.contains("kam") || lowerText.contains("down") || lowerText.contains("ghatao") || lowerText.contains("silent")
            val isUp = lowerText.contains("teji") || lowerText.contains("halka") || lowerText.contains("up") || lowerText.contains("badhao") || lowerText.contains("high")
            
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            var targetVol = currentVol
            var percent = 50
            
            val numberInText = lowerText.replace("%", "").split(" ").firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            if (numberInText != null) {
                percent = numberInText.coerceIn(0, 100)
                targetVol = (maxVol * (percent / 100f)).toInt()
            } else if (isDown) {
                targetVol = (currentVol - 2).coerceAtLeast(0)
                percent = (targetVol * 100f / maxVol).toInt()
            } else if (isUp) {
                targetVol = (currentVol + 2).coerceAtMost(maxVol)
                percent = (targetVol * 100f / maxVol).toInt()
            }
            
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                val responseMsg = "Awaaj ko $percent percent kar diya hai."
                recordAndSpeakLocalResult(command, "SYSTEM", "Volume set to $percent%", true, responseMsg)
            } catch (e: Exception) {
                recordAndSpeakLocalResult(command, "SYSTEM", "Volume error: ${e.message}", false, "Volume adjust karne me error aayi.")
            }
            return true
        }

        // Brightness control trigger
        if (lowerText.contains("brightness") || lowerText.contains("ujala") || lowerText.contains("prakash")) {
            var percent = 50
            val isDown = lowerText.contains("kam") || lowerText.contains("down") || lowerText.contains("ghatao")
            val isUp = lowerText.contains("badhao") || lowerText.contains("high") || lowerText.contains("up")
            
            val numberInText = lowerText.replace("%", "").split(" ").firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
            if (numberInText != null) {
                percent = numberInText.coerceIn(5, 100)
            } else if (isDown) {
                percent = 15
            } else if (isUp) {
                percent = 95
            }
            
            setScreenBrightness(context, percent)
            val responseMsg = "Brightness ko $percent percent kar diya hai."
            recordAndSpeakLocalResult(command, "SYSTEM", "Brightness level set to $percent%", true, responseMsg)
            return true
        }

        // WiFi launcher panel
        if (lowerText.contains("wifi") || lowerText.contains("internet") || lowerText.contains("data")) {
            try {
                val wifiSettingsIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(wifiSettingsIntent)
                val responseMsg = "WiFi settings khol rahi hoon."
                recordAndSpeakLocalResult(command, "SYSTEM", "Opened WiFi settings panel", true, responseMsg)
            } catch (e: Exception) {
                recordAndSpeakLocalResult(command, "SYSTEM", "WiFi panel error: ${e.message}", false, "WiFi panel nahi khol payi.")
            }
            return true
        }

        // Bluetooth settings panel
        if (lowerText.contains("bluetooth")) {
            try {
                val bluetoothSettingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(bluetoothSettingsIntent)
                val responseMsg = "Bluetooth settings page khol diya hai."
                recordAndSpeakLocalResult(command, "SYSTEM", "Opened Bluetooth settings panel", true, responseMsg)
            } catch (e: Exception) {
                recordAndSpeakLocalResult(command, "SYSTEM", "Bluetooth panel error: ${e.message}", false, "Bluetooth settings nahi khol saki.")
            }
            return true
        }

        // Alarm setting
        if (lowerText.contains("alarm") || lowerText.contains("alam") || lowerText.contains("subah") || lowerText.contains("shaam")) {
            if (lowerText.contains("alarm") || lowerText.contains("alam")) {
                var hr = 6
                var min = 0
                val digits = lowerText.split(" ").mapNotNull { it.replace(":", "").toIntOrNull() }
                if (digits.isNotEmpty()) {
                    val num = digits[0]
                    if (num in 0..23) {
                        hr = num
                    }
                }
                if (lowerText.contains("shaam") || lowerText.contains("pm") || lowerText.contains("dopahar")) {
                    if (hr in 1..11) {
                        hr += 12
                    }
                }
                try {
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hr)
                        putExtra(AlarmClock.EXTRA_MINUTES, min)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "Siri Quick Alarm")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(alarmIntent)
                    val responseMsg = "$hr baje ka alarm set kar diya hai."
                    recordAndSpeakLocalResult(command, "ALARM", "Set alarm for $hr:$min", true, responseMsg)
                } catch (e: Exception) {
                    recordAndSpeakLocalResult(command, "ALARM", "Alarm creation failed: ${e.message}", false, "Alarm block nahi laga saki.")
                }
                return true
            }
        }

        // Media controls dispatcher
        if (lowerText.contains("play") || lowerText.contains("pause") || lowerText.contains("stop") || lowerText.contains("gaana") || lowerText.contains("baja")) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val keyCode = when {
                    lowerText.contains("pause") || lowerText.contains("stop") || lowerText.contains("roko") || lowerText.contains("band") -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    lowerText.contains("next") || lowerText.contains("agla") -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                    lowerText.contains("prev") || lowerText.contains("pichla") -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                }
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
                
                val actionName = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "paused"
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "next track"
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous track"
                    else -> "playing"
                }
                recordAndSpeakLocalResult(command, "MEDIA", "Dispatched KeyCode $keyCode", true, "Gaana $actionName kar rahi hoon.")
            } catch (e: Exception) {
                recordAndSpeakLocalResult(command, "MEDIA", "Media failed: ${e.message}", false, "Media response error bano.")
            }
            return true
        }

        return false
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(siriReceiver)
        } catch (e: Exception) {}
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        localSpeechRecognizer?.destroy()
    }

    private suspend fun executeAppMessageAutomation(packageName: String, contact: String, message: String, rawCommand: String) {
        val appName = when {
            packageName.contains("telegram") -> "Telegram"
            packageName.contains("instagram") -> "Instagram"
            packageName.contains("messenger") -> "Messenger"
            else -> "App"
        }
        
        if (SiriAccessibilityService.isRunning) {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                
                val startMsg = "$appName khol rahi hoon, $contact ko reply bhej rahi hoon."
                recordAndSpeakLocalResult(rawCommand, appName.uppercase(), "Opened $appName, reply triggered.", true, startMsg)
                
                handler.postDelayed({
                    viewModelScope.launch {
                        var clickedSearch = SiriAccessibilityService.clickNodeByText("Search")
                        if (!clickedSearch) clickedSearch = SiriAccessibilityService.clickNodeByDescription("Search")
                        if (!clickedSearch) clickedSearch = SiriAccessibilityService.clickNodeByDescription("खोजें")
                        if (!clickedSearch) clickedSearch = SiriAccessibilityService.clickNodeByText("खोजें")
                        
                        delay(1200)
                        SiriAccessibilityService.typeText(contact)
                        
                        delay(1500)
                        val clickedContact = SiriAccessibilityService.clickNodeByText(contact)
                        
                        delay(1500)
                        SiriAccessibilityService.typeText(message)
                        
                        delay(1500)
                        var clickedSend = SiriAccessibilityService.clickNodeByDescription("Send")
                        if (!clickedSend) clickedSend = SiriAccessibilityService.clickNodeByDescription("भेजें")
                        if (!clickedSend) clickedSend = SiriAccessibilityService.clickNodeByText("Send")
                        if (!clickedSend) clickedSend = SiriAccessibilityService.clickNodeByText("भेजें")
                        
                        if (clickedSend) {
                            speak("$appName par $contact ko sandesh bhej diya hai!")
                        } else {
                            speak("Sandesh tayar kar diya hai, reply bhejne ke liye kripya button dabayein.")
                        }
                    }
                }, 1800)
            } else {
                val errorMsg = "$appName hardware me install nahi hai."
                recordAndSpeakLocalResult(rawCommand, appName.uppercase(), "$appName not installed.", false, errorMsg)
            }
        } else {
            try {
                val uri = Uri.parse("sms:?body=${Uri.encode(message)}")
                val smsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(smsIntent)
                recordAndSpeakLocalResult(rawCommand, appName.uppercase(), "Accessibility offline, prefilled message fallback", true, "Accessibility offline hai. Sandesh prefill kar rahi hoon.")
            } catch (e: Exception) {
                recordAndSpeakLocalResult(rawCommand, appName.uppercase(), "Launch failed: ${e.message}", false, "Reply open karne me issue aayi.")
            }
        }
    }
}

object NotificationReplyState {
    var lastPackageName: String? = null
    var lastSender: String? = null
    var lastMessage: String? = null
    var isWaitingForReplyOption = false
    var isWaitingForMessageBody = false

    fun clear() {
        lastPackageName = null
        lastSender = null
        lastMessage = null
        isWaitingForReplyOption = false
        isWaitingForMessageBody = false
    }
}

@JsonClass(generateAdapter = true)
data class ParsedCommand(
    @Json(name = "intent") val intent: String,
    @Json(name = "params") val params: ParsedParams? = null,
    @Json(name = "spokenResponse") val spokenResponse: String? = null
)

@JsonClass(generateAdapter = true)
data class ParsedParams(
    @Json(name = "contact") val contact: String? = null,
    @Json(name = "message") val message: String? = null,
    @Json(name = "appName") val appName: String? = null,
    @Json(name = "setting") val setting: String? = null,
    @Json(name = "value") val value: String? = null,
    @Json(name = "time") val time: String? = null,
    @Json(name = "route") val route: String? = null
)
