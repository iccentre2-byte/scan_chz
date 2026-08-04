package com.tsd.czsetcollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.annotations.SerializedName
import com.tsd.czsetcollector.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

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

interface CzApiService {
    @POST("api/v2/true-api/lk/documents/create?type=CREATE_SET")
    suspend fun sendSetDraft(
        @Header("Authorization") token: String,
        @Body request: SetDocumentRequest
    ): Response<CzApiResponse>
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var currentSetCode: String? = null
    private val currentChildrenCodes = mutableListOf<String>()
    private val completedSets = mutableListOf<SetUnit>()

    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val barcode = intent?.getStringExtra("m3scannerdata")
                ?: intent?.getStringExtra("data")
                ?: intent?.getStringExtra("scan_data")
                
            barcode?.trim()?.let { processScannedBarcode(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.m3.scan.action.SCANNER_OUTPUT")
            addAction("android.intent.ACTION_DECODE_DATA")
            addAction("com.tsd.czsetcollector.SCAN_ACTION")
        }
        registerReceiver(scannerReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(scannerReceiver)
    }

    private fun setupListeners() {
        binding.btnSendDraft.setOnClickListener {
            sendDraftToChestnyZnak()
        }
    }

    private fun processScannedBarcode(barcode: String) {
        log("Сканирование: $barcode")
        val targetCount = binding.etCountPerSet.text.toString().toIntOrNull() ?: 6

        if (currentSetCode == null) {
            currentSetCode = barcode
            currentChildrenCodes.clear()
            log("-> Принят НАБОР: $barcode")
            Toast.makeText(this, "Набор открыт! Сканируйте пачки", Toast.LENGTH_SHORT).show()
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
                log("✅ Набор [\${currentSetCode!!}] успешно заполнен и закрыт!")
                Toast.makeText(this, "Набор закрыт! Жду следующий набор.", Toast.LENGTH_SHORT).show()
                
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
            binding.tvScanState.text = "СТАТУС: Сканируйте ПАЧКИ для наполнения"
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

        val request = SetDocumentRequest(
            productDocument = ProductDocumentSet(
                inn = inn,
                setUnits = sendUnits
            )
        )

        log("🚀 Отправка черновика в ЧЗ (${sendUnits.size} наборов)...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl("https://markirovka.crpt.ru/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val api = retrofit.create(CzApiService::class.java)
                val response = api.sendSetDraft(authHeader, request)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val body = response.body()
                        log("✅ УСПЕХ! Черновик 'Сформировать набор' создан в ЧЗ.")
                        log("ID Документа: ${body?.documentId ?: "Принят"}")
                        Toast.makeText(this@MainActivity, "Черновик наборов отправлен в ЧЗ!", Toast.LENGTH_LONG).show()

                        completedSets.clear()
                        currentSetCode = null
                        currentChildrenCodes.clear()
                        updateUi()
                    } else {
                        val err = response.errorBody()?.string() ?: response.message()
                        log("❌ ОШИБКА ЧЗ [${response.code()}]: $err")
                        Toast.makeText(this@MainActivity, "Ошибка отправки в ЧЗ", Toast.LENGTH_LONG).show()
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