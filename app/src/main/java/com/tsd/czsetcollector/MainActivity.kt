package com.tsd.czsetcollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.annotations.SerializedName
import com.tsd.czsetcollector.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class OrganizationProfile(
    val inn: String,
    val token: String,
    val title: String = "ИНН: $inn"
) {
    override fun toString(): String = title
}

data class SetUnit(
    @SerializedName("set_code") val setCode: String,
    @SerializedName("sGTIN") val sgtinList: List<String>
)

data class ProductDocumentSet(
    @SerializedName("action_id") val actionId: Int = 20,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("inn") val inn: String,
    @SerializedName("set_units") val setUnits: List<SetUnit>
)

data class SetDocumentRequest(
    @SerializedName("document_format") val documentFormat: String = "MANUAL",
    @SerializedName("product_document") val productDocument: ProductDocumentSet
)

data class CzApiResponse(
    @SerializedName("number") val documentId: String?,
    @SerializedName("error_message") val errorMessage: String?
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    private val profilesList = mutableListOf<OrganizationProfile>()
    private var selectedProfileIndex = -1

    private var currentSetCode: String? = null
    private val currentChildrenCodes = mutableListOf<String>()
    private val completedSets = mutableListOf<SetUnit>()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("cz_multi_profiles", Context.MODE_PRIVATE)

        loadProfilesFromStorage()
        setupListeners()
        setupKeyAndTextListeners()
        updateUi()

        binding.tvAppVersion.text = "v1.0.2"
        log("Запуск приложения v1.0.2")
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.android.server.scannerservice.broadcast")
            addAction("com.m3.scan.action.SCANNER_OUTPUT")
            addAction("android.intent.ACTION_DECODE_DATA")
            addAction("com.scan.output")
            addAction("com.tsd.czsetcollector.SCAN_ACTION")
        }
        registerReceiver(scannerReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        setContinuousScanMode(false)
        unregisterReceiver(scannerReceiver)
    }

    private fun setContinuousScanMode(enable: Boolean) {
        val intent = Intent("com.m3.scan.action.SCANNER_SETTING_CHANGE").apply {
            putExtra("setting_name", "continuous_scan")
            putExtra("setting_value", if (enable) 1 else 0)
        }
        sendBroadcast(intent)

        val directIntent = Intent("com.m3.scan.action.CONTINUOUS_SCAN").apply {
            putExtra("enable", enable)
        }
        sendBroadcast(directIntent)
    }

    private fun cleanCode(rawCode: String): String {
        var code = rawCode.trim()
        
        if (code.startsWith("]d2") || code.startsWith("]e0")) {
            code = code.substring(3)
        }
        
        val gsIndex = code.indexOf('\u001d')
        if (gsIndex != -1) {
            return code.substring(0, gsIndex)
        }

        val key91Index = code.indexOf("91")
        if (key91Index in 21..35) {
            return code.substring(0, key91Index)
        }

        return code
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
        val json = prefs.getString("profiles_json", null)
        profilesList.clear()

        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<List<OrganizationProfile>>() {}.type
            val savedList: List<OrganizationProfile> = gson.fromJson(json, type)
            profilesList.addAll(savedList)
        }

        if (profilesList.isEmpty()) {
            profilesList.add(OrganizationProfile("7700000000", "", "Основной профиль"))
        }

        updateProfilesSpinner()
    }

    private fun saveProfilesToStorage() {
        val json = gson.toJson(profilesList)
        prefs.edit().putString("profiles_json", json).apply()
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

            if (inn.isEmpty()) {
                Toast.makeText(this, "Введите ИНН!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val existingIndex = profilesList.indexOfFirst { it.inn == inn }
            val newProfile = OrganizationProfile(inn, token)

            if (existingIndex >= 0) {
                profilesList[existingIndex] = newProfile
                Toast.makeText(this, "Профиль ИНН $inn обновлен", Toast.LENGTH_SHORT).show()
            } else {
                profilesList.add(newProfile)
                Toast.makeText(this, "Новый профиль ИНН $inn сохранен", Toast.LENGTH_SHORT).show()
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

        // Обработчик кнопки сброса незавершенного набора
        binding.btnResetCurrentSet.setOnClickListener {
            if (currentSetCode != null) {
                currentSetCode = null
                currentChildrenCodes.clear()
                setContinuousScanMode(false)
                updateUi()
                log("⚠️ Текущий незавершенный набор сброшен пользователем.")
                Toast.makeText(this, "Набор сброшен. Отсканируйте новый НАБОР.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Нет открытого набора для сброса", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSendDraft.setOnClickListener {
            setContinuousScanMode(false)
            sendDraftToChestnyZnak()
        }
    }

    private fun processScannedBarcode(rawBarcode: String) {
        val barcode = cleanCode(rawBarcode)
        log("Сканирование: $barcode")
        val targetCount = binding.etCountPerSet.text.toString().toIntOrNull() ?: 6

        if (currentSetCode == null) {
            currentSetCode = barcode
            currentChildrenCodes.clear()
            
            setContinuousScanMode(true)
            log("-> Принят НАБОР: $barcode (ВКЛЮЧЕН НЕПРЕРЫВНЫЙ СКАНЕР)")
            Toast.makeText(this, "Набор открыт! Сканируйте пачки подряд", Toast.LENGTH_SHORT).show()
        } else {
            if (currentChildrenCodes.contains(barcode)) {
                log("⚠️ Ошибка: Этот DataMatrix пачки уже сканировали!")
                Toast.makeText(this, "Дубликат пачки!", Toast.LENGTH_SHORT).show()
                return
            }

            if (barcode == currentSetCode) {
                log("⚠️ Ошибка: Сосканирован код самого Набора вместо пачки!")
                return
            }

            currentChildrenCodes.add(barcode)
            log("-> Пачка (${currentChildrenCodes.size}/$targetCount): $barcode")

            if (currentChildrenCodes.size >= targetCount) {
                completedSets.add(SetUnit(currentSetCode!!, ArrayList(currentChildrenCodes)))
                
                setContinuousScanMode(false)
                log("✅ Набор [${currentSetCode!!}] ЗАКРЫТ. Сканер выключен. Отсканируйте следующий НАБОР.")
                Toast.makeText(this, "Набор закрыт! Отсканируйте следующий НАБОР.", Toast.LENGTH_SHORT).show()
                
                currentSetCode = null
                currentChildrenCodes.clear()
            }
        }
        updateUi()
    }

    private fun updateUi() {
        val targetCount = binding.etCountPerSet.text.toString().toIntOrNull() ?: 6

        if (currentSetCode == null) {
            binding.tvScanState.text = "СТАТУС: Отсканируйте DataMatrix НАБОРА"
            binding.tvScanState.setBackgroundColor(0xFFEFF6FF.toInt())
            binding.tvCurrentSet.text = "Текущий набор: НЕ ВЫБРАН"
            binding.tvProgress.text = "Пачек в наборе: 0 / $targetCount"
        } else {
            binding.tvScanState.text = "СТАТУС: Потоковый режим (Сканируйте $targetCount пачек)"
            binding.tvScanState.setBackgroundColor(0xFFFEF3C7.toInt())
            binding.tvCurrentSet.text = "Текущий набор: $currentSetCode"
            binding.tvProgress.text = "Пачек в наборе: ${currentChildrenCodes.size} / $targetCount"
        }

        binding.tvTotalCompletedSets.text = "Закрытых наборов к отправке: ${completedSets.size}"
    }

    private fun sendDraftToChestnyZnak() {
        val inn = binding.etInn.text.toString().trim()
        val rawToken = binding.etToken.text.toString().trim()

        if (inn.isEmpty() || rawToken.isEmpty()) {
            Toast.makeText(this, "Заполните ИНН и Token!", Toast.LENGTH_SHORT).show()
            return
        }

        val sendUnits = ArrayList(completedSets)
        if (currentSetCode != null && currentChildrenCodes.isNotEmpty()) {
            sendUnits.add(SetUnit(currentSetCode!!, ArrayList(currentChildrenCodes)))
        }

        if (sendUnits.isEmpty()) {
            Toast.makeText(this, "Нет готовых наборов для отправки!", Toast.LENGTH_SHORT).show()
            return
        }

        val authHeader = if (rawToken.startsWith("Bearer ")) rawToken else "Bearer $rawToken"

        val requestData = SetDocumentRequest(
            productDocument = ProductDocumentSet(
                inn = inn,
                setUnits = sendUnits
            )
        )

        val jsonBody = gson.toJson(requestData)
        log("🚀 [v1.0.2] Отправка черновика в ЧЗ (${sendUnits.size} наборов)...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                // Список целевых доменов True API ГИС МТ
                val targetUrls = listOf(
                    "https://ismp.crpt.ru/api/v2/true-api/lk/documents/create?type=CREATE_SET",
                    "https://ismp.crpt.ru/api/v2/true-api/documents/create?type=CREATE_SET",
                    "https://markirovka.crpt.ru/api/v2/true-api/lk/documents/create?type=CREATE_SET"
                )

                var isSuccess = false
                var lastResponseCode = 0
                var lastResponseBody = ""

                for (url in targetUrls) {
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .addHeader("Authorization", authHeader)
                        .addHeader("Accept", "application/json")
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .build()

                    val response = client.newCall(request).execute()
                    lastResponseCode = response.code
                    lastResponseBody = response.body?.string() ?: ""

                    if (response.isSuccessful) {
                        isSuccess = true
                        break
                    }

                    if (lastResponseCode != 405) {
                        break
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isSuccess) {
                        val apiResp = try { gson.fromJson(lastResponseBody, CzApiResponse::class.java) } catch (e: Exception) { null }
                        log("✅ УСПЕХ! Черновик создан в ЧЗ.")
                        log("ID Документа: ${apiResp?.documentId ?: "Принят"}")
                        Toast.makeText(this@MainActivity, "Черновик наборов отправлен в ЧЗ!", Toast.LENGTH_LONG).show()

                        completedSets.clear()
                        currentSetCode = null
                        currentChildrenCodes.clear()
                        updateUi()
                    } else {
                        log("❌ ОШИБКА ЧЗ [$lastResponseCode]: $lastResponseBody")
                        Toast.makeText(this@MainActivity, "Ошибка ответа ЧЗ: $lastResponseCode", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    log("💥 Ошибка сети: ${e.localizedMessage}")
                    Toast.makeText(this@MainActivity, "Ошибка сети: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun log(message: String) {
        val currentText = binding.tvLog.text.toString()
        binding.tvLog.text = "$message\n$currentText"
    }
}
