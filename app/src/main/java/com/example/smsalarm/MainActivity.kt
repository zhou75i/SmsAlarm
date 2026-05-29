package com.example.smsalarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private var selectedRingtoneUri: String = ""
    private val PERMISSION_CODE = 101
    private val RINGTONE_PICKER_CODE = 102

    private var isEditMode = false
    private lateinit var llKeywordContainer: LinearLayout
    private lateinit var checkBoxes: List<CheckBox>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
        llKeywordContainer = findViewById(R.id.llKeywordContainer)
        checkBoxes = listOf(
            findViewById(R.id.cbMon), findViewById(R.id.cbTue), findViewById(R.id.cbWed),
            findViewById(R.id.cbThu), findViewById(R.id.cbFri), findViewById(R.id.cbSat), findViewById(R.id.cbSun)
        )

        setupCopyrightInfo()
        loadSavedConfig()
        setupButtons()
        checkAndRequestPermissions()
        
        // 首次打开检测并引导关闭电池优化
        checkBatteryOptimization()
        
        // 每次完全打开软件，直接在后台刷活监控服务
        startMonitorService()
    }

    private fun setupButtons() {
        val btnEdit = findViewById<Button>(R.id.btnEdit)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnAddKeyword = findViewById<Button>(R.id.btnAddKeyword)

        btnEdit.setOnClickListener {
            isEditMode = true
            btnEdit.visibility = View.GONE
            btnSave.visibility = View.VISIBLE
            btnAddKeyword.visibility = View.VISIBLE
            refreshKeywordListUI()
        }

        btnSave.setOnClickListener {
            isEditMode = false
            btnEdit.visibility = View.VISIBLE
            btnSave.visibility = View.GONE
            btnAddKeyword.visibility = View.GONE
            saveAllConfig()
            refreshKeywordListUI()
        }

        btnAddKeyword.setOnClickListener {
            addKeywordRow("", true)
        }

        findViewById<Button>(R.id.btnEnableAll).setOnClickListener { setAllSwitches(true) }
        findViewById<Button>(R.id.btnDisableAll).setOnClickListener { setAllSwitches(false) }

        findViewById<Button>(R.id.btnSelectRingtone).setOnClickListener { openRingtonePicker() }
        findViewById<Button>(R.id.btnStopAlarm).setOnClickListener {
            startService(Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" })
            Toast.makeText(this, "警报已解除", Toast.LENGTH_SHORT).show()
        }
    }

    // 动态生成一行关键词控件
    private fun addKeywordRow(word: String, isEnabled: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) }
        }

        val etWord = EditText(this).apply {
            setText(word)
            hint = "输入关键词"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            this.isEnabled = isEditMode
        }

        val switch = Switch(this).apply {
            isChecked = isEnabled
            // 非编辑模式下也可以随时开关
            setOnCheckedChangeListener { _, _ -> if (!isEditMode) saveAllConfig() } 
        }

        val btnDelete = Button(this).apply {
            text = "❌"
            visibility = if (isEditMode) View.VISIBLE else View.GONE
            setOnClickListener { llKeywordContainer.removeView(row) }
        }

        row.addView(etWord)
        row.addView(switch)
        row.addView(btnDelete)
        llKeywordContainer.addView(row)
    }

    private fun refreshKeywordListUI() {
        for (i in 0 until llKeywordContainer.childCount) {
            val row = llKeywordContainer.getChildAt(i) as LinearLayout
            (row.getChildAt(0) as EditText).isEnabled = isEditMode
            row.getChildAt(2).visibility = if (isEditMode) View.VISIBLE else View.GONE
        }
    }

    private fun setAllSwitches(state: Boolean) {
        for (i in 0 until llKeywordContainer.childCount) {
            val row = llKeywordContainer.getChildAt(i) as LinearLayout
            (row.getChildAt(1) as Switch).isChecked = state
        }
        if (!isEditMode) saveAllConfig()
    }

    private fun loadSavedConfig() {
        llKeywordContainer.removeAllViews()
        val jsonString = sharedPrefs.getString("keywords_json", "[]")
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                addKeywordRow(obj.getString("word"), obj.getBoolean("enabled"))
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 如果为空，默认添加一个空行
        if (llKeywordContainer.childCount == 0) addKeywordRow("", true)

        selectedRingtoneUri = sharedPrefs.getString("ringtone_uri", "") ?: ""
        findViewById<TextView>(R.id.tvRingtoneName).text = if (selectedRingtoneUri.isEmpty()) "当前: 系统默认" else "当前: 已选定"

        for (i in checkBoxes.indices) { checkBoxes[i].isChecked = sharedPrefs.getBoolean("day_$i", true) }
    }

    private fun saveAllConfig() {
        val array = JSONArray()
        for (i in 0 until llKeywordContainer.childCount) {
            val row = llKeywordContainer.getChildAt(i) as LinearLayout
            val word = (row.getChildAt(0) as EditText).text.toString().trim()
            val isEnabled = (row.getChildAt(1) as Switch).isChecked
            if (word.isNotEmpty()) {
                val obj = JSONObject()
                obj.put("word", word)
                obj.put("enabled", isEnabled)
                array.put(obj)
            }
        }
        val editor = sharedPrefs.edit()
        editor.putString("keywords_json", array.toString())
        editor.putString("ringtone_uri", selectedRingtoneUri)
        for (i in checkBoxes.indices) { editor.putBoolean("day_$i", checkBoxes[i].isChecked) }
        editor.apply()
        
        startMonitorService()
        Toast.makeText(this, "配置已生效", Toast.LENGTH_SHORT).show()
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, AlarmService::class.java).apply { action = "START_MONITOR" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 核心防杀后台设置")
                    .setMessage("为了确保在息屏或半夜时仍能收到警报，请务必在下一步中将本应用设置为【无限制】使用电池！")
                    .setPositiveButton("立即去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun setupCopyrightInfo() {
        val tvCopyright = findViewById<TextView>(R.id.tvCopyright)
        try {
            val p1 = Base64.decode("5pys5bel5YW35p2l6Ieq77ya", Base64.DEFAULT)
            val p2 = Base64.decode("UWkgV3UK", Base64.DEFAULT)
            val p3 = Base64.decode("R2l0aHVi5byA5rqQ5Zyw5Z2A77ya", Base64.DEFAULT)
            val p4 = Base64.decode("aHR0cHM6Ly9naXRodWIuY29tL3pob3U3NWkvU21zQWxhcm0v", Base64.DEFAULT)
            tvCopyright.text = String(p1) + String(p2) + String(p3) + String(p4)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { permissions.add(Manifest.permission.POST_NOTIFICATIONS) }
        val pArray = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
        if (pArray.isNotEmpty()) ActivityCompat.requestPermissions(this, pArray, PERMISSION_CODE)
    }

    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择警报铃声")
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
                findViewById<TextView>(R.id.tvRingtoneName).text = "当前: 已选定"
                saveAllConfig()
            }
        }
    }
}
