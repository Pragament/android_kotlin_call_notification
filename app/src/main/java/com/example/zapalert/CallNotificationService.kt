package com.example.zapalert

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.io.IOException

class CallNotificationService : Service() {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var sharedPreferences: android.content.SharedPreferences
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusGranted = false

    companion object {
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "call_channel"
        private const val RINGTONE_DURATION_MS = 30000L
        private const val TAG = "CallNotification"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            createNotificationChannel()
            startForegroundService()
            setupPhoneListener()
            acquireWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "Service failed to start", e)
            stopSelf()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName::WakeLock"
            )
            wakeLock?.acquire(RINGTONE_DURATION_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakelock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call notifications"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = buildNotification(
            "Call Notifier Active",
            "Monitoring for incoming calls"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun setupPhoneListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            telephonyManager.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallStateChange(state, phoneNumber)
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup listener", e)
            stopSelf()
        }
    }

    private fun handleCallStateChange(state: Int, phoneNumber: String?) {
        try {
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    Log.d(TAG, "Incoming call: $phoneNumber")
                    stopRingtone()
                    stopVibration()
                    Handler(Looper.getMainLooper()).postDelayed({
                        phoneNumber?.let { handleIncomingCall(it) }
                    }, 300)
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    stopRingtone()
                    stopVibration()
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    stopRingtone()
                    stopVibration()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in call state", e)
        }
    }

    private fun handleIncomingCall(phoneNumber: String) {
        Log.d(TAG, "Handling incoming call from $phoneNumber")
        try {
            val blockedCountryCodes = sharedPreferences.getStringSet("blocked_country_codes", emptySet()) ?: emptySet()

            if (blockedCountryCodes.none { phoneNumber.startsWith(it) }) {
                Log.d(TAG, "Call not blocked, showing notification")
                showCallNotification(phoneNumber)
                ensureVolumeIsUp()
                playRingtone()
                vibrate()
            } else {
                Log.d(TAG, "Call blocked by country code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle call", e)
        }
    }

    private fun ensureVolumeIsUp() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }

    private fun playRingtone() {
        stopRingtone()

        if (!requestAudioFocus()) {
            Log.e(TAG, "Audio focus denied")
            Handler(Looper.getMainLooper()).postDelayed({
                if (!audioFocusGranted) {
                    playRingtone()
                }
            }, 500)
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    mp?.release()
                    playFallbackSound()
                    true
                }

                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()
                )

                try {
                    val selectedRingtone = sharedPreferences.getInt("selected_ringtone",
                        R.raw.alert)
                    val afd = resources.openRawResourceFd(selectedRingtone)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    afd.close()

                    prepareAsync()
                    setOnPreparedListener { mp ->
                        if (audioFocusGranted) {
                            mp.start()
                            Log.d(TAG, "Playing ringtone")
                            Handler(Looper.getMainLooper()).postDelayed(
                                { stopRingtone() },
                                RINGTONE_DURATION_MS
                            )
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error setting data source", e)
                    playFallbackSound()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating MediaPlayer", e)
            playFallbackSound()
        }
    }

    private fun playFallbackSound() {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )

                val afd = resources.openRawResourceFd(R.raw.alert)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()

                prepare()
                if (audioFocusGranted) {
                    start()
                    Log.d(TAG, "Playing fallback sound")
                    Handler(Looper.getMainLooper()).postDelayed(
                        { stopRingtone() },
                        RINGTONE_DURATION_MS
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing fallback sound", e)
            playSystemDefaultSound()
        }
    }

    private fun playSystemDefaultSound() {
        try {
            val defaultRingtone = RingtoneManager.getRingtone(
                applicationContext,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            )
            defaultRingtone.play()
            Handler(Looper.getMainLooper()).postDelayed(
                { defaultRingtone.stop() },
                RINGTONE_DURATION_MS
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play even default ringtone", e)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_ALARM,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return audioFocusGranted
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusGranted = false
                stopRingtone()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioFocusGranted = false
                mediaPlayer?.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusGranted = true
                mediaPlayer?.start()
            }
        }
    }

    private fun showCallNotification(phoneNumber: String) {
        try {
            val fullScreenIntent = Intent(this, MainActivity::class.java).let {
                PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Incoming Call")
                .setContentText("From: $phoneNumber")
                .setSmallIcon(R.drawable.ic_call)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenIntent, true)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(phoneNumber.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }

    private fun vibrate() {
        try {
            if (sharedPreferences.getBoolean("enable_vibration", true)) {
                vibrator?.let { vibrator ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 500, 200, 500),
                                0
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(500)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }
    }

    private fun stopRingtone() {
        try {
            mediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                    }
                    player.reset()
                    player.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping media player", e)
                }
            }
            mediaPlayer = null

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(audioFocusChangeListener)
            audioFocusGranted = false

            Log.d(TAG, "Ringtone fully stopped and resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error in stopRingtone", e)
        }
    }

    override fun onDestroy() {
        try {
            stopRingtone()
            stopVibration()
            telephonyManager.listen(null, PhoneStateListener.LISTEN_NONE)
            wakeLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}