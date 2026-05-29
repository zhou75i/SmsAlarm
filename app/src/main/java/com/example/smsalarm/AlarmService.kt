package com.example.smsalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat

class AlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "SmsMonitorChannel"
    private val NOTIFICATION_ID = 8888

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_MONITOR" -> {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("智能短信强提醒运行中")
                    .setContentText("已应用最新配置，拦截系统休眠中...")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIFICATION_ID, notification)
            }
            "TRIGGER_ALARM" -> executeStrongAlarm()
            "STOP_ALARM" -> stopAlarmAndVibration()
        }
        return START_STICKY 
    }

    private fun executeStrongAlarm() {
        stopAlarmAndVibration() 

        // 1. 获取休眠唤醒锁，防止黑屏状态下CPU休眠导致不出声
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlarm::WakeLock")
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)

        val sharedPrefs = getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
        val ringtoneStr = sharedPrefs.getString("ringtone_uri", "") ?: ""
        val volumePercent = sharedPrefs.getInt("volume_percent", 100)
        
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmUri = if (ringtoneStr.isEmpty()) defaultUri else Uri.parse(ringtoneStr)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // 2. 根据用户设置的百分比，计算并强制设定系统闹钟通道音量
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val targetVolume = (maxVolume * (volumePercent / 100f)).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)

        // 3. 播放声音 (带兜底防崩溃逻辑)
        try {
            playAudio(alarmUri)
        } catch (e: Exception) {
            e.printStackTrace()
            // 如果用户选择的铃声因为杀后台失去了URI权限，立刻使用系统默认铃声兜底！
            try {
                playAudio(defaultUri)
            } catch (fallbackEx: Exception) {
                fallbackEx.printStackTrace()
            }
        }

        // 4. 持续震动
        val pattern = longArrayOf(0, 800, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun playAudio(uri: Uri) {
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@AlarmService, uri)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM) // 强力洞穿勿扰模式
                .build()
            setAudioAttributes(audioAttributes)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun stopAlarmAndVibration() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "短信强提醒保活服务", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAlarmAndVibration()
        super.onDestroy()
    }
}
