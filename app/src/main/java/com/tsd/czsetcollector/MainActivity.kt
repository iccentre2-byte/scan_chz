package com.tsd.czsetcollector

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import com.tsd.czsetcollector.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OrganizationProfile(
    val inn: String,
    val token: String,
    val pg: String = "grocery",
    val title: String = "ИНН: $inn ($pg)"
) {
    override fun toString(): String = title
}

data class SetUnit(
    @SerializedName("set_code") val setCode: String,
    @SerializedName("sGTIN") val sgtinList: List<String>
)

data class ProductDocumentSet(
    @SerializedName("action_id") val actionId: Int = 30,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("inn") val inn: String,
    @SerializedName("set_units") val setUnits: List<SetUnit>
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val profilesList = mutableListOf<OrganizationProfile>()
    private var selectedProfileIndex = -1

    private var downloadId: Long = -1L

    private val pgPresets = listOf(
        "Бакалея / Сухарики / Снеки (grocery)",
        "Соусы / Майонезы (sauces)",
        "Консервированная продукция (canned_products)",
        "Растительные масла (vegetable_oil)",
        "Кондитерские изделия (sweets)",
        "Табачная продукция (tobacco)",
        "Свой код..."
    )

    private val pgCodes = listOf(
        "grocery",
        "sauces",
        "canned_products",
        "vegetable_oil",
        "sweets",
        "tobacco",
        ""
    )

    private var currentSetCode: String? = null
    private val currentChildrenCodes = mutableListOf<String>()
    private val completedSets = mutableListOf<SetUnit>()

    private val logFile: File
        get() = File(getExternalFilesDir(null), "tsd_cz_logs.txt")

    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            val barcode = intent.getStringExtra("m3scannerdata")
                ?: intent.getStringExtra("scannerdata")
                ?: intent.getStringExtra("barcode_string")
                ?: intent.getStringExtra("data")
                ?: intent.getStringExtra("scan_data")
                ?: intent.getStringExtra("extra_barcode_data")
                ?: intent.getByteArrayExtra("barcode")?.let { String(it) }

            barcode?.trim()?.let { 
                if (it.isNotEmpty()) {
                    processScannedBarcode(it) 
                }
            }
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadId && downloadId != -1L) {
                checkDownloadStatusAndInstall()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()
        binding.tvLog.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_UP) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        prefs = getSharedPreferences("cz_multi_profiles", Context.MODE_PRIVATE)

        setupPgSpinner()
        loadProfilesFromStorage()
        setupListeners()
        setupKeyAndTextListeners()
        updateUi()

        binding.tvAppVersion.text = "v1.2.1"
        log("Запуск v1.2.1 (Потоковый режим сканирования)")
    }

    override fun onResume() {
        super.onResume()
        val scannerFilter = IntentFilter().apply {
            addAction("com.android.server.scannerservice.broadcast")
            addAction("com.m3.scan.action.SCANNER_OUTPUT")
            addAction("android.intent.ACTION_DECODE_DATA")
            addAction("com.scan.output")
            addAction("com.tsd.czsetcollector.SCAN_ACTION")
        }
        ContextCompat.registerReceiver(
            this,
            scannerReceiver,
            scannerFilter,
            ContextCompat.RECEIVER_EXPORTED
        )

        val downloadFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            downloadFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        setContinuousScanMode(false)
        stopSoftwareScanTrigger()
        try { unregisterReceiver(scannerReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(downloadReceiver) } catch (e: Exception) {}
    }

    private fun setupPgSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pgPresets)
        binding.spinnerPg.adapter = adapter
        binding.spinnerPg.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val code = pgCodes[position]
                if (code.isNotEmpty()) {
                    binding.etProductGroup.setText(code)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Включение / выключение непрерывного потокового режима луча на ТСД M3 / стандартных сервисах
     */
    private fun setContinuousScanMode(enable: Boolean) {
        // 1. Команда утилите M3 Scanner
        val m3Intent = Intent("com.m3.scan.action.SCANNER_SETTING_CHANGE").apply {
            putExtra("setting_name", "continuous_scan")
            putExtra("setting_value", if (enable) 1 else 0)
        }
        sendBroadcast(m3Intent)

        // 2. Универсальная команда режима презентации/потока
        val directIntent = Intent("com.m3.scan.action.CONTINUOUS_SCAN").apply {
            putExtra("enable", enable)
        }
        sendBroadcast(directIntent)

        // 3. Управление аппаратным триггером
        if (enable) {
            startSoftwareScanTrigger()
        } else {
            stopSoftwareScanTrigger()
        }
    }

    /**
     * Программное зажигание лазерного/фото луча
     */
    private fun startSoftwareScanTrigger() {
        val scanTriggerIntent = Intent("com.android.server.scannerservice.m3plugin.start")
        sendBroadcast(scanTriggerIntent)

        val altScanTrigger = Intent("android.intent.action.M3SCANNER_BUTTON_DOWN")
        sendBroadcast(altScanTrigger)
    }

    /**
     * Программное гашение луча
     */
    private fun stopSoftwareScanTrigger() {
        val scanStopIntent = Intent("com.android.server.scannerservice.m3plugin.stop")
        sendBroadcast(scanStopIntent)

        val altScanStop = Intent("android.intent.action.M3SCANNER_BUTTON_UP")
        sendBroadcast(altScanStop)
    }

    /**
     * Очистка кода от служебных префиксов и спецсимволов GS1 FNC1
     */
    private fun cleanCode(rawCode: String): String {
        var code = rawCode.trim()
        
        if (code.startsWith("]d2") || code.startsWith("]e0") || code.startsWith("]Q3") || code.startsWith("]C1")) {
            code = code.substring(3)
        }
        
        val gsIndex = code.indexOf('\u001d')
        if (gsIndex != -1) {
            code = code.substring(0, gsIndex)
        }

        val key91Index = code.indexOf("91")
        if (key91Index in 21..35) {
            code = code.substring(0, key91Index)
        }

        return code.trim()
    }

    /**
     * Валидация: проверяет, является ли код маркировкой Честного ЗНАКа (DataMatrix / GS1)
     */
    private fun isChestnyZnakCode(code: String): Boolean {
        // Обычный линейный штрихкод EAN-13/EAN-8 отсекаем
        if (code.matches(Regex("^\\d{8}$")) || code.matches(Regex("^\\d{12,14}$"))) {
            return false
        }
        // Честный Знак обычно содержит GTIN (01) + серийный номер (21) либо имеет длину от 20+ символов
        if (code.startsWith("01") && code.contains("21") && code.length >= 20) {
            return true
        }
        // Допускаются наборы и агрегаты (SSCC / КИГУ / КИН), длина которых > 14 символов
        return code.length >= 16
    }

    private fun setupKeyAndTextListeners() {
        binding.etCountPerSet.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val input = s.toString().trim()
                if (input.length > 10) {
                    binding.etCountPerSet.removeTextChangedListener(this)
                    binding.etCountPerSet.setText("6")
                    binding.etCountPerSet.addTextChangedListener(this)
                    
                    processScannedBarcode(input)
                }
            }
        })
    }

    private fun loadProfilesFromStorage() {
        profilesList.clear()
        val loadedJson = prefs.getString("profiles_json", null)

        if (!loadedJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<OrganizationProfile>>() {}.type
                val savedList: List<OrganizationProfile> = gson.fromJson(loadedJson, type)
                profilesList.addAll(savedList)
            } catch (e: Exception) {
                log("⚠️ Ошибка JSON профилей")
            }
        }

        if (profilesList.isEmpty()) {
            profilesList.add(OrganizationProfile("7700000000", "", "grocery", "Основной профиль"))
        }

        updateProfilesSpinner()
    }

    private fun saveProfilesToStorage() {
        val json = gson.toJson(profilesList)
        prefs.edit().putString("profiles_json", json).apply()
        log("💾 Профиль сохранен")
    }

    private fun updateProfilesSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profilesList)
        binding.spinnerProfiles.adapter = adapter

        val lastIndex = prefs.getInt("selected_profile_index", 0)
        if (lastIndex < profilesList.size) {
            binding.spinnerProfiles.setSelection(lastIndex)
            applyProfileToInputs(profilesList[lastIndex])
        }
    }

    private fun applyProfileToInputs(profile: OrganizationProfile) {
        binding.etInn.setText(profile.inn)
        binding.etToken.setText(profile.token)
        val currentPg = if (profile.pg.isEmpty()) "grocery" else profile.pg
        binding.etProductGroup.setText(currentPg)

        val presetIndex = pgCodes.indexOf(currentPg)
        if (presetIndex >= 0) {
            binding.spinnerPg.setSelection(presetIndex)
        } else {
            binding.spinnerPg.setSelection(pgPresets.size - 1)
        }
    }

    private fun setupListeners() {
        binding.spinnerProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedProfileIndex = position
                prefs.edit().putInt("selected_profile_index", position).apply()
                applyProfileToInputs(profilesList[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnSaveProfile.setOnClickListener {
            val inn = binding.etInn.text.toString().trim()
            val token = binding.etToken.text.toString().trim()
            val pg = binding.etProductGroup.text.toString().trim().ifEmpty { "grocery" }

            if (inn.isEmpty()) {
                Toast.makeText(this, "Введите ИНН!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val existingIndex = profilesList.indexOfFirst { it.inn == inn }
            val newProfile = OrganizationProfile(inn, token, pg)

            if (existingIndex >= 0) {
                profilesList[existingIndex] = newProfile
                Toast.makeText(this, "Профиль $inn обновлен", Toast.LENGTH_SHORT).show()
            } else {
                profilesList.add(newProfile)
                Toast.makeText(this, "Профиль $inn сохранен", Toast.LENGTH_SHORT).show()
            }

            saveProfilesToStorage()
            updateProfilesSpinner()
        }

        binding.btnDeleteProfile.setOnClickListener {
            if (profilesList.size <= 1) {
                Toast.makeText(this, "Нельзя удалить единственный профиль!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (selectedProfileIndex in profilesList.indices) {
                val removed = profilesList.removeAt(selectedProfileIndex)
                saveProfilesToStorage()
                updateProfilesSpinner()
                Toast.makeText(this, "Профиль ${removed.inn} удален", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResetCurrentSet.setOnClickListener {
            if (currentSetCode != null || currentChildrenCodes.isNotEmpty()) {
                currentSetCode = null
                currentChildrenCodes.clear()
                setContinuousScanMode(false)
                stopSoftwareScanTrigger()
                updateUi()
                log("⚠️ Набор сброшен оператором")
                Toast.makeText(this, "Набор сброшен.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCheckUpdate.setOnClickListener {
            startApkDownload()
        }

        binding.btnShareLog.setOnClickListener {
            shareLogFile()
        }

        binding.btnSendDraft.setOnClickListener {
            setContinuousScanMode(false)
            stopSoftwareScanTrigger()
            exportJsonDocument()
        }
    }

    private fun exportJsonDocument() {
        val inn = binding.etInn.text.toString().trim()
        val pg = binding.etProductGroup.text.toString().trim().ifEmpty { "grocery" }

        if (inn.isEmpty()) {
            Toast.makeText(this, "Введите ИНН!", Toast.LENGTH_SHORT).show()
            return
        }

        val sendUnits = ArrayList(completedSets)
        if (currentSetCode != null && currentChildrenCodes.isNotEmpty()) {
            sendUnits.add(SetUnit(currentSetCode!!, ArrayList(currentChildrenCodes)))
        }

        if (sendUnits.isEmpty()) {
            Toast.makeText(this, "Нет наборов для выгрузки!", Toast.LENGTH_SHORT).show()
            return
        }

        val actionId = if (pg == "tobacco" || pg == "otp") 20 else 30
        val docStructure = ProductDocumentSet(
            actionId = actionId,
            inn = inn,
            setUnits = sendUnits
        )

        val rawJsonDoc = gson.toJson(docStructure)
        
        try {
            val fileName = "cz_set_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"
            val file = File(getExternalFilesDir(null), fileName)
            file.writeText(rawJsonDoc, Charsets.UTF_8)

            log("📄 JSON документ сохранен: ${file.name}")

            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Документ Наборов Честный ЗНАК ($pg)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Отправить JSON документ..."))

            completedSets.clear()
            currentSetCode = null
            currentChildrenCodes.clear()
            updateUi()

        } catch (e: Exception) {
            log("❌ Ошибка сохранения JSON: ${e.message}")
            Toast.makeText(this, "Ошибка сохранения файла", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogFile() {
        try {
            if (!logFile.exists() || logFile.length() == 0L) {
                Toast.makeText(this, "Лог пока пуст", Toast.LENGTH_SHORT).show()
                return
            }

            val logUri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", logFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, logUri)
                putExtra(Intent.EXTRA_SUBJECT, "Лог работы ТСД Честный ЗНАК")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Отправить лог через..."))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка отправки лога: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startApkDownload() {
        val apkUrl = "https://raw.githubusercontent.com/iccentre2-byte/scan_chz/main/app-release.apk"
        log("🔄 Старт скачивания: $apkUrl")
        Toast.makeText(this, "Загрузка обновления...", Toast.LENGTH_SHORT).show()

        try {
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-update.apk")
            if (file.exists()) file.delete()

            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Обновление ЧЗ Наборы")
                .setDescription("Загрузка новой версии...")
                .setDestinationUri(Uri.fromFile(file))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
        } catch (e: Exception) {
            log("❌ Ошибка запуска скачивания: ${e.message}")
            Toast.makeText(this, "Ошибка скачивания: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkDownloadStatusAndInstall() {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)

        if (cursor != null && cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

            if (statusIndex != -1) {
                val status = cursor.getInt(statusIndex)
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    log("✅ Скачивание успешно! Запуск установки...")
                    installDownloadedApk()
                } else if (status == DownloadManager.STATUS_FAILED) {
                    val reason = if (reasonIndex != -1) cursor.getInt(reasonIndex) else -1
                    log("❌ Ошибка скачивания (Код $reason)")
                    Toast.makeText(this, "Не удалось скачать APK (Код $reason)", Toast.LENGTH_LONG).show()
                }
            }
            cursor.close()
        } else {
            log("❌ Загрузка не найдена")
        }
    }

    private fun installDownloadedApk() {
        try {
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "app-update.apk")
            if (!file.exists() || file.length() == 0L) {
                log("❌ Файл обновления пуст или отсутствует!")
                Toast.makeText(this, "Файл обновления не найден", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, "$packageName.provider", file)
            } else {
                Uri.fromFile(file)
            }

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            log("❌ Ошибка запуска установки: ${e.message}")
        }
    }

    /**
     * Обработка скан-события с фильтрацией Честного ЗНАКа и удержанием потока
     */
    private fun processScannedBarcode(rawBarcode: String) {
        val barcode = cleanCode(rawBarcode)
        val targetCount = binding.etCountPerSet.text.toString().toIntOrNull() ?: 6

        // Проверка: это Честный ЗНАК или посторонний штрихкод
        if (!isChestnyZnakCode(barcode)) {
            log("⚠️ Пропуск: не Честный Знак ($barcode)")
            Toast.makeText(this, "Нужен код Честного ЗНАКа!", Toast.LENGTH_SHORT).show()
            // Если набор открыт, продолжаем держать луч включенным
            if (currentSetCode != null) {
                mainHandler.postDelayed({ startSoftwareScanTrigger() }, 150)
            }
            return
        }

        log("Скан ЧЗ: $barcode")

        if (currentSetCode == null) {
            // Открытие нового набора кодом набора
            currentSetCode = barcode
            currentChildrenCodes.clear()
            
            // Включаем непрерывный луч для потокового сканирования пачек
            setContinuousScanMode(true)
            log("📦 НАБОР ОТКРЫТ: $barcode")
            Toast.makeText(this, "Набор открыт! Сканируйте пачки подряд", Toast.LENGTH_SHORT).show()
        } else {
            // Проверка на сканирование того же кода набора
            if (barcode == currentSetCode) {
                log("⚠️ Сосканирован код НАБОРА (уже открыт)!")
                mainHandler.postDelayed({ startSoftwareScanTrigger() }, 200)
                return
            }

            // Проверка на дубликат пачки в текущем наборе
            if (currentChildrenCodes.contains(barcode)) {
                log("⚠️ Дубликат пачки в наборе!")
                Toast.makeText(this, "Пачка уже в наборе!", Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({ startSoftwareScanTrigger() }, 200)
                return
            }

            // Добавление пачки в набор
            currentChildrenCodes.add(barcode)
            log("-> Пачка (${currentChildrenCodes.size}/$targetCount): $barcode")

            if (currentChildrenCodes.size >= targetCount) {
                // Набор укомплектован
                completedSets.add(SetUnit(currentSetCode!!, ArrayList(currentChildrenCodes)))
                
                // Гасим луч сканера
                setContinuousScanMode(false)
                stopSoftwareScanTrigger()

                log("✅ НАБОР УКОМПЛЕКТОВАН (${completedSets.size} шт)")
                Toast.makeText(this, "Набор закрыт! Отсканируйте следующий НАБОР", Toast.LENGTH_SHORT).show()
                
                currentSetCode = null
                currentChildrenCodes.clear()
            } else {
                // Набор ещё не полон: сразу перезапускаем луч для следующей пачки
                mainHandler.postDelayed({
                    startSoftwareScanTrigger()
                }, 100)
            }
        }
        updateUi()
    }

    private fun updateUi() {
        val targetCount = binding.etCountPerSet.text.toString().toIntOrNull() ?: 6

        if (currentSetCode == null) {
            binding.tvScanState.text = "СТАТУС: Ожидание сканирования НАБОРА"
            binding.tvScanState.setBackgroundColor(0xFFEFF6FF.toInt())
            binding.tvCurrentSet.text = "Текущий набор: НЕ ВЫБРАН"
            binding.tvProgress.text = "Пачек в наборе: 0 / $targetCount"
        } else {
            binding.tvScanState.text = "🔥 ПОТОКОВЫЙ РЕЖИМ (Пачка ${currentChildrenCodes.size + 1} из $targetCount)"
            binding.tvScanState.setBackgroundColor(0xFFFEF3C7.toInt())
            binding.tvCurrentSet.text = "Текущий набор: $currentSetCode"
            binding.tvProgress.text = "Пачек в наборе: ${currentChildrenCodes.size} / $targetCount"
        }

        binding.tvTotalCompletedSets.text = "Закрытых наборов к отправке: ${completedSets.size}"
    }

    @Synchronized
    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $message\n"
        
        try {
            logFile.appendText(entry)
        } catch (e: Exception) {}

        runOnUiThread {
            val currentText = binding.tvLog.text.toString()
            binding.tvLog.text = "$entry$currentText"
        }
    }
}
