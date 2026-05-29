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
import java.io.File

class AlarmService : Service() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 渠道改名，强迫系统重新授予最高优先级！
    private val CHANNEL_ID = "SmsMonitorChannel_V2"
    private val ALARM_CHANNEL_ID = "AlarmPopupChannel_V3"
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

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "SmsAlarm::WakeLock")
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) { e.printStackTrace() }

        triggerFullScreenPopup()

        val sharedPrefs = getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
        val ringtoneStr = sharedPrefs.getString("ringtone_uri", "") ?: ""
        val volumePercent = sharedPrefs.getInt("volume_percent", 100)
        
        var defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (defaultUri == null) defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        // 读取私有拷贝的铃声
        val alarmUri = if (ringtoneStr.startsWith("/")) {
            Uri.fromFile(File(ringtoneStr))
        } else if (ringtoneStr.isNotEmpty()) {
            Uri.parse(ringtoneStr)
        } else {
            defaultUri
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, (maxVolume * (volumePercent.toFloat() / 100f)).toInt(), 0)
        } catch (e: Exception) { e.printStackTrace() }

        try {
            playAudio(alarmUri)
        } catch (e: Exception) {
            try { playAudio(defaultUri) } catch (ex: Exception) { ex.printStackTrace() }
        }

        try {
            val pattern = longArrayOf(0, 800, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    private fun triggerFullScreenPopup() {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("is_alarm_triggered", true)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, fullScreenIntent, flags)

        // 核心：直接在通知栏外挂一个“停止”按钮！
        val stopIntent = Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" }
        val stopPendingIntent = PendingIntent.getService(this, 200, stopIntent, flags)

        val alarmNotif = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle("🚨 收到指定短信！")
            .setContentText("系统检测到强提醒关键词！")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX) // 强制提到最高层级
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "🔕 点击立刻停止警报", stopPendingIntent)
            .setAutoCancel(true)
            .build()
            
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(9999, alarmNotif)
    }

    private fun playAudio(uri: Uri) {
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@AlarmService, uri)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
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
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.cancel(9999) 
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val channel = NotificationChannel(CHANNEL_ID, "短信强提醒保活", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
            
            val alarmChannel = NotificationChannel(ALARM_CHANNEL_ID, "警报强制弹窗", NotificationManager.IMPORTANCE_HIGH)
            alarmChannel.setBypassDnd(true)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAlarmAndVibration()
        super.onDestroy()
    }
}
