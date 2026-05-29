package com.example.smsalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONArray
import java.util.Calendar

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val sharedPrefs = context.getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
            
            // 1. 周期过滤
            val calendar = Calendar.getInstance()
            val prefIndex = when (val day = calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> 6 else -> day - 2
            }
            if (!sharedPrefs.getBoolean("day_$prefIndex", true)) return 

            // 2. 解析 JSON 配置，提取处于"开启"状态的关键词
            val activeKeywords = mutableListOf<String>()
            val jsonString = sharedPrefs.getString("keywords_json", "[]")
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.getBoolean("enabled")) {
                        activeKeywords.add(obj.getString("word"))
                    }
                }
            } catch (e: Exception) { return }

            if (activeKeywords.isEmpty()) return

            // 3. 多关键词联合判定
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val body = sms.displayMessageBody
                val isMatch = activeKeywords.any { keyword -> body.contains(keyword) }
                
                if (isMatch) {
                    val alarmIntent = Intent(context, AlarmService::class.java).apply {
                        action = "TRIGGER_ALARM"
                    }
                    context.startService(alarmIntent)
                    break
                }
            }
        }
    }
}
