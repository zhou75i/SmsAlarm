package com.example.smsalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat

class AlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val CHANNEL_ID = "SmsMonitorChannel"
    private val NOTIFICATION_ID = 8888

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_MONITOR" -> {
                // 变成前台服务，实现系统底层级保活
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("智能短信强提醒正在运行")
                    .setContentText("短信策略监控防护中，请保持此常驻状态")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIFICATION_ID, notification)
            }
            "TRIGGER_ALARM" -> {
                executeStrongAlarm()
            }
            "STOP_ALARM" -> {
                stopAlarmAndVibration()
            }
        }
        return START_STICKY // 被杀后自动重启
    }

    private fun executeStrongAlarm() {
        stopAlarmAndVibration() // 先清理可能正在响的警报

        val sharedPrefs = getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
        val ringtoneStr = sharedPrefs.getString("ringtone_uri", "") ?: ""
        
        val alarmUri = if (ringtoneStr.isEmpty()) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        } else {
            Uri.parse(ringtoneStr)
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // 强制把闹钟通道音量直接拉满
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmService, alarmUri)
                setAudioStreamType(AudioManager.STREAM_ALARM) // 核心：无视静音的闹钟流
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 持续急促震动
        val pattern = longArrayOf(0, 800, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopAlarmAndVibration() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "短信强提醒保活服务", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAlarmAndVibration()
        super.onDestroy()
    }
}