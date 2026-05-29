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
                    .setContentText("防护已生效，拦截系统休眠中...")
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

        // 关键修复：加入安全的唤醒锁逻辑 (依赖 Manifest 中的 WAKE_LOCK 权限)
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAlarm::WakeLock")
            wakeLock?.acquire(10 * 60 * 1000L /*10 mins*/)
        } catch (e: Exception) { e.printStackTrace() }

        val sharedPrefs = getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
        val ringtoneStr = sharedPrefs.getString("ringtone_uri", "") ?: ""
        val volumePercent = sharedPrefs.getInt("volume_percent", 100)
        
        var defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (defaultUri == null) defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        val alarmUri = if (ringtoneStr.isEmpty()) defaultUri else Uri.parse(ringtoneStr)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // 强行突破勿扰并修改音量
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            // 修复整数相除可能带来的 0 音量 Bug
            val targetVolume = (maxVolume * (volumePercent.toFloat() / 100f)).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)
        } catch (e: Exception) { e.printStackTrace() }

        // 多重兜底播放逻辑，宁可播放难听的系统音，也绝不能不出声
        try {
            playAudio(alarmUri)
        } catch (e: Exception) {
            try { playAudio(defaultUri) } catch (ex: Exception) { ex.printStackTrace() }
        }

        // 震动狂暴模式
        try {
            val pattern = longArrayOf(0, 800, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun playAudio(uri: Uri) {
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@AlarmService, uri)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM) // 核心：伪装成闹钟，无视静音和免打扰
                .build()
            setAudioAttributes(audioAttributes)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun stopAlarmAndVibration() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
            if (wakeLock?.isHeld == true) { wakeLock?.release() }
        } catch (e: Exception) { e.printStackTrace() }
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
