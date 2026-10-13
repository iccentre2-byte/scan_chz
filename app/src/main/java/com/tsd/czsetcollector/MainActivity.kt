package com.tsd.czsetcollector

import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.util.AtomicFile
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.tsd.czsetcollector.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:AppCompatActivity() {
    private lateinit var b:ActivityMainBinding
    private lateinit var prefs:SharedPreferences
    private lateinit var store:QueueStore
    private lateinit var profileStore:ProfileStore
    private val gson=Gson()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val mainHandler=Handler(Looper.getMainLooper())
    private var snapshot=Snapshot()
    private val profiles=mutableListOf<OrganizationProfile>()
    private var selected=0
    private var halted=false
    private var networkBusy=false
    private var maintenance=false
    private var pairMode=false
    private var pc=PcConfig()
    private var foreground=false
    private val work=WorkSession()
    private var dialogDepth=0
    private var editorOpen=false
    private var editingIndex:Int?=null
    private var editorScanTarget:GtinInput?=null
    private val scanEcho=ScanEchoGuard()
    private var scanFeedback:ScanFeedback?=null
    private var scanResult=""
    private var scanResultError=false
    private var queueView=QueueView.CURRENT
    private var historySession:String?=null
    private var historyOptions=emptyList<SessionOption>()
    private var keyboardSuppressed=false
    private var activeToast:Toast?=null
    private val importProfiles=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)attempt{
            check(work.profile==null && !halted){"Сначала выйдите к выбору профиля"}
            val raw=readBackup(uri);val incoming=ProfileBackup.decode(raw)
            require(incoming.profiles.isNotEmpty()){"В резервной копии нет профилей"}
            val preview=ProfileBackup.merge(ProfileData(profiles.toList(),selected),incoming)
            guardDialog().setTitle("Импорт профилей")
                .setMessage("В файле: ${incoming.profiles.size}. Добавить: ${preview.added}, уже есть: ${preview.unchanged}, переименовать: ${preview.renamed}.\nСуществующие шаблоны сохранятся. Очередь и подключение к ПК не меняются.")
                .setNegativeButton("Отмена",null).setPositiveButton("Импортировать"){_,_->attempt{
                    check(work.profile==null && !halted){"Сначала выйдите к выбору профиля"}
                    val merged=profileStore.repository.importBackup(raw)
                    profiles.clear();profiles.addAll(merged.data.profiles);selected=merged.data.selectedIndex
                    profileSpinner();render();log("Импорт профилей проверен: добавлено ${merged.added}; очередь сохранена")
                    toast("Профили импортированы и проверены. Проверьте ИНН и состав перед работой.")
                }}.show()
        }
    }
    private val importQueue=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)attempt{
            check(work.profile==null && !halted){"Сначала выйдите к выбору профиля"}
            val raw=readBackup(uri);val incoming=QueueBackup.prepareImport(readStoredQueue(),raw)
            guardDialog().setTitle("Перенести очередь 1.3.0?")
                .setMessage("Готовых наборов: ${incoming.jobs.size}; незавершённый: ${if(incoming.current!=null)"есть" else "нет"}.\nИдентификаторы, статусы и отметки отправки сохранятся. Перенос возможен только в пустую очередь. Старую программу не удаляйте; продолжайте работу в одной выбранной версии.")
                .setNegativeButton("Отмена",null).setPositiveButton("Перенести"){_,_->attempt{
                    check(work.profile==null && !halted){"Сначала выйдите к выбору профиля"}
                    try {snapshot=store.importEmpty(raw)}catch(e:Exception){storageFailure(e);return@attempt}
                    restoreWork();render()
                    log("Перенос очереди проверен. Статусы, идентификаторы и отметки отправки сохранены.")
                    toast("Очередь перенесена и проверена. Поток на паузе.")
                }}.show()
        }
    }
    private val restartScan:Runnable=Runnable {
        if(canFlow()) {
            startSoftwareScanTrigger()
            mainHandler.postDelayed(scanPulse,6500)
        }
    }
    private val scanPulse:Runnable=Runnable {
        if(canFlow()) {
            stopSoftwareScanTrigger()
            mainHandler.postDelayed(restartScan,80)
        }
    }
    private val keyboardScan=Runnable {
        if(!canReceive() || pairMode)return@Runnable
        val raw=b.etScanInput.text.toString()
        if(raw.isNotBlank() && runCatching{Codes.normalize(raw)}.isSuccess)consumeKeyboardScan()
    }
    private val logFile:File get()=File(getExternalFilesDir(null),"tsd_cz_logs.txt")
    private val poll=object:Runnable {override fun run(){if(foreground){sync(false,false);mainHandler.postDelayed(this,10000)}}}
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context?,intent:Intent?) {
            if(intent==null)return
            val raw=intent.getStringExtra("m3scannerdata")?:intent.getStringExtra("scannerdata")
                ?:intent.getStringExtra("barcode_string")?:intent.getStringExtra("data")
                ?:intent.getStringExtra("scan_data")?:intent.getStringExtra("extra_barcode_data")
                ?:intent.getByteArrayExtra("barcode")?.let{String(it,Charsets.UTF_8)}
            if(!raw.isNullOrBlank())scan(raw)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);b=ActivityMainBinding.inflate(layoutInflater);setContentView(b.root)
        maintenance=intent?.getBooleanExtra("chz_maintenance",false)==true
        prefs=getSharedPreferences("cz_multi_profiles",Context.MODE_PRIVATE);store=QueueStore(filesDir);profileStore=ProfileStore(prefs,filesDir)
        scanFeedback=ScanFeedback(this,prefs.getBoolean("scan_sound",true),prefs.getBoolean("scan_voice",true))
        b.tvLog.movementMethod=ScrollingMovementMethod()
        b.tvAppVersion.text="${BuildConfig.VERSION_NAME} · Профиль → Сборка · Поток"
        try {snapshot=store.load()} catch(e:Exception){storageFailure(e)}
        try {pc=gson.fromJson(prefs.getString("pc_connection",null),PcConfig::class.java)?:PcConfig()}catch(e:Exception){pc=PcConfig()}
        b.spinnerEnvironment.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            listOf("PRODUCTION — реальный ЧЗ","SANDBOX — тестовый контур"))
        b.spinnerQueueView.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,QueueView.values().map{it.label})
        loadProfiles();listeners();restoreWork();render()
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){
            override fun handleOnBackPressed(){
                when {
                    pairMode -> {pairMode=false;pauseFlow();render()}
                    work.profile!=null -> requestProfilePage()
                    editorOpen -> closeProfileEditor()
                    else -> {isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true}
                }
            }
        })
        log("Запуск ${BuildConfig.VERSION_NAME}. Профиль зафиксирован при входе в сборку. Подпись только на ПК.")
        handleStagedMigration(intent)
        handleBackupRequest(intent)
    }
    override fun onNewIntent(intent:Intent?) {
        super.onNewIntent(intent)
        if(intent!=null){
            setIntent(intent)
            if(intent.hasExtra("chz_maintenance")){maintenance=intent.getBooleanExtra("chz_maintenance",false);pauseFlow();render()}
            handleStagedMigration(intent);handleBackupRequest(intent)
        }
    }
    override fun onResume(){
        super.onResume();foreground=true
        scanFeedback?.resume()
        val f=IntentFilter().apply {
            addAction("com.android.server.scannerservice.broadcast");addAction("com.m3.scan.action.SCANNER_OUTPUT")
            addAction("android.intent.ACTION_DECODE_DATA");addAction("com.scan.output");addAction("com.tsd.czsetcollector.SCAN_ACTION")
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_EXPORTED) else registerReceiver(receiver,f)
        mainHandler.postDelayed(poll,10000)
    }
    override fun onPause(){
        foreground=false;mainHandler.removeCallbacks(poll);pairMode=false;pauseFlow();clearScanInput()
        scanFeedback?.pause()
        try{unregisterReceiver(receiver)}catch(e:Exception){};super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(!hasFocus && ::b.isInitialized && work.streaming)pauseFlow()
    }
    override fun onDestroy(){scanFeedback?.pause();scope.cancel();mainHandler.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun listeners(){
        b.btnResumeAfterUpdate.setOnClickListener{maintenance=false;render()}
        b.btnFeedbackSettings.setOnClickListener{feedbackSettings()}
        b.btnViewQueue.setOnClickListener{showQueueViewer()}
        b.btnBeginSession.setOnClickListener{attempt{
            check(!maintenance && !halted){"Сначала завершите проверку обновления"}
            val p=checkNotNull(work.profile){"Сначала выберите рабочий профиль"}
            if(beginTrackedSession(p)){clearScanInput();render();log("Новая сессия начата. Старые наборы остались в истории.")}
        }}
        b.spinnerQueueView.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                val next=QueueView.values().getOrNull(position) ?: return
                if(next!=queueView){queueView=next;render()}
            }
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        b.spinnerSessionHistory.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                val next=historyOptions.getOrNull(position)?.id ?: return
                if(next!=historySession){historySession=next;render()}
            }
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        b.spinnerProfiles.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                if(work.profile!=null || halted || editorOpen || position !in profiles.indices)return
                selected=position;prefs.edit().putInt("selected_profile_index",position).apply();applyProfile(profiles[position]);render()
            }
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        b.btnSaveProfile.setOnClickListener {attempt{
            check(work.profile==null && snapshot.current==null){"Профиль заблокирован на время работы"}
            saveProfile(inputProfile());closeProfileEditor();log("Профиль и состав сохранены")
        }}
        b.btnEditProfile.setOnClickListener {
            if(work.profile!=null || snapshot.current!=null)return@setOnClickListener
            if(editorOpen)closeProfileEditor()else openProfileEditor(profiles[selected],selected)
        }
        b.btnNewProfile.setOnClickListener {
            if(work.profile!=null || snapshot.current!=null || halted)return@setOnClickListener
            val base=profiles[selected]
            openProfileEditor(OrganizationProfile(inn=base.inn,environment=base.environment,
                title=ProfileEditing.availableTitle("Новый профиль",profiles)),null)
        }
        b.btnDuplicateProfile.setOnClickListener {
            if(work.profile!=null || snapshot.current!=null || halted)return@setOnClickListener
            val base=profiles[selected]
            openProfileEditor(base.copy(title=ProfileEditing.availableTitle(base.title+" (копия)",profiles),
                template=base.template.copy(expected=base.template.expected.toMap())),null)
        }
        b.etSetGtin.setOnFocusChangeListener{_,focused->if(focused && editorOpen)selectEditorTarget(b.etSetGtin,"GTIN набора")}
        b.btnScanSetGtin.setOnClickListener{startEditorScan(b.etSetGtin,"GTIN набора")}
        b.compositionEditor.onTarget={field,label->selectEditorTarget(field,label)}
        b.compositionEditor.onScan={field,label->startEditorScan(field,label)}
        b.compositionEditor.onError={toast(it)}
        b.btnAddCompositionRow.setOnClickListener{attempt{b.compositionEditor.addRow().requestFocus()}}
        b.btnBackupAll.setOnClickListener{showBackupDialog()}
        b.btnStartWork.setOnClickListener {attempt{
            check(!maintenance && !halted){"Сначала завершите проверку обновления"}
            check(snapshot.current==null){"Сначала завершите незавершённый набор"}
            val p=if(editorOpen)inputProfile() else profiles[selected]
            work.enter(p,snapshot.current)
            try {
                if(editorOpen)saveProfile(p)
                if(!beginTrackedSession(checkNotNull(work.profile))){work.leave(null);return@attempt}
            } catch(e:Exception) {work.leave(null);throw e}
            editorOpen=false;editingIndex=null;editorScanTarget=null;clearScanInput();hideKeyboard();render()
            scanResult=if(snapshot.current==null)"Готово к сканированию набора." else "Набор открыт. Продолжайте сканировать пачки."
            scanResultError=false;render()
            b.workPage.scrollTo(0,0);b.etScanInput.requestFocus()
            log("Начата работа: ${p.title}. Профиль нельзя переключить внутри сборки.")
        }}
        b.btnBackProfiles.setOnClickListener {requestProfilePage()}
        b.btnFlow.setOnClickListener {if(work.streaming)pauseFlow() else attempt{startFlow()}}
        b.btnDeleteProfile.setOnClickListener {
            if(work.profile!=null || snapshot.current!=null || halted || editorOpen)return@setOnClickListener
            if(profiles.size<=1){toast("Единственный профиль не удаляется");return@setOnClickListener}
            val at=selected
            val target=profiles.getOrNull(at) ?: return@setOnClickListener
            guardDialog().setTitle("Удалить профиль «${target.title}»?")
                .setMessage("Будет удалён только профиль «${target.title}» и его шаблон.\nОчередь не удаляется: собранные наборы останутся со своим ИНН и составом.")
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->
                    attempt{
                        check(work.profile==null && snapshot.current==null && !halted && !editorOpen){"Профиль заблокирован на время работы"}
                        check(selected==at && profiles.getOrNull(at)==target){"Выбранный профиль изменился. Повторите удаление."}
                        val next=profiles.toMutableList().apply{removeAt(at)}
                        profileStore.repository.save(ProfileData(next,0))
                        profiles.clear();profiles.addAll(next);selected=0;profileSpinner();closeProfileEditor()
                    }
                }.show()
        }
        b.btnExportProfiles.setOnClickListener{attempt{
            pauseFlow()
            val encoded=ProfileBackup.encode(ProfileData(profiles.toList(),selected))
            val f=File(getExternalFilesDir(null),"chz-profiles-backup.json")
            f.writeText(encoded,Charsets.UTF_8)
            require(ProfileBackup.decode(f.readText(Charsets.UTF_8))==ProfileData(profiles.toList(),selected)){"Не удалось проверить файл экспорта"}
            share(f,"application/json");log("Экспорт всех профилей проверен. Ключ ПК и КЭП исключены.")
        }}
        b.btnImportProfiles.setOnClickListener{pauseFlow();importProfiles.launch(arrayOf("*/*"))}
        b.btnImportQueue.setOnClickListener{pauseFlow();importQueue.launch(arrayOf("*/*"))}
        b.btnConnectPc.setOnClickListener{connectDialog()}
        b.btnSendDraft.setOnClickListener{pauseFlow();sync(true,true)}
        b.btnRefresh.setOnClickListener{pauseFlow();sync(false,true)}
        b.btnResetCurrentSet.setOnClickListener{
            val j=snapshot.current?:return@setOnClickListener
            guardDialog().setTitle("Сбросить незавершённый набор?")
                .setMessage("Пачек: ${j.children.size}. Этот набор ещё не передавался на ПК. Готовые наборы не будут удалены.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сбросить"){_,_->
                    val next=SessionAccounting.discardCurrent(snapshot)
                    if(persist(next)){snapshot=next;pauseFlow();clearScanInput();showScanResult("Незавершённый набор сброшен. Готово к следующему набору.")}
                }.show()
        }
        b.btnShareLog.setOnClickListener{share(logFile,"text/plain")}
        b.btnBackup.setOnClickListener{
            guardDialog().setTitle("Экспорт резервной копии")
                .setMessage("Файл содержит коды маркировки и ИНН, но не ключ подключения к ПК. Передавайте только себе.")
                .setNegativeButton("Отмена",null).setPositiveButton("Экспортировать"){_,_->attempt{
                    val encoded=QueueBackup.encode(readStoredQueue())
                    val f=File(getExternalFilesDir(null),"chz-queue-backup.json");f.writeText(encoded,Charsets.UTF_8)
                    require(QueueBackup.decode(f.readText(Charsets.UTF_8))==snapshot){"Не удалось проверить файл очереди"}
                    share(f,"application/json")
                }}.show()
        }
        b.btnCheckUpdate.setOnClickListener{
            guardDialog().setTitle("Обновление опытной версии")
                .setMessage("Автозагрузка старого app-release.apk отключена. Новый APK устанавливайте только после проверки версии и подписи. Старую программу и её данные не удаляйте.")
                .setPositiveButton("Понятно",null).show()
        }
        b.etScanInput.showSoftInputOnFocus=false
        b.etScanInput.setOnEditorActionListener{_,_,_->consumeKeyboardScan();true}
        b.etScanInput.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
            override fun afterTextChanged(s:Editable?){
                if(keyboardSuppressed)return
                mainHandler.removeCallbacks(keyboardScan)
                if(canReceive())mainHandler.postDelayed(keyboardScan,200)
            }
        })
        b.btnScan.setOnClickListener{
            if(!canReceive())return@setOnClickListener
            pauseFlow();clearScanInput();b.etScanInput.requestFocus();hideKeyboard();startSoftwareScanTrigger()
        }
    }
    private fun loadProfiles(){
        try {
            val saved=profileStore.repository.load()
            profiles.addAll(saved.profiles);selected=saved.selectedIndex
        }catch(e:Exception){log("Профили не прочитаны; исходные данные защищены от перезаписи.");storageFailure(e)}
        if(profiles.isEmpty())profiles.add(OrganizationProfile(title="Основной профиль"))
        selected=selected.coerceIn(0,profiles.lastIndex);profileSpinner()
    }
    private fun profileSpinner(){
        b.spinnerProfiles.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,profiles)
        b.spinnerProfiles.setSelection(selected);applyProfile(profiles[selected])
    }
    private fun applyProfile(p:OrganizationProfile){
        if(work.profile!=null)return
        b.etProfileTitle.setText(p.title);b.etInn.setText(p.inn)
        b.spinnerEnvironment.setSelection(if(p.environment=="sandbox")1 else 0)
        val template=p.template
        b.etSetGtin.setText(template.setGtin)
        b.compositionEditor.show(template.expected)
        if(p.pg!="grocery")log("В старом профиле была группа ${p.pg}. В этой версии только grocery; проверьте товары перед сохранением.")
    }
    private fun inputProfile():OrganizationProfile {
        val inn=b.etInn.text.toString().trim();require(Codes.inn(inn)){"Проверьте ИНН и контрольные цифры"}
        val title=b.etProfileTitle.text.toString().trim();require(title.isNotBlank()){"Назовите профиль, например «Кириешки 6 / ИП»"}
        val gtin=b.etSetGtin.validated()
        return OrganizationProfile(inn,"grocery",title,if(b.spinnerEnvironment.selectedItemPosition==1)"sandbox" else "production",
            Template(gtin,b.compositionEditor.composition()))
    }
    private fun scan(raw:String){
        if(halted || maintenance || !foreground || dialogDepth>0)return
        if(editorOpen && work.profile==null && !pairMode){
            attempt{
                val target=checkNotNull(editorScanTarget){"Нажмите «Скан» рядом с GTIN набора или пачки"}
                require(target.isShown && target.isEnabled){"Выберите поле GTIN для сканирования"}
                target.scan(raw);stopSoftwareScanTrigger();toast("GTIN сохранён; серийный номер исключён")
            };return
        }
        if(pairMode){pairMode=false;pauseFlow();attempt{
            require(raw.length<=4096){"Слишком длинный QR подключения"};val code=Codes.withoutPrefix(raw);require(code.startsWith("CZPAIR1:")){"Это не QR подключения из помощника ПК"}
            val decoded=String(Base64.decode(code.removePrefix("CZPAIR1:"),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),Charsets.UTF_8)
            val c=gson.fromJson(decoded,PcConfig::class.java);PcClient.validate(c)
            guardDialog().setTitle("Подключить этот ПК?")
                .setMessage("Адрес: ${c.url}\nSHA-256: ${c.pin}\n\nСверьте адрес и отпечаток с экраном ПК. КЭП не передаётся на ТСД.")
                .setNegativeButton("Отмена",null).setPositiveButton("Подключить"){_,_->savePc(c)}.show()
        };return}
        if(Codes.withoutPrefix(raw).startsWith("CZPAIR1:")){toast("Сначала нажмите «Подключить ПК» → «Сканировать QR»");return}
        if(!canReceive())return
        mainHandler.removeCallbacks(scanPulse);mainHandler.removeCallbacks(restartScan)
        if(work.streaming)stopSoftwareScanTrigger()
        try {
            val profile=checkNotNull(work.profile){"Сначала выберите профиль"}
            val code=Codes.normalize(raw)
            val now=SystemClock.elapsedRealtime()
            if(!scanEcho.accept(code,now))return
            val result=CollectionScan.accept(snapshot,profile,code){next->persist(next)}
            if(result==null){showScanResult("Код не принят: ошибка сохранения.",true);return}
            snapshot=result.snapshot
            if(!SessionAccounting.ready(snapshot))pauseFlow()
            showScanResult(result.message,result.signal==ScanSignal.DUPLICATE)
            result.signal?.let{signal->
                scanFeedback?.play(signal)
                log(result.message)
            }
        } catch(e:Exception) {
            showScanResult("Код не принят: ${e.message?:"Ошибка"}",true)
            toast(e.message?:"Ошибка");log(e.message?:"Ошибка")
        } finally {scheduleScan(220)}
    }
    private fun beginTrackedSession(profile:OrganizationProfile):Boolean {
        val next=SessionAccounting.begin(snapshot,profile,System.currentTimeMillis())
        if(!persist(next))return false
        snapshot=next
        // The ledger contains the frozen profile and is the authoritative restart record.
        if(!prefs.edit().putString("work_session_profile",gson.toJson(profile)).commit())log("Профиль сессии сохранён с очередью; отдельный кэш профиля не записан.")
        queueView=QueueView.CURRENT;b.spinnerQueueView.setSelection(queueView.ordinal)
        historySession=SessionAccounting.active(snapshot)?.id
        scanResult="Новая сессия начата. Сканируйте код набора.";scanResultError=false
        return true
    }
    private fun showScanResult(message:String,error:Boolean=false){scanResult=message;scanResultError=error;render()}
    private fun feedbackSettings(){
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,12,24,12)}
        val sound=CheckBox(this).apply{text="Короткие звуковые сигналы";isChecked=prefs.getBoolean("scan_sound",true)}
        val voice=CheckBox(this).apply{text="Голос: набор открыт, набор собран, дубль";isChecked=prefs.getBoolean("scan_voice",true)}
        val note=TextView(this).apply{text="${scanFeedback?.voiceStatus.orEmpty()}\nГромкость — мультимедиа ТСД. «Набор собран» означает сохранение на ТСД. Подпись и отправка в ЧЗ выполняются на ПК.";setPadding(0,12,0,12)}
        listOf(sound,voice,note).forEach{layout.addView(it)}
        guardDialog().setTitle("Звук и голос").setView(layout).setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить"){_,_->attempt{
                check(prefs.edit().putBoolean("scan_sound",sound.isChecked).putBoolean("scan_voice",voice.isChecked).commit()){"Не удалось сохранить настройки сигналов"}
                scanFeedback?.configure(sound.isChecked,voice.isChecked)
            }}.show()
    }
    private fun renderQueueViews(){
        val options=QueueViews.options(snapshot)
        if(options!=historyOptions){
            historyOptions=options
            historySession=historySession?.takeIf{id->options.any{it.id==id}} ?: SessionAccounting.active(snapshot)?.id ?: options.firstOrNull()?.id
            b.spinnerSessionHistory.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,options)
            b.spinnerSessionHistory.setSelection(options.indexOfFirst{it.id==historySession}.coerceAtLeast(0))
        }
        b.spinnerSessionHistory.visibility=if(queueView==QueueView.HISTORY)View.VISIBLE else View.GONE
        val data=QueueViews.select(snapshot,queueView,historySession)
        b.tvQueueSelection.text=data.title;b.tvTotalCompletedSets.text=data.summary;b.tvQueue.text=QueueViews.describe(data)
    }
    private fun showQueueViewer(){
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,8,20,8)}
        val mode=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,QueueView.values().map{it.label});setSelection(QueueView.ALL.ordinal)}
        val options=QueueViews.options(snapshot)
        val history=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,options)}
        val summary=TextView(this).apply{setPadding(0,8,0,8);textSize=15f}
        val content=TextView(this).apply{setPadding(0,8,0,8);textSize=15f;setTextIsSelectable(true)}
        val scroll=ScrollView(this).apply{addView(content)}
        layout.addView(mode);layout.addView(history);layout.addView(summary)
        layout.addView(scroll,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(resources.displayMetrics.heightPixels*0.45f).toInt()))
        fun update(){
            val view=QueueView.values().getOrNull(mode.selectedItemPosition) ?: QueueView.ALL
            history.visibility=if(view==QueueView.HISTORY)View.VISIBLE else View.GONE
            val data=QueueViews.select(snapshot,view,options.getOrNull(history.selectedItemPosition)?.id)
            summary.text=data.title+"\n"+data.summary;content.text=QueueViews.describe(data)
        }
        val onSelection=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){update()}
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        mode.onItemSelectedListener=onSelection;history.onItemSelectedListener=onSelection;update()
        guardDialog().setTitle("Наборы и история сессий").setView(layout).setPositiveButton("Закрыть",null).show()
    }
    private fun sync(upload:Boolean,notify:Boolean){
        if(networkBusy||halted||maintenance||dialogDepth>0)return
        if(pc.url.isBlank()){if(notify)connectDialog();return}
        // A manual transfer from this work page sends only its frozen profile's jobs.
        val toUpload=if(upload)snapshot.jobs.filter{!it.uploaded&&it.status=="LOCAL_READY"&&work.owns(it)}else emptyList()
        val toRead=snapshot.jobs.filter{it.uploaded&&it.status!="VERIFIED"&&it.status!="CANCELLED_LOCAL"}.take(100)
        if(toUpload.isEmpty()&&toRead.isEmpty()){if(notify)toast("Нет новых наборов или незавершённых отправок");return}
        networkBusy=true;render()
        scope.launch {
            try {
                val client=PcClient(pc)
                for(job in toUpload){
                    val reply=withContext(Dispatchers.IO){client.upload(job)};applyReply(job,reply)
                    if(halted)break
                }
                if(!halted)for(job in toRead){
                    val reply=withContext(Dispatchers.IO){client.status(job.id)};applyReply(job,reply)
                    if(halted)break
                }
                if(notify)log(if(upload)"Готовые наборы переданы в очередь ПК. Для ЧЗ нужна подпись на ПК." else "Статусы с ПК обновлены")
            }catch(e:CancellationException){throw e}
             catch(e:Exception){if(notify){log("Связь с ПК: ${e.message}. Очередь сохранена.");toast("${e.message}\nНаборы не удалены")}}
            finally{networkBusy=false;render()}
        }
    }
    private fun applyReply(original:Job,reply:Job){
        require(reply.schemaVersion==original.schemaVersion&&reply.pg==original.pg&&reply.id==original.id&&reply.inn==original.inn&&reply.environment==original.environment&&
            reply.setCode==original.setCode&&reply.children.toSet()==original.children.toSet()&&reply.children.size==original.children.size&&
            reply.template==original.template){"ПК вернул другой состав. Ответ не принят"}
        require(reply.status in setOf("WAITING_SIGNATURE","SUBMITTING","SUBMITTED","PROCESSING","VERIFIED","REJECTED","UNKNOWN","BLOCKED","CHECK_REQUIRED","CANCELLED_LOCAL")){"Неизвестный статус ПК"}
        val changed=!original.uploaded||original.status!=reply.status||original.detail!=reply.detail||original.documentId!=reply.documentId
        original.status=reply.status;original.detail=reply.detail;original.documentId=reply.documentId;original.uploaded=true
        if(changed){persist();render()}
    }
    private fun connectDialog(){
        pairMode=false;pauseFlow()
        val layout=LinearLayout(this);layout.orientation=LinearLayout.VERTICAL;layout.setPadding(24,12,24,12)
        val url=EditText(this).apply{hint="https://192.168.1.10:18765";setText(pc.url)}
        val key=EditText(this).apply{hint="Ключ подключения к ПК";setText(pc.key);inputType=129}
        val pin=EditText(this).apply{hint="SHA-256 сертификата ПК (64 символа)";setText(pc.pin)}
        listOf(url,key,pin).forEach{layout.addView(it)}
        guardDialog().setTitle("Подключить помощник ПК").setView(layout)
            .setNegativeButton("Отмена",null).setNeutralButton("Сканировать QR"){_,_->pairMode=true;render();toast("Отсканируйте QR из окна помощника ПК");startSoftwareScanTrigger()}
            .setPositiveButton("Сохранить"){_,_->attempt{val c=PcConfig(url.text.toString().trim().trimEnd('/'),key.text.toString().trim(),pin.text.toString().trim());PcClient.validate(c);savePc(c)}}.show()
    }
    private fun savePc(c:PcConfig){pc=c;prefs.edit().putString("pc_connection",gson.toJson(c)).commit();render();log("Подключение к ПК сохранено: ${c.url}")}
    private fun persist(next:Snapshot=snapshot):Boolean=try{store.save(next);true}catch(e:Exception){storageFailure(e);false}
    private fun readStoredQueue():Snapshot=try{store.load()}catch(e:Exception){storageFailure(e);throw e}
    private fun readStoredProfiles():ProfileData=try{profileStore.repository.load()}catch(e:Exception){storageFailure(e);throw e}
    private fun storageFailure(e:Exception){
        halted=true;pauseFlow()
        guardDialog().setTitle("Сканирование остановлено: ошибка хранения")
            .setMessage("${e.message}\nНе удаляйте приложение и его данные. Очередь не будет перезаписана пустой.")
            .setPositiveButton("Понятно",null).show();render()
    }
    private fun render(){
        b.btnResumeAfterUpdate.visibility=if(maintenance)View.VISIBLE else View.GONE
        val j=snapshot.current;val p=work.profile;val locked=p!=null||j!=null||halted
        val sessionReady=SessionAccounting.ready(snapshot)
        b.profilePage.visibility=if(p==null)View.VISIBLE else View.GONE
        b.workPage.visibility=if(p!=null)View.VISIBLE else View.GONE
        b.tvScanResult.visibility=if(p!=null)View.VISIBLE else View.GONE
        b.tvScanResult.text=if(halted)"Сканирование остановлено: ошибка сохранения." else if(p!=null&&!sessionReady)"Нажмите «Начать новую сессию»." else scanResult.ifBlank{if(j==null)"Готово к сканированию набора." else "Набор открыт. Продолжайте сканировать пачки."}
        b.tvScanResult.setTextColor(android.graphics.Color.BLACK)
        b.tvScanResult.setBackgroundColor(if(scanResultError||halted)0xffffe0b2.toInt()else 0xffe8f5e9.toInt())
        b.profileEditor.visibility=if(editorOpen)View.VISIBLE else View.GONE
        b.btnEditProfile.text=if(editorOpen)"Закрыть настройки без сохранения" else "Настроить профиль"
        b.btnStartWork.text=if(editorOpen)"Сохранить и начать работу" else "Начать работу"
        b.btnStartWork.isEnabled=p==null&&!halted&&!maintenance
        b.btnViewQueue.isEnabled=!editorOpen&&!halted
        b.btnEditProfile.isEnabled=!locked
        b.btnNewProfile.isEnabled=!locked&&!editorOpen
        b.btnDuplicateProfile.isEnabled=!locked&&!editorOpen
        b.btnBackupAll.isEnabled=!halted&&!networkBusy&&!editorOpen
        b.btnExportProfiles.isEnabled=!halted
        b.btnImportProfiles.isEnabled=p==null&&!halted&&!editorOpen
        b.btnImportQueue.isEnabled=p==null&&!halted&&!editorOpen&&SessionAccounting.emptyForImport(snapshot)
        val chosen=profiles.getOrNull(selected)
        b.tvProfileSummary.text=chosen?.let{"${it.title}\nИНН: ${it.inn.ifBlank{"не заполнен"}}\n${if(it.environment=="sandbox")"ТЕСТОВЫЙ контур" else "РЕАЛЬНЫЙ ЧЗ"}\nGTIN набора: ${it.template.setGtin.ifBlank{"не заполнен"}} · ${it.template.expected.values.sum()} шт"}?:"Выберите профиль"
        b.tvWorkProfile.text=p?.let{"${it.title}\nИНН ${it.inn} · ${if(it.environment=="sandbox")"ТЕСТОВЫЙ контур" else "РЕАЛЬНЫЙ ЧЗ"}\nПрофиль зафиксирован до выхода из сборки"}?:""
        val session=SessionAccounting.active(snapshot)
        b.tvWorkSession.text=when{
            session!=null->"Текущая сессия: ${QueueViews.date(session.startedAtMs)} · ${session.profile.title}"
            j!=null->"Незавершённый набор из «${SessionAccounting.LEGACY_TITLE}». Завершите его, затем начните новую сессию."
            else->"Сессия ещё не начата. Старые наборы находятся в истории «${SessionAccounting.LEGACY_TITLE}»."
        }
        b.btnBeginSession.visibility=if(p!=null && session==null)View.VISIBLE else View.GONE
        b.btnBeginSession.isEnabled=p!=null&&j==null&&!halted&&!maintenance
        b.btnBackProfiles.isEnabled=!maintenance
        b.btnFlow.text=if(work.streaming)"ПАУЗА потока" else "СТАРТ потока"
        b.btnFlow.isEnabled=p!=null&&sessionReady&&!halted&&!maintenance&&!pairMode&&dialogDepth==0
        b.btnScan.isEnabled=p!=null&&sessionReady&&!halted&&!maintenance&&!pairMode&&dialogDepth==0
        b.tvFlowState.text=when {work.streaming->"ПОТОК: подносите коды по очереди. После пачек — код следующего набора."; p!=null->"Поток на паузе. Нажмите СТАРТ один раз для всей серии наборов.";else->""}
        b.btnResetCurrentSet.isEnabled=j!=null&&!halted&&!maintenance
        b.root.keepScreenOn=work.streaming

        listOf<View>(b.etProfileTitle,b.etInn,b.spinnerEnvironment,b.etSetGtin,b.compositionEditor,b.btnScanSetGtin,b.btnAddCompositionRow,b.btnSaveProfile).forEach{it.isEnabled=!locked}
        b.spinnerProfiles.isEnabled=!locked&&!editorOpen
        b.btnDeleteProfile.isEnabled=!locked&&!editorOpen&&selected in profiles.indices
        b.tvConnection.text=if(pc.url.isBlank())"ПК не подключён" else "ПК: ${pc.url}"
        b.tvScanState.text=if(halted)"ОСТАНОВЛЕНО: ошибка сохранения" else if(pairMode)"Сканируйте QR подключения ПК" else if(!sessionReady)"Сначала начните новую сессию" else if(j==null)"Сканируйте DataMatrix НАБОРА" else "Сканируйте пачки: ${j.children.size} / ${j.template.expected.values.sum()}"
        b.tvCurrentSet.text=j?.let{"Набор: ${it.setCode}\nИНН ${it.inn} · ${it.environment}"}?:"Текущий набор не открыт"
        b.tvProgress.text=j?.template?.expected?.entries?.joinToString("\n"){(g,n)->"$g: ${j.children.count{it.substring(2,16)==g}} / $n"}?:"Проверьте шаблон перед сборкой"
        renderQueueViews()
        b.btnSendDraft.isEnabled=!networkBusy&&!halted&&!maintenance;b.btnRefresh.isEnabled=!networkBusy&&!halted&&!maintenance
    }
    private fun attempt(block:()->Unit){try{block()}catch(e:Exception){if(e is ProfileStorageException)storageFailure(e);toast(e.message?:"Ошибка");log(e.message?:"Ошибка")}}
    private fun toast(message:String){activeToast?.cancel();activeToast=Toast.makeText(this,message,Toast.LENGTH_LONG);activeToast?.show()}
    private fun log(message:String){
        val entry="[${SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(Date())}] $message\n"
        try{if(logFile.length()>1_000_000)logFile.writeText("");logFile.appendText(entry)}catch(e:Exception){}
        b.tvLog.text=(entry+b.tvLog.text.toString()).take(16000)
    }
    private fun share(file:File,mime:String){attempt{
        require(file.exists()){"Файл пока не создан"}
        val uri=FileProvider.getUriForFile(this,"$packageName.provider",file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Передать файл себе"))
    }}
    private fun readBackup(uri:android.net.Uri):String {
        val bytes=contentResolver.openInputStream(uri)?.use{stream->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=BackupJson.MAX_BYTES){"Резервная копия слишком велика"};out.write(buffer,0,n)}
            out.toByteArray()
        }?:throw IllegalArgumentException("Не удалось открыть резервную копию")
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    private fun readStaged(file:File):String {
        require(file.isFile && file.length()<=BackupJson.MAX_BYTES) { "Файл переноса отсутствует или слишком велик: ${file.name}" }
        val bytes=file.inputStream().use{stream->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=BackupJson.MAX_BYTES){"Файл переноса слишком велик"};out.write(buffer,0,n)}
            out.toByteArray()
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun handleBackupRequest(request:Intent?) {
        if(request?.getBooleanExtra("chz_export_backup",false)!=true)return
        request.removeExtra("chz_export_backup")
        attempt{
            val requestId=request.getStringExtra("chz_backup_request") ?: java.util.UUID.randomUUID().toString()
            request.removeExtra("chz_backup_request")
            showBackupDialog(requestId)
        }
    }
    private fun showBackupDialog(requestId:String=java.util.UUID.randomUUID().toString()) {attempt{
        require(requestId.length<=64 && requestId.matches(Regex("[A-Za-z0-9-]+"))){"Неверный идентификатор запроса резервной копии"}
        check(!halted && !networkBusy && !editorOpen && dialogDepth==0){"Сначала сохраните или закройте редактор и дождитесь завершения связи с ПК"}
        guardDialog().setTitle("Сохранить резервную копию?")
            .setMessage("Будут сохранены все профили, составы и очередь, включая незавершённый набор и статусы отправки. Ключ подключения к ПК и КЭП исключены. Сканирование останется на паузе.")
            .setNegativeButton("Отмена",null).setPositiveButton("Сохранить копию"){_,_->attempt{exportCompleteBackup(requestId)}}.show()
    }}
    private fun exportCompleteBackup(requestId:String) {
        check(!halted && !networkBusy && !editorOpen){"Дождитесь завершения связи с ПК и закройте редактор"}
        val savedProfiles=readStoredProfiles();val savedQueue=readStoredQueue()
        val profilesJson=ProfileBackup.encode(savedProfiles);val queueJson=QueueBackup.encode(savedQueue)
        val dir=File(checkNotNull(getExternalFilesDir(null)){"Недоступна папка копии"},"backup")
        check(dir.isDirectory || dir.mkdirs()){"Не удалось создать папку копии"}
        fun write(file:File,value:String) {
            val atomic=AtomicFile(file);val stream=atomic.startWrite()
            try{stream.write(value.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream)}
            catch(e:Exception){atomic.failWrite(stream);throw e}
            require(String(atomic.readFully(),Charsets.UTF_8)==value){"Не удалось проверить сохранённую копию"}
        }
        val profilesFile=File(dir,"chz-profiles-backup.json");val queueFile=File(dir,"chz-queue-backup.json")
        write(profilesFile,profilesJson);write(queueFile,queueJson)
        require(readStoredProfiles()==savedProfiles && readStoredQueue()==savedQueue){"Данные изменились во время экспорта; сохраните копию ещё раз"}
        val result=CompleteBackup.verifiedResult(savedProfiles,savedQueue,readStaged(profilesFile),readStaged(queueFile),requestId)
        write(File(dir,"backup-result.json"),result)
        log("Копия профилей и очереди сохранена и проверена. Профилей: ${savedProfiles.profiles.size}; готовых наборов: ${savedQueue.jobs.size}.")
        toast("Резервная копия сохранена и проверена. Можно скопировать её на ПК.")
    }
    private fun handleStagedMigration(request:Intent?) {
        if(request?.getBooleanExtra("chz_import_staged",false)!=true)return
        request.removeExtra("chz_import_staged") // Rotation must not open a second confirmation.
        attempt{
            check(!halted && work.profile==null && !networkBusy && !editorOpen && dialogDepth==0) { "Перенос заблокирован во время рабочей сессии или редактирования. Сначала выйдите к профилям" }
            pauseFlow()
            val external=checkNotNull(getExternalFilesDir(null)){"Недоступна папка переноса"}
            val dir=File(external,"migration")
            val profilesFile=readStaged(File(dir,"profiles-transfer.json"))
            val queueFile=readStaged(File(dir,"queue-transfer.json"))
            val source=StagedMigration.read(profilesFile,queueFile)
            val plan=StagedMigration.prepare(ProfileData(profiles.toList(),selected),readStoredQueue(),source)
            guardDialog().setTitle("Перенести сохранённые данные?")
                .setMessage("Профилей: ${plan.source.profiles.size}; готовых наборов: ${plan.queue.jobs.size}; незавершённый: ${if(plan.queue.current!=null)"есть" else "нет"}.\nСуществующие профили сохранятся. Статусы отправки и состав очереди будут проверены. Подключение к ПК не переносится.")
                .setNegativeButton("Отмена",null).setPositiveButton("Перенести"){_,_->attempt{
                    check(!halted && work.profile==null && !networkBusy && !editorOpen) { "Перенос заблокирован во время работы или редактирования" }
                    // Re-read both sources and destination: files may have changed while the dialog was open.
                    val currentProfiles=readStaged(File(dir,"profiles-transfer.json"))
                    val currentQueue=readStaged(File(dir,"queue-transfer.json"))
                    val checkedSources=StagedMigration.read(currentProfiles,currentQueue)
                    val latest=StagedMigration.prepare(readStoredProfiles(),readStoredQueue(),checkedSources)
                    require(latest.profilesHash==plan.profilesHash && latest.queueHash==plan.queueHash) { "Исходные копии изменились; запустите перенос заново" }
                    val merged=profileStore.repository.importBackup(currentProfiles)
                    val savedBefore=readStoredQueue()
                    if(savedBefore!=latest.queue){
                        try{snapshot=store.importEmpty(currentQueue)}catch(e:Exception){storageFailure(e);return@attempt}
                    }else snapshot=savedBefore
                    val savedProfiles=readStoredProfiles()
                    val savedQueue=readStoredQueue()
                    val verified=try{StagedMigration.verifiedResult(latest.copy(merge=merged),savedProfiles,savedQueue)}
                        catch(e:Exception){storageFailure(e);return@attempt}
                    val result=AtomicFile(File(dir,"migration-result.json"))
                    try {
                        val stream=result.startWrite()
                        try{stream.write(verified.toByteArray(Charsets.UTF_8));result.finishWrite(stream)}
                        catch(e:Exception){result.failWrite(stream);throw e}
                        require(String(result.readFully(),Charsets.UTF_8)==verified){"Подтверждение переноса не сохранено"}
                    } catch(e:Exception){storageFailure(e);return@attempt}
                    profiles.clear();profiles.addAll(savedProfiles.profiles);selected=savedProfiles.selectedIndex
                    snapshot=savedQueue;profileSpinner();restoreWork();render()
                    log("Автоматический перенос проверен. Профилей: ${latest.source.profiles.size}; готовых наборов: ${latest.queue.jobs.size}. Статусы отправки сохранены.")
                    toast("Перенос подтверждён. Старую программу не удаляйте. Работайте в одной выбранной версии.")
                }}.show()
        }
    }

    private fun saveProfile(p:OrganizationProfile){
        val next=ProfileEditing.saved(profiles,editingIndex,p)
        profileStore.repository.save(next)
        profiles.clear();profiles.addAll(next.profiles);selected=next.selectedIndex;profileSpinner()
    }
    private fun openProfileEditor(profile:OrganizationProfile,index:Int?) {
        check(work.profile==null && snapshot.current==null && !halted){"Профиль заблокирован на время работы"}
        pauseFlow();editorScanTarget=null;editingIndex=index;editorOpen=true
        applyProfile(profile);render();b.etProfileTitle.requestFocus();b.etProfileTitle.selectAll()
        b.profilePage.post{b.profilePage.smoothScrollTo(0,b.profileEditor.top)}
    }
    private fun closeProfileEditor() {
        editorOpen=false;editingIndex=null;editorScanTarget=null;stopSoftwareScanTrigger();hideKeyboard()
        applyProfile(profiles[selected]);render()
    }
    private fun selectEditorTarget(field:GtinInput,label:String) {
        if(!editorOpen || work.profile!=null)return
        editorScanTarget=field;b.tvEditorScanTarget.text="Сканирование: $label. Сохранится только GTIN."
    }
    private fun startEditorScan(field:GtinInput,label:String) {
        if(!editorOpen || work.profile!=null || halted || dialogDepth>0)return
        selectEditorTarget(field,label);field.requestFocus();field.selectAll();hideKeyboard();startSoftwareScanTrigger()
    }
    private fun restoreWork(){
        if(halted)return
        val saved=runCatching{gson.fromJson(prefs.getString("work_session_profile",null),OrganizationProfile::class.java)}.getOrNull()
        val current=snapshot.current
        val candidate=SessionAccounting.active(snapshot)?.profile ?: if(snapshot.sessions!=null)null else if(current!=null){
            val match=profiles.firstOrNull{WorkSession.matches(it,current)}
            WorkSession.fromJob(current,match?.title ?: saved?.takeIf{WorkSession.matches(it,current)}?.title ?: "Незавершённый набор")
        }else saved
        if(candidate!=null){
            try{work.enter(candidate,current);log("Рабочий профиль восстановлен. Поток на паузе.")}
            catch(e:Exception){if(current!=null)storageFailure(e) else log("Рабочая сессия не восстановлена: ${e.message}")}
        }
    }
    private fun requestProfilePage(){
        pauseFlow();pairMode=false
        if(maintenance){toast("Сначала завершите проверку обновления");return}
        if(snapshot.current!=null){
            guardDialog().setTitle("Набор ещё не закончен")
                .setMessage("Переключение профиля заблокировано. Завершите сборку или отдельно сбросьте незавершённый набор. Его состав сохранён.")
                .setPositiveButton("Продолжить сборку",null).show();return
        }
        guardDialog().setTitle("Выйти к выбору профиля?")
            .setMessage("Текущая сессия завершится. Готовые наборы останутся в очереди и истории со своим ИНН и составом. На следующем экране можно выбрать другой профиль и начать новую сессию.")
            .setNegativeButton("Остаться",null).setPositiveButton("К профилям"){_,_->attempt{
                val next=SessionAccounting.finish(snapshot,System.currentTimeMillis())
                if(next!=snapshot){if(!persist(next))return@attempt;snapshot=next}
                val cacheRemoved=prefs.edit().remove("work_session_profile").commit()
                check(cacheRemoved || snapshot.sessions!=null){"Не удалось сохранить выход из профиля"}
                if(!cacheRemoved)log("Сессия завершена в очереди; отдельный кэш профиля будет проигнорирован при запуске.")
                work.leave(snapshot.current);clearScanInput();applyProfile(profiles[selected]);render();b.profilePage.scrollTo(0,0)
            }}.show()
    }
    private fun guardDialog():AlertDialog.Builder{
        pauseFlow();dialogDepth++
        return AlertDialog.Builder(this).setOnDismissListener{dialogDepth=(dialogDepth-1).coerceAtLeast(0);render()}
    }
    private fun canReceive()=work.canReceive(foreground,halted,dialogDepth>0)&&!pairMode&&!maintenance&&SessionAccounting.ready(snapshot)
    private fun canFlow()=work.canTrigger(foreground,halted,dialogDepth>0)&&!pairMode&&!maintenance&&SessionAccounting.ready(snapshot)
    private fun startFlow(){
        check(canReceive()){ "Сначала войдите в рабочий экран и закройте диалог" }
        clearScanInput();b.etScanInput.requestFocus();hideKeyboard()
        work.start();scheduleScan(0);render();log("Поток запущен. Коды следующих наборов принимаются без повторного СТАРТ.")
    }
    private fun pauseFlow(){
        work.pause();mainHandler.removeCallbacks(scanPulse);mainHandler.removeCallbacks(restartScan)
        stopSoftwareScanTrigger()
        if(::b.isInitialized)render()
    }
    private fun scheduleScan(delay:Long){
        mainHandler.removeCallbacks(scanPulse);mainHandler.removeCallbacks(restartScan)
        if(canFlow())mainHandler.postDelayed(scanPulse,delay)
    }
    private fun clearScanInput(){
        mainHandler.removeCallbacks(keyboardScan)
        keyboardSuppressed=true
        try{b.etScanInput.setText("")}finally{keyboardSuppressed=false}
    }
    private fun consumeKeyboardScan(){
        val raw=b.etScanInput.text.toString();clearScanInput()
        if(raw.isNotBlank())scan(raw)
    }
    private fun hideKeyboard(){
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(b.root.windowToken,0)
    }

    private fun startSoftwareScanTrigger() {
        val scanTriggerIntent = Intent("com.android.server.scannerservice.m3plugin.start")
        sendBroadcast(scanTriggerIntent)

        val altScanTrigger = Intent("android.intent.action.M3SCANNER_BUTTON_DOWN")
        sendBroadcast(altScanTrigger)
    }

    private fun stopSoftwareScanTrigger() {
        val scanStopIntent = Intent("com.android.server.scannerservice.m3plugin.stop")
        sendBroadcast(scanStopIntent)

        val altScanStop = Intent("android.intent.action.M3SCANNER_BUTTON_UP")
        sendBroadcast(altScanStop)
    }


}
