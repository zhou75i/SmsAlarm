package com.example.smsalarm

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPrefs: SharedPreferences
    private var selectedRingtoneUri: String = ""
    private var currentVolumePercent: Int = 100
    private val PERMISSION_CODE = 101
    private val RINGTONE_PICKER_CODE = 102

    private var isEditMode = false
    private lateinit var llKeywordContainer: LinearLayout
    private lateinit var checkBoxes: List<CheckBox>
    private lateinit var sbVolume: SeekBar
    private lateinit var tvVolumePercent: TextView
    private lateinit var btnTestRingtone: Button
    private var previewPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 启动图渐隐消失动画 (延迟 1 秒后开始淡出)
        val splashImg = findViewById<ImageView>(R.id.splashImage)
        splashImg.animate().alpha(0f).setDuration(800).setStartDelay(1000).withEndAction {
            splashImg.visibility = View.GONE
        }.start()

        sharedPrefs = getSharedPreferences("SmsAlarmConfig", Context.MODE_PRIVATE)
        llKeywordContainer = findViewById(R.id.llKeywordContainer)
        sbVolume = findViewById(R.id.sbVolume)
        tvVolumePercent = findViewById(R.id.tvVolumePercent)
        btnTestRingtone = findViewById(R.id.btnTestRingtone)
        checkBoxes = listOf(
            findViewById(R.id.cbMon), findViewById(R.id.cbTue), findViewById(R.id.cbWed),
            findViewById(R.id.cbThu), findViewById(R.id.cbFri), findViewById(R.id.cbSat), findViewById(R.id.cbSun)
        )

        setupCopyrightInfo()
        loadSavedConfig()
        setupButtons()
        setupVolumeSlider()
        
        checkAndRequestPermissions()
        startMonitorService()
        
        if (!sharedPrefs.getBoolean("has_run_first_wizard", false)) {
            showAutoStartGuide()
        }
        
        checkAlarmTrigger(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        checkAlarmTrigger(intent)
    }

    private fun checkAlarmTrigger(intent: Intent?) {
        if (intent?.getBooleanExtra("is_alarm_triggered", false) == true) {
            AlertDialog.Builder(this)
                .setTitle("🚨 警报触发！")
                .setMessage("已检测到指定的强提醒短信！")
                .setPositiveButton("立即停止响铃") { _, _ ->
                    startService(Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" })
                    Toast.makeText(this, "警报已解除", Toast.LENGTH_SHORT).show()
                }
                .setCancelable(false)
                .show()
            intent.removeExtra("is_alarm_triggered")
        }
    }

    private fun setupVolumeSlider() {
        sbVolume.progress = currentVolumePercent
        tvVolumePercent.text = "${currentVolumePercent}%"
        sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val finalProgress = if (progress < 10) 10 else progress
                tvVolumePercent.text = "${finalProgress}%"
                currentVolumePercent = finalProgress
                if (previewPlayer?.isPlaying == true) {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * (finalProgress / 100f)).toInt(), 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { saveAllConfig() }
        })
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

        btnAddKeyword.setOnClickListener { addKeywordRow("", true) }
        findViewById<Button>(R.id.btnEnableAll).setOnClickListener { setAllSwitches(true) }
        findViewById<Button>(R.id.btnDisableAll).setOnClickListener { setAllSwitches(false) }
        findViewById<Button>(R.id.btnSelectRingtone).setOnClickListener { openRingtonePicker() }
        
        btnTestRingtone.setOnClickListener {
            if (previewPlayer?.isPlaying == true) {
                stopPreview()
            } else {
                startPreview()
            }
        }

        findViewById<Button>(R.id.btnStopAlarm).setOnClickListener {
            startService(Intent(this, AlarmService::class.java).apply { action = "STOP_ALARM" })
            stopPreview()
            Toast.makeText(this, "警报已手动解除", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPreview() {
        saveAllConfig()
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * (currentVolumePercent / 100f)).toInt(), 0)

            // 读取刚才拷贝进私有目录的铃声
            val targetUri = if (selectedRingtoneUri.startsWith("/")) {
                Uri.fromFile(File(selectedRingtoneUri))
            } else if (selectedRingtoneUri.isNotEmpty()) {
                Uri.parse(selectedRingtoneUri)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            }

            previewPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, targetUri)
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                isLooping = true
                prepare()
                start()
            }
            btnTestRingtone.text = "⏹停止"
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "铃声读取失败，请重新选择", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        previewPlayer?.stop()
        previewPlayer?.release()
        previewPlayer = null
        btnTestRingtone.text = "🎵试听"
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

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
        if (llKeywordContainer.childCount == 0) addKeywordRow("", true)

        selectedRingtoneUri = sharedPrefs.getString("ringtone_uri", "") ?: ""
        currentVolumePercent = sharedPrefs.getInt("volume_percent", 100)
        findViewById<TextView>(R.id.tvRingtoneName).text = if (selectedRingtoneUri.isEmpty()) "当前: 默认" else "当前: 已私有化保存"
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
        editor.putInt("volume_percent", currentVolumePercent)
        for (i in checkBoxes.indices) { editor.putBoolean("day_$i", checkBoxes[i].isChecked) }
        editor.apply()
        startMonitorService()
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, AlarmService::class.java).apply { action = "START_MONITOR" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { startForegroundService(serviceIntent) } catch (e: Exception) {}
        } else {
            startService(serviceIntent)
        }
    }
    
    private fun showAutoStartGuide() {
        AlertDialog.Builder(this)
            .setTitle("🚀 第1步：开启自启动 (极其重要)")
            .setMessage("为了防止后台被杀收不到警报，请在稍后的系统中允许本应用【自启动】及【后台运行】。")
            .setPositiveButton("立即去设置") { _, _ ->
                jumpToAutoStartSettings()
                showBatteryOptimizationGuide()
            }
            .setCancelable(false)
            .show()
    }

    private fun jumpToAutoStartSettings() {
        try {
            val intent = Intent()
            val manufacturer = Build.MANUFACTURER.lowercase()
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                    intent.component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    intent.component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                    intent.component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                }
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    intent.component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                }
                else -> {
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = Uri.parse("package:$packageName")
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun showBatteryOptimizationGuide() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("🔋 第2步：解除电池优化")
                    .setMessage("最后一步！请将本应用的电池策略设置为【无限制】或【不优化】。")
                    .setPositiveButton("立即去设置") { _, _ ->
                        sharedPrefs.edit().putBoolean("has_run_first_wizard", true).apply()
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
                    }
                    .setCancelable(false).show()
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
        } catch (e: Exception) {}
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
        }
        startActivityForResult(intent, RINGTONE_PICKER_CODE)
    }

    // 核心修复：将外部铃声强行拷贝进 App 私有目录，永不失效！
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RINGTONE_PICKER_CODE && resultCode == Activity.RESULT_OK) {
            val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        val outFile = File(filesDir, "custom_ringtone.audio")
                        val outputStream = FileOutputStream(outFile)
                        inputStream.copyTo(outputStream)
                        inputStream.close()
                        outputStream.close()
                        selectedRingtoneUri = outFile.absolutePath // 保存私有文件绝对路径
                        findViewById<TextView>(R.id.tvRingtoneName).text = "当前: 已私有化保存"
                        saveAllConfig()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "铃声保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
