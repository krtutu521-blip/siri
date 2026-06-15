package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.SiriApplication
import com.example.data.DailyHealthStats
import com.example.data.LocationPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.TelecomManager
import android.view.KeyEvent
import android.view.Gravity
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.View
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SiriForegroundService : Service(), SensorEventListener, LocationListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())
    private var wakeWordListenerActive = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Health tracking components
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var currentDaySteps = 0
    private var currentDayDistanceKm = 0.0
    private var currentDayCalories = 0
    private var currentDayWalkingMinutes = 0
    private var lastLocation: Location? = null
    private var lastMovementTime: Long = 0

    // Voice and telephony engine properties
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private val ttsCallbacks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    private var isIncomingCallActive = false
    private var currentCallerName = "Unknown"
    private var originalRingerVolume = -1
    private var originalRingerMode = -1
    private lateinit var windowManager: WindowManager
    private var callOverlayView: ComposeView? = null

    private var pendingReplyPackage: String? = null
    private var pendingReplySender: String? = null
    private var pendingReplyText: String? = null

    enum class SiriMode {
        WAKE_WORD,
        CALL_CONTROL,
        NOTIFICATION_REPLY_OPTION,
        NOTIFICATION_REPLY_BODY
    }
    private var siriMode = SiriMode.WAKE_WORD

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "serviceReceiver received action: $action")
            when (action) {
                SiriNotificationListenerService.ACTION_ANNOUNCE -> {
                    val msg = intent.getStringExtra(SiriNotificationListenerService.EXTRA_MESSAGE) ?: ""
                    val pkg = intent.getStringExtra(SiriNotificationListenerService.EXTRA_PACKAGE) ?: ""
                    val sender = intent.getStringExtra(SiriNotificationListenerService.EXTRA_SENDER) ?: ""
                    
                    if (msg.isNotEmpty()) {
                        Log.d(TAG, "Received notification to speak in service receiver: $msg, pkg: $pkg, sender: $sender")
                        pendingReplyPackage = pkg
                        pendingReplySender = sender
                        siriMode = SiriMode.NOTIFICATION_REPLY_OPTION
                        speakResponse(msg, "NotificationReplyPrompt")
                    }
                }
                "android.intent.action.PHONE_STATE" -> {
                    val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
                    val incomingNumber = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER)
                    Log.d(TAG, "Phone Call State Changed: State = $state, Number = $incomingNumber")
                    handleCallState(state, incomingNumber)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Foreground Service Created")
        createNotificationChannel()
        acquireWakeLock()
        initHealthTracking()
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Initialize Text To Speech
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                val result = textToSpeech?.setLanguage(Locale("hi", "IN"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech?.setLanguage(Locale.US)
                }
                
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS speaking started: $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS speaking completed: $utteranceId")
                        if (utteranceId == "CallAnnouncement") {
                            handler.post {
                                startListeningForCallCommands()
                            }
                        } else if (utteranceId == "NotificationReplyPrompt") {
                            handler.post {
                                startListeningForNotificationReplyOption()
                            }
                        } else if (utteranceId == "NotificationAskBodyPrompt") {
                            handler.post {
                                startListeningForNotificationReplyBody()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                    }
                })
            }
        }

        // Register Dynamic BroadcastReceiver for Notifications and Phone Call state
        val serviceFilter = IntentFilter().apply {
            addAction(SiriNotificationListenerService.ACTION_ANNOUNCE)
            addAction("android.intent.action.PHONE_STATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, serviceFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceReceiver, serviceFilter)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SiriAssistant:WakeLock").apply {
                acquire()
            }
            Log.d(TAG, "Partial WakeLock acquired successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Foreground Service Started")
        
        // Handle stop action from notification
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Run as foreground
        val notification = createNotification("Power button activation mode. Siri is ready.")
        startForeground(NOTIFICATION_ID, notification)

        // Wake word listening is disabled by default for battery, privacy, and touch/button trigger activation
        Log.d(TAG, "Continuous wake word listening disabled by default. Mic is off.")

        return START_STICKY
    }

    private fun startWakeWordListening() {
        if (wakeWordListenerActive) return
        wakeWordListenerActive = true
        
        handler.post {
            try {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                    speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "Speech recognizer ready")
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {
                            // Send out RMS dB for waveform animation in UI if active
                            sendRmsBroadcast(rmsdB)
                        }
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        
                        override fun onError(error: Int) {
                            Log.d(TAG, "Speech recognizer error: $error")
                            unmuteSystemSounds()
                            // Restart listening unless canceled
                            if (siriMode == SiriMode.WAKE_WORD) {
                                if (wakeWordListenerActive) {
                                    handler.postDelayed({ restartListening() }, 1000)
                                }
                            } else {
                                handler.postDelayed({ restartListening() }, 800)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            unmuteSystemSounds()
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (matches != null && matches.isNotEmpty()) {
                                val matchHeard = matches[0]
                                Log.d(TAG, "Speech result heard in mode $siriMode: $matchHeard")
                                handleHeardText(matchHeard)
                            } else {
                                if (siriMode == SiriMode.WAKE_WORD) {
                                    if (wakeWordListenerActive) {
                                        restartListening()
                                    }
                                } else {
                                    restartListening()
                                }
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            if (siriMode != SiriMode.WAKE_WORD) return
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (matches != null) {
                                for (match in matches) {
                                    val matchLower = match.lowercase(Locale.getDefault())
                                    if (isWakeWord(matchLower)) {
                                        Log.d(TAG, "Wake word detected in partial result: $match")
                                        triggerAssistant(match)
                                        break
                                    }
                                }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })

                    startListening()
                } else {
                    Log.d(TAG, "Speech recognizer not available on this device, using background loop emulator")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start background speech API", e)
            }
        }
    }

    private fun startListening() {
        muteSystemSounds()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, arrayListOf("hi-IN", "en-IN", "en-US"))
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech listener", e)
        }
    }

    private fun restartListening() {
        if (!wakeWordListenerActive) return
        speechRecognizer?.cancel()
        startListening()
    }

    private fun stopWakeWordListening() {
        wakeWordListenerActive = false
        unmuteSystemSounds()
        speechRecognizer?.apply {
            stopListening()
            cancel()
            destroy()
        }
        speechRecognizer = null
        isListening = false
    }

    private fun triggerAssistant(heardText: String) {
        unmuteSystemSounds()
        // Play notification or vibration
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

        // Launch app overlay or send broadcast to main app to open the panel
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_WAKE_WORD_TRIGGERED, true)
            putExtra(EXTRA_INITIAL_COMMAND, heardText)
        }
        startActivity(intent)

        // Send a broadcast to the local UI if it's open
        val localIntent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            putExtra(EXTRA_INITIAL_COMMAND, heardText)
        }
        sendBroadcast(localIntent)
    }

    private fun sendRmsBroadcast(rms: Float) {
        val intent = Intent(ACTION_RMS_CHANGED).apply {
            putExtra(EXTRA_RMS_VALUE, rms)
        }
        sendBroadcast(intent)
    }

    private fun isWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale.getDefault()).trim()
        val wakeWords = listOf(
            "siri", "siry", "seeree", "sheeree", "shiri", "cheri", "cherry", "sirri", "seri",
            "hey siri", "hello siri", "hay siri", "hi siri", "hell siri", "he siri", "eh siri",
            "सिरी", "हे सिरी", "हेलो सिरी", "ओ हरी"
        )
        for (wake in wakeWords) {
            if (lower.contains(wake)) {
                return true
            }
        }
        return false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed (app swiped from recents) - scheduling relaunch")
        val restartServiceIntent = Intent(applicationContext, this.javaClass).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            applicationContext, 1234, restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmService = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopWakeWordListening()
        releaseWakeLock()
        
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering serviceReceiver", e)
        }
        
        textToSpeech?.apply {
            stop()
            shutdown()
        }
        textToSpeech = null
        isTtsReady = false
        
        dismissCallOverlay()
        
        try {
            sensorManager?.unregisterListener(this)
            locationManager?.removeUpdates(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleanup sensors/GPS", e)
        }
        unmuteSystemSounds()
        super.onDestroy()
        Log.d(TAG, "Foreground Service Destroyed")
    }

    // --- HEALTH TRACKING SYSTEM ---
    private fun initHealthTracking() {
        Log.d(TAG, "Initializing Health Tracking...")
        serviceScope.launch {
            loadTodayStatsFromDb()
        }

        // Initialize Sensors
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val countSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (countSensor != null) {
                sensorManager?.registerListener(this, countSensor, SensorManager.SENSOR_DELAY_UI)
                Log.d(TAG, "Step Counter Sensor registered successfully")
            } else {
                val detectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
                if (detectorSensor != null) {
                    sensorManager?.registerListener(this, detectorSensor, SensorManager.SENSOR_DELAY_UI)
                    Log.d(TAG, "Step Detector Sensor registered successfully")
                } else {
                    Log.d(TAG, "No step count/detect hardware sensors found. Relying on GPS steps calculation fallback!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting step sensor", e)
        }

        // Initialize GPS Location
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isGpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            val isNetworkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            
            if (isGpsEnabled || isNetworkEnabled) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    if (isGpsEnabled) {
                        locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, this)
                    }
                    if (isNetworkEnabled) {
                        locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, this)
                    }
                    Log.d(TAG, "GPS/Network Location Updates registered")
                } else {
                    Log.d(TAG, "Location permission not granted, background GPS tracking cannot start until granted.")
                }
            } else {
                Log.d(TAG, "GPS or Network Provider are disabled.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting GPS tracking", e)
        }
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private suspend fun loadTodayStatsFromDb() {
        try {
            val date = getTodayDateString()
            val dao = SiriApplication.instance.database.siriDao
            val stats = dao.getHealthStatsForDate(date)
            if (stats != null) {
                currentDaySteps = stats.steps
                currentDayDistanceKm = stats.distanceKm
                currentDayCalories = stats.caloriesBurned
                currentDayWalkingMinutes = stats.walkingTimeMinutes
                Log.d(TAG, "Loaded stats for today: Steps=$currentDaySteps, Dist=$currentDayDistanceKm km")
            } else {
                val newStats = DailyHealthStats(date, 0, 0.0, 0, 0)
                dao.insertHealthStats(newStats)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading today's health stats from DB", e)
        }
    }

    private fun saveStatsToDb() {
        serviceScope.launch {
            try {
                val date = getTodayDateString()
                val dao = SiriApplication.instance.database.siriDao
                val stats = DailyHealthStats(
                    date = date,
                    steps = currentDaySteps,
                    distanceKm = currentDayDistanceKm,
                    walkingTimeMinutes = currentDayWalkingMinutes,
                    caloriesBurned = currentDayCalories
                )
                dao.insertHealthStats(stats)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving health stats to DB", e)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val totalSteps = event.values[0].toInt()
            val prefs = getSharedPreferences("siri_prefs", Context.MODE_PRIVATE)
            val baseSteps = prefs.getInt("basesteps_${getTodayDateString()}", -1)
            if (baseSteps == -1) {
                prefs.edit().putInt("basesteps_${getTodayDateString()}", totalSteps).apply()
                currentDaySteps = 0
            } else if (totalSteps >= baseSteps) {
                currentDaySteps = totalSteps - baseSteps
            } else {
                prefs.edit().putInt("basesteps_${getTodayDateString()}", totalSteps).apply()
                currentDaySteps = 0
            }
        } else if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            if (event.values[0] == 1.0f) {
                currentDaySteps += 1
            }
        }
        
        currentDayDistanceKm = currentDaySteps * 0.00075
        currentDayCalories = (currentDaySteps * 0.04).toInt()
        
        saveStatsToDb()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        serviceScope.launch {
            try {
                val dao = SiriApplication.instance.database.siriDao
                dao.insertLocationPoint(
                    LocationPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speedMps = location.speed
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error logging location point", e)
            }
        }

        if (lastLocation != null) {
            val dist = lastLocation!!.distanceTo(location)
            if (dist > 1.5) {
                val distKm = dist / 1100.0 // adjust for minor drifts and calibration
                currentDayDistanceKm += distKm
                
                val isStepSensorAvailable = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null || 
                                             sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
                if (!isStepSensorAvailable) {
                    currentDaySteps = (currentDayDistanceKm / 0.00075).toInt()
                }

                currentDayCalories = (currentDaySteps * 0.04).toInt()

                val now = System.currentTimeMillis()
                if (lastMovementTime > 0) {
                    val durationMs = now - lastMovementTime
                    if (location.speed > 0.4f || dist / (durationMs / 1000.0) > 0.4) {
                        val durationMinutes = (durationMs / 60000.0)
                        if (durationMinutes < 10 && durationMinutes > 0) {
                            currentDayWalkingMinutes = (currentDayWalkingMinutes + durationMinutes).toInt()
                        }
                    }
                }
                lastMovementTime = now
            }
        } else {
            lastMovementTime = System.currentTimeMillis()
        }
        lastLocation = location
        saveStatsToDb()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // --- AUDIO MANAGEMENTS FOR SILENT LISTENING ---
    private fun muteSystemSounds() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, true)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, true)
            }
            Log.d(TAG, "System sounds muted for silent listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error muting system sounds", e)
        }
    }

    private fun unmuteSystemSounds() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0)
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_NOTIFICATION, false)
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_SYSTEM, false)
            }
            Log.d(TAG, "System sounds restored")
        } catch (e: Exception) {
            Log.e(TAG, "Error unmuting system sounds", e)
        }
    }

    private fun createNotification(message: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("active_listen_bubble", true)
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 12, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SiriForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activateIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("active_listen_bubble", true)
        }
        val activatePendingIntent = PendingIntent.getActivity(
            this, 14, activateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Siri AI Assistant")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Activate", activatePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Siri Background Command Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    // --- VOICE, CALL OVERLAY, AND NOTIFICATION STATE MACHINE INTEGRATION ---

    private fun speakResponse(text: String, utteranceId: String = "SiriSpeak", onDoneAction: (() -> Unit)? = null) {
        if (!isTtsReady || textToSpeech == null) {
            Log.e(TAG, "TTS not ready or null")
            onDoneAction?.invoke()
            return
        }
        
        onDoneAction?.let {
            ttsCallbacks[utteranceId] = it
        }

        // Always cancel any active recognizer before speaking to prevent TTS talking to itself
        speechRecognizer?.cancel()

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
        }
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun startListeningForCallCommands() {
        siriMode = SiriMode.CALL_CONTROL
        restartListening()
    }

    private fun startListeningForNotificationReplyOption() {
        siriMode = SiriMode.NOTIFICATION_REPLY_OPTION
        restartListening()
    }

    private fun startListeningForNotificationReplyBody() {
        siriMode = SiriMode.NOTIFICATION_REPLY_BODY
        restartListening()
    }

    private fun vibrate() {
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
    }

    private fun getRandomGreeting(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour in 5..11 -> "Good Morning Sir."
            hour in 12..16 -> "Good Afternoon Sir."
            else -> "Good Evening Sir."
        }
        val options = listOf(
            "$timeGreeting Ha Sir, boliye kya karna hai?",
            "$timeGreeting Ji Sir, main sun raha hoon.",
            "$timeGreeting Sir, aapke liye kya kar sakta hoon?"
        )
        return options.random()
    }

    private fun handleHeardText(text: String) {
        val lower = text.lowercase(Locale.getDefault()).trim()
        Log.d(TAG, "SiriForegroundService handling heard text: $text in mode $siriMode")

        when (siriMode) {
            SiriMode.WAKE_WORD -> {
                if (isWakeWord(lower)) {
                    vibrate()
                    val greet = getRandomGreeting()
                    siriMode = SiriMode.CALL_CONTROL
                    speakResponse(greet, "GeneralGreetingPrompt")
                }
            }
            SiriMode.CALL_CONTROL -> {
                if (isIncomingCallActive) {
                    when {
                        lower.contains("receive") || lower.contains("answer") || lower.contains("rcv") ||
                        lower.contains("uthao") || lower.contains("utha") || lower.contains("sunao") ||
                        lower.contains("haan") || lower.contains("ha") || lower.contains("yes") || lower.contains("yeah") -> {
                            speakResponse("Call receive kar raha hoon sir.", "CallAccept") {
                                answerCall()
                                dismissCallOverlay()
                            }
                        }
                        lower.contains("reject") || lower.contains("cut") || lower.contains("kat") ||
                        lower.contains("no") || lower.contains("na") || lower.contains("cancel") -> {
                            speakResponse("Call reject kar raha hoon.", "CallReject") {
                                rejectCall()
                                dismissCallOverlay()
                            }
                        }
                        lower.contains("speaker") || lower.contains("speaker on") || lower.contains("loudspeaker") -> {
                            speakResponse("Speaker on kar diya hai.", "CallSpeaker") {
                                enableSpeaker()
                            }
                        }
                        else -> {
                            restartListening()
                        }
                    }
                } else {
                    Log.d(TAG, "Regular background command heard: $text")
                    triggerAssistant(text)
                    siriMode = SiriMode.WAKE_WORD
                }
            }
            SiriMode.NOTIFICATION_REPLY_OPTION -> {
                if (lower.contains("haan") || lower.contains("ha") || lower.contains("yes") || lower.contains("yeah") || lower.contains("han") || lower.contains("reply") || lower.contains("kar do")) {
                    siriMode = SiriMode.NOTIFICATION_REPLY_BODY
                    speakResponse("Kya reply bhejna hai?", "NotificationAskBodyPrompt")
                } else {
                    siriMode = SiriMode.WAKE_WORD
                    speakResponse("Thik hai sir.")
                    restartListening()
                }
            }
            SiriMode.NOTIFICATION_REPLY_BODY -> {
                if (text.isNotBlank() && !text.lowercase().contains("listening")) {
                    pendingReplyText = text
                    siriMode = SiriMode.WAKE_WORD
                    speakResponse("Thik hai sir, message bhej raha hoon.", "NotificationSending") {
                        sendNotificationReply(pendingReplyPackage, pendingReplySender, text)
                    }
                } else {
                    restartListening()
                }
            }
        }
    }

    private fun handleCallState(state: String?, incomingNumber: String?) {
        when (state) {
            android.telephony.TelephonyManager.EXTRA_STATE_RINGING -> {
                isIncomingCallActive = true
                currentCallerName = getContactName(incomingNumber)
                
                muteIncomingRinger()
                requestBluetoothSco()
                showCallOverlay(currentCallerName)
                
                val speakText = if (currentCallerName == "Unknown Number") {
                    "Sir, ek unknown number se call aa raha hai."
                } else if (currentCallerName.lowercase() == "maa" || currentCallerName.lowercase() == "mother") {
                    "Sir, Maa ka call aa raha hai."
                } else {
                    "Sir, $currentCallerName ka call aa raha hai."
                }
                
                speakResponse(speakText, "CallAnnouncement")
            }
            android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isIncomingCallActive = false
                restoreIncomingRinger()
                stopBluetoothSco()
                dismissCallOverlay()
                siriMode = SiriMode.WAKE_WORD
            }
            android.telephony.TelephonyManager.EXTRA_STATE_IDLE -> {
                isIncomingCallActive = false
                restoreIncomingRinger()
                stopBluetoothSco()
                dismissCallOverlay()
                siriMode = SiriMode.WAKE_WORD
                restartListening()
            }
        }
    }

    private fun getContactName(phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return "Unknown Number"
        
        if (phoneNumber.contains("123456") || phoneNumber.contains("98765")) {
            return "Pankaj"
        } else if (phoneNumber.contains("4444") || phoneNumber.contains("3333")) {
            return "Maa"
        } else if (phoneNumber.contains("5555") || phoneNumber.contains("7777")) {
            return "Rahul"
        }

        try {
            val uri = Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return it.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact name", e)
        }
        return "Unknown Number"
    }

    private fun muteIncomingRinger() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            originalRingerMode = audioManager.ringerMode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                originalRingerVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_RING, true)
            }
            Log.d(TAG, "Ringer muted temporarily for announcement")
        } catch (e: Exception) {
            Log.e(TAG, "Error muting ringer", e)
        }
    }

    private fun restoreIncomingRinger() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                if (originalRingerVolume != -1) {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, originalRingerVolume, 0)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_RING, false)
            }
            Log.d(TAG, "Ringer restored")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring ringer", e)
        }
    }

    private fun requestBluetoothSco() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                Log.d(TAG, "Bluetooth SCO started for speech routing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requestBluetoothSco", e)
        }
    }

    private fun stopBluetoothSco() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                Log.d(TAG, "Bluetooth SCO stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopBluetoothSco", e)
        }
    }

    private fun answerCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall()
                    Log.d(TAG, "Call accepted using TelecomManager")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to accept call via TelecomManager", e)
            }
        }
        try {
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            sendOrderedBroadcast(downIntent, null)
            sendOrderedBroadcast(upIntent, null)
            Log.d(TAG, "Call accepted using simulated media button")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to simulate headset button press", e)
        }
    }

    private fun rejectCall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall()
                    Log.d(TAG, "Call ended using TelecomManager")
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject call via TelecomManager", e)
            }
        }
    }

    private fun enableSpeaker() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
            Log.d(TAG, "Speakerphone turned ON")
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling speaker", e)
        }
    }

    private fun showCallOverlay(callerName: String) {
        if (!Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Cannot draw overlay: overlay permission not granted")
            return
        }
        
        handler.post {
            try {
                if (callOverlayView != null) {
                    dismissCallOverlay()
                }

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
                    y = 50
                }

                val composeView = ComposeView(this).apply {
                    setContent {
                        IncomingOverlayUi(
                            callerName = callerName,
                            onReceive = {
                                Log.d(TAG, "Overlay Click: Receive")
                                answerCall()
                                dismissCallOverlay()
                            },
                            onReject = {
                                Log.d(TAG, "Overlay Click: Reject")
                                rejectCall()
                                dismissCallOverlay()
                            }
                        )
                    }
                }

                // Setup Lifecycle for Compose in Service
                val lifecycleOwner = object : SavedStateRegistryOwner {
                    private val lifecycleRegistry = LifecycleRegistry(this)
                    private val savedStateRegistryController = SavedStateRegistryController.create(this)

                    init {
                        savedStateRegistryController.performRestore(null)
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    }

                    override val lifecycle: Lifecycle get() = lifecycleRegistry
                    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
                }

                val viewModelStoreOwner = object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }

                composeView.setViewTreeLifecycleOwner(lifecycleOwner)
                composeView.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
                composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                windowManager.addView(composeView, layoutParams)
                callOverlayView = composeView
                Log.d(TAG, "Incoming Call Overlay added successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding Call Overlay View", e)
            }
        }
    }

    private fun dismissCallOverlay() {
        handler.post {
            try {
                callOverlayView?.let {
                    windowManager.removeView(it)
                    callOverlayView = null
                    Log.d(TAG, "Call Overlay dismissed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing Call Overlay View", e)
            }
        }
    }

    private fun sendNotificationReply(packageName: String?, sender: String?, message: String) {
        val intent = Intent(ACTION_SEND_REPLY_AUTOMATION).apply {
            putExtra(EXTRA_REPLY_PACKAGE, packageName ?: "com.whatsapp")
            putExtra(EXTRA_REPLY_SENDER, sender ?: "")
            putExtra(EXTRA_REPLY_BODY, message)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Sent sendNotificationReply broadcast for sender: $sender, message: $message")
    }

    companion object {
        private const val TAG = "SiriForegroundService"
        private const val NOTIFICATION_ID = 1011
        const val CHANNEL_ID = "SiriForegroundServiceChannel"

        const val ACTION_STOP = "com.example.service.action.STOP"
        const val ACTION_WAKE_WORD_DETECTED = "com.example.service.action.WAKE_WORD_DETECTED"
        const val ACTION_RMS_CHANGED = "com.example.service.action.RMS_CHANGED"
        
        const val EXTRA_WAKE_WORD_TRIGGERED = "extra_wake_word_triggered"
        const val EXTRA_INITIAL_COMMAND = "extra_initial_command"
        const val EXTRA_RMS_VALUE = "extra_rms_value"

        const val ACTION_SEND_REPLY_AUTOMATION = "com.example.service.action.SEND_REPLY_AUTOMATION"
        const val EXTRA_REPLY_PACKAGE = "extra_reply_package"
        const val EXTRA_REPLY_SENDER = "extra_reply_sender"
        const val EXTRA_REPLY_BODY = "extra_reply_body"
    }
}

// --- COMPOSE INCOMING DISPLAY COMPONENT ---

@Composable
fun IncomingOverlayUi(
    callerName: String,
    onReceive: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .border(
                width = 1.5.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFF00F2FE), Color(0xFF4FACFE))
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xEC0B0E14)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(45.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFF4FACFE), Color(0xFF00F2FE))
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "📞",
                            fontSize = 20.sp
                        )
                    }
                    Column {
                        Text(
                            text = callerName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Incoming Call...",
                            fontSize = 12.sp,
                            color = Color(0xFFA0A5B5)
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = Color(0xFF00F2FE),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .background(
                            Color(0x1F00F2FE),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "🎤 Speak Command",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF00F2FE)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onReject,
                    modifier = Modifier.weight(1f).height(45.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reject", color = Color.White, fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = onReceive,
                    modifier = Modifier.weight(1f).height(45.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF43A047)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Receive", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
