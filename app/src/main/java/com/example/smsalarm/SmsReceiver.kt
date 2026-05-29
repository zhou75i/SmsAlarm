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
            try {
                val sharedPrefs = context.getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
                val calendar = Calendar.getInstance()
                val prefIndex = when (val day = calendar.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.SUNDAY -> 6 else -> day - 2
                }
                if (!sharedPrefs.getBoolean("day_$prefIndex", true)) return 

                val activeKeywords = mutableListOf<String>()
                val jsonString = sharedPrefs.getString("keywords_json", "[]")
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.getBoolean("enabled")) {
                        activeKeywords.add(obj.getString("word"))
                    }
                }
                if (activeKeywords.isEmpty()) return

                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                for (sms in messages) {
                    val body = sms.displayMessageBody
                    if (activeKeywords.any { keyword -> body.contains(keyword) }) {
                        val alarmIntent = Intent(context, AlarmService::class.java).apply { action = "TRIGGER_ALARM" }
                        context.startService(alarmIntent)
                        break
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
