package com.example.smsalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.Calendar

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val sharedPrefs = context.getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
            val targetKeywordString = sharedPrefs.getString("keyword", "") ?: ""

            if (targetKeywordString.isEmpty()) return

            // 核心升级：支持中英文逗号分隔的多个关键词
            val keywords = targetKeywordString.split(",", "，")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (keywords.isEmpty()) return

            // 1. 周期时间校验 (星期过滤)
            val calendar = Calendar.getInstance()
            val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            val prefIndex = when (currentDayOfWeek) {
                Calendar.SUNDAY -> 6
                else -> currentDayOfWeek - 2
            }

            val isDayEnabled = sharedPrefs.getBoolean("day_$prefIndex", true)
            if (!isDayEnabled) return // 如果今天未勾选生效，直接拦截

            // 2. 短信文本内容匹配 (多词匹配)
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val body = sms.displayMessageBody
                
                // 只要短信内容包含 keywords 数组里的任意一个词，就触发 (any)
                val isMatch = keywords.any { keyword -> body.contains(keyword) }
                
                if (isMatch) {
                    val alarmIntent = Intent(context, AlarmService::class.java).apply {
                        action = "TRIGGER_ALARM"
                    }
                    context.startService(alarmIntent)
                    break // 响铃一次就够了，跳出循环
                }
            }
        }
    }
}