package com.example.smsalarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private var selectedRingtoneUri: String = ""
    private val PERMISSION_CODE = 101
    private val RINGTONE_PICKER_CODE = 102

    private lateinit var etKeyword: EditText
    private lateinit var tvRingtoneName: TextView
    private lateinit var checkBoxes: List<CheckBox>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)

        etKeyword = findViewById(R.id.etKeyword)
        tvRingtoneName = findViewById(R.id.tvRingtoneName)
        checkBoxes = listOf(
            findViewById(R.id.cbMon), findViewById(R.id.cbTue), findViewById(R.id.cbWed),
            findViewById(R.id.cbThu), findViewById(R.id.cbFri), findViewById(R.id.cbSat), findViewById(R.id.cbSun)
        )

        loadSavedConfig()

        findViewById<Button>(R.id.btnSelectRingtone).setOnClickListener { openRingtonePicker() }
        findViewById<Button>(R.id.btnSaveConfig).setOnClickListener { saveAndStartService() }
        findViewById<Button>(R.id.btnStopAlarm).setOnClickListener { stopAlarmAction() }

        findViewById<Button>(R.id.btnPresetEveryday).setOnClickListener { setDaysChecked(true, true, true, true, true, true, true) }
        findViewById<Button>(R.id.btnPresetWorkday).setOnClickListener { setDaysChecked(true, true, true, true, true, false, false) }
        findViewById<Button>(R.id.btnPresetWeekend).setOnClickListener { setDaysChecked(false, false, false, false, false, true, true) }

        // 渲染碎片化加密的版权信息
        setupCopyrightInfo()

        checkAndRequestPermissions()
    }

    private fun setupCopyrightInfo() {
        val tvCopyright = findViewById<TextView>(R.id.tvCopyright)
        try {
            // 碎片化
            val p1 = Base64.decode("5pys5bel5YW35p2l6Ieq77ya", Base64.DEFAULT)
            val p2 = Base64.decode("UWkgV3UK", Base64.DEFAULT)
            val p3 = Base64.decode("R2l0aHVi5byA5rqQ5Zyw5Z2A77ya", Base64.DEFAULT)
            val p4 = Base64.decode("aHR0cHM6Ly9naXRodWIuY29tL3pob3U3NWkvU21zQWxhcm0v", Base64.DEFAULT)

            val finalCopyrightText = String(p1) + String(p2) + String(p3) + String(p4)
            tvCopyright.text = finalCopyrightText
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadSavedConfig() {
        etKeyword.setText(sharedPrefs.getString("keyword", ""))
        selectedRingtoneUri = sharedPrefs.getString("ringtone_uri", "") ?: ""
        tvRingtoneName.text = if (selectedRingtoneUri.isEmpty()) "当前：系统默认闹钟" else "当前：已选择自定义铃声"

        for (i in checkBoxes.indices) {
            checkBoxes[i].isChecked = sharedPrefs.getBoolean("day_$i", true)
        }
    }

    private fun setDaysChecked(vararg states: Boolean) {
        for (i in checkBoxes.indices) { if (i < states.size) checkBoxes[i].isChecked = states[i] }
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择强提醒警报铃声")
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(selectedRingtoneUri))
        }
        startActivityForResult(intent, RINGTONE_PICKER_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RINGTONE_PICKER_CODE && resultCode == Activity.RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                selectedRingtoneUri = uri.toString()
                tvRingtoneName.text = "当前：已选择自定义铃声"
            }
        }
    }

    private fun saveAndStartService() {
        val keyword = etKeyword.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }

        val editor = sharedPrefs.edit()
        editor.putString("keyword", keyword)
        editor.putString("ringtone_uri", selectedRingtoneUri)
        for (i in checkBoxes.indices) {
            editor.putBoolean("day_$i", checkBoxes[i].isChecked)
        }
        editor.apply()

        val serviceIntent = Intent(this, AlarmService::class.java).apply { action = "START_MONITOR" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
        Toast.makeText(this, "配置成功，监控防护已在后台常驻！", Toast.LENGTH_LONG).show()
    }

    private fun stopAlarmAction() {
        val serviceIntent = Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" }
        startService(serviceIntent)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val pArray = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (pArray.isNotEmpty()) ActivityCompat.requestPermissions(this, pArray, PERMISSION_CODE)
    }
}