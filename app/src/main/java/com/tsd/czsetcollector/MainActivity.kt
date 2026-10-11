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
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val gson=Gson()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val mainHandler=Handler(Looper.getMainLooper())
    private var snapshot=Snapshot()
    private val profiles=mutableListOf<OrganizationProfile>()
    private var selected=0
    private var halted=false
    private var networkBusy=false
    private var pairMode=false
    private var pc=PcConfig()
    private var foreground=false
    private val work=WorkSession()
    private var dialogDepth=0
    private var editorOpen=false
    private var lastCode=""
    private var lastCodeAt=0L
    private var keyboardSuppressed=false
    private var activeToast:Toast?=null
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
        prefs=getSharedPreferences("cz_multi_profiles",Context.MODE_PRIVATE);store=QueueStore(filesDir)
        b.tvLog.movementMethod=ScrollingMovementMethod()
        b.tvAppVersion.text="1.3.1-rc1 · Профиль → Сборка · Поток"
        try {snapshot=store.load()} catch(e:Exception){storageFailure(e)}
        try {pc=gson.fromJson(prefs.getString("pc_connection",null),PcConfig::class.java)?:PcConfig()}catch(e:Exception){pc=PcConfig()}
        b.spinnerEnvironment.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            listOf("PRODUCTION — реальный ЧЗ","SANDBOX — тестовый контур"))
        loadProfiles();listeners();restoreWork();render()
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){
            override fun handleOnBackPressed(){
                when {
                    pairMode -> {pairMode=false;pauseFlow();render()}
                    work.profile!=null -> requestProfilePage()
                    editorOpen -> {editorOpen=false;applyProfile(profiles[selected]);render()}
                    else -> {isEnabled=false;onBackPressedDispatcher.onBackPressed();isEnabled=true}
                }
            }
        })
        log("Запуск 1.3.1-rc1. Профиль зафиксирован при входе в сборку. Подпись только на ПК.")
    }
    override fun onResume(){
        super.onResume();foreground=true
        val f=IntentFilter().apply {
            addAction("com.android.server.scannerservice.broadcast");addAction("com.m3.scan.action.SCANNER_OUTPUT")
            addAction("android.intent.ACTION_DECODE_DATA");addAction("com.scan.output");addAction("com.tsd.czsetcollector.SCAN_ACTION")
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_EXPORTED) else registerReceiver(receiver,f)
        mainHandler.postDelayed(poll,10000)
    }
    override fun onPause(){
        foreground=false;mainHandler.removeCallbacks(poll);pairMode=false;pauseFlow();clearScanInput()
        try{unregisterReceiver(receiver)}catch(e:Exception){};super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        if(!hasFocus && ::b.isInitialized && work.streaming)pauseFlow()
    }
    override fun onDestroy(){scope.cancel();mainHandler.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun listeners(){
        b.spinnerProfiles.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                if(work.profile!=null || position !in profiles.indices)return
                selected=position;prefs.edit().putInt("selected_profile_index",position).apply();applyProfile(profiles[position]);render()
            }
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        b.btnSaveProfile.setOnClickListener {attempt{
            check(work.profile==null && snapshot.current==null){"Профиль заблокирован на время работы"}
            saveProfile(inputProfile());editorOpen=false;render();log("Профиль и состав сохранены")
        }}
        b.btnEditProfile.setOnClickListener {
            if(work.profile!=null || snapshot.current!=null)return@setOnClickListener
            editorOpen=!editorOpen;applyProfile(profiles[selected]);render()
        }
        b.btnStartWork.setOnClickListener {attempt{
            val p=if(editorOpen)inputProfile() else profiles[selected]
            work.enter(p,snapshot.current)
            try {
                if(editorOpen)saveProfile(p)
                check(prefs.edit().putString("work_session_profile",gson.toJson(work.profile)).commit()) {"Не удалось сохранить выбранный профиль"}
            } catch(e:Exception) {work.leave(null);throw e}
            editorOpen=false;clearScanInput();hideKeyboard();render()
            b.workPage.scrollTo(0,0);b.etScanInput.requestFocus()
            log("Начата работа: ${p.title}. Профиль нельзя переключить внутри сборки.")
        }}
        b.btnBackProfiles.setOnClickListener {requestProfilePage()}
        b.btnFlow.setOnClickListener {if(work.streaming)pauseFlow() else attempt{startFlow()}}
        b.btnDeleteProfile.setOnClickListener {
            if(work.profile!=null || snapshot.current!=null)return@setOnClickListener
            if(profiles.size<=1){toast("Единственный профиль не удаляется");return@setOnClickListener}
            guardDialog().setTitle("Удалить профиль?").setMessage("Собранные наборы останутся в очереди со своим ИНН и составом.")
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->
                    profiles.removeAt(selected);selected=0
                    prefs.edit().putString("profiles_json",gson.toJson(profiles)).putInt("selected_profile_index",0).commit();profileSpinner()
                }.show()
        }
        b.btnConnectPc.setOnClickListener{connectDialog()}
        b.btnSendDraft.setOnClickListener{pauseFlow();sync(true,true)}
        b.btnRefresh.setOnClickListener{pauseFlow();sync(false,true)}
        b.btnResetCurrentSet.setOnClickListener{
            val j=snapshot.current?:return@setOnClickListener
            guardDialog().setTitle("Сбросить незавершённый набор?")
                .setMessage("Пачек: ${j.children.size}. Этот набор ещё не передавался на ПК. Готовые наборы не будут удалены.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сбросить"){_,_->snapshot.current=null;persist();pauseFlow();clearScanInput();render()}.show()
        }
        b.btnShareLog.setOnClickListener{share(logFile,"text/plain")}
        b.btnBackup.setOnClickListener{
            guardDialog().setTitle("Экспорт резервной копии")
                .setMessage("Файл содержит коды маркировки и ИНН, но не ключ подключения к ПК. Передавайте только себе.")
                .setNegativeButton("Отмена",null).setPositiveButton("Экспортировать"){_,_->attempt{
                    val f=File(getExternalFilesDir(null),"chz-queue-backup.json");f.writeText(gson.toJson(snapshot));share(f,"application/json")
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
            val type=object:TypeToken<List<OrganizationProfile>>(){}.type
            val saved:List<OrganizationProfile>?=gson.fromJson(prefs.getString("profiles_json",null),type)
            if(saved!=null)profiles.addAll(saved)
        }catch(e:Exception){log("Старые профили не прочитаны. Исходные настройки не перезаписаны.")}
        if(profiles.isEmpty())profiles.add(OrganizationProfile(title="Основной профиль"))
        selected=prefs.getInt("selected_profile_index",0).coerceIn(0,profiles.lastIndex);profileSpinner()
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
        b.etComposition.setText(template.expected.entries.joinToString("\n"){"${it.key}=${it.value}"})
        if(p.pg!="grocery")log("В старом профиле была группа ${p.pg}. В этой версии только grocery; проверьте товары перед сохранением.")
    }
    private fun inputProfile():OrganizationProfile {
        val inn=b.etInn.text.toString().trim();require(Codes.inn(inn)){"Проверьте ИНН и контрольные цифры"}
        val title=b.etProfileTitle.text.toString().trim();require(title.isNotBlank()){"Назовите профиль, например «Кириешки 6 / ИП»"}
        val gtin=b.etSetGtin.text.toString().trim();require(Codes.gtin(gtin)){"Заполните 14-значный GTIN НАБОРА из Национального каталога"}
        return OrganizationProfile(inn,"grocery",title,if(b.spinnerEnvironment.selectedItemPosition==1)"sandbox" else "production",
            Template(gtin,Codes.composition(b.etComposition.text.toString())))
    }
    private fun scan(raw:String){
        if(halted || !foreground || dialogDepth>0)return
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
        try { attempt{
            val profile=checkNotNull(work.profile){"Сначала выберите профиль"}
            val code=Codes.normalize(raw)
            val now=SystemClock.elapsedRealtime()
            if(code==lastCode && now-lastCodeAt<700)return@attempt
            lastCode=code;lastCodeAt=now
            val current=snapshot.current
            require(current==null || work.owns(current)){"Состав незавершённого набора не соответствует рабочему профилю"}
            val env=profile.environment
            require(snapshot.jobs.none{it.status!="CANCELLED_LOCAL"&&it.environment==env&&(it.setCode==code||it.children.contains(code))}){"Код уже использован в другом собранном наборе"}
            if(current==null){
                require(code.substring(2,16)==profile.template.setGtin){"Сначала сканируйте код НАБОРА с GTIN ${profile.template.setGtin}"}
                snapshot.current=Job(environment=profile.environment,inn=profile.inn,template=profile.template,setCode=code)
                if(!persist())return@attempt
                log("Набор открыт: …${code.takeLast(6)}")
            }else{
                require(code!=current.setCode){"Это код самого набора"};require(!current.children.contains(code)){"Дубликат пачки в наборе"}
                val gtin=code.substring(2,16);val expected=current.template.expected[gtin]?:0
                require(expected>0){"Этого GTIN нет в составе шаблона: $gtin"}
                require(current.children.count{it.substring(2,16)==gtin}<expected){"По GTIN $gtin уже набрано нужное количество"}
                current.children.add(code)
                if(current.children.size==current.template.expected.values.sum()){
                    current.status="LOCAL_READY";snapshot.jobs.add(current);snapshot.current=null
                    if(!persist())return@attempt
                    log("Собран на ТСД, не в ЧЗ: …${current.setCode.takeLast(6)}");toast("Набор сохранён. Можно сканировать следующий.")
                }else{
                    if(!persist())return@attempt
                    // Only the single guarded flow scheduler may restart the scanner.
                }
            }
            render()
        }} finally {scheduleScan(220)}
    }
    private fun sync(upload:Boolean,notify:Boolean){
        if(networkBusy||halted)return
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
        require(reply.id==original.id&&reply.inn==original.inn&&reply.environment==original.environment&&
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
    private fun persist():Boolean=try{store.save(snapshot);true}catch(e:Exception){storageFailure(e);false}
    private fun storageFailure(e:Exception){
        halted=true;pauseFlow()
        guardDialog().setTitle("Сканирование остановлено: ошибка хранения")
            .setMessage("${e.message}\nНе удаляйте приложение и его данные. Очередь не будет перезаписана пустой.")
            .setPositiveButton("Понятно",null).show();render()
    }
    private fun render(){
        val j=snapshot.current;val p=work.profile;val locked=p!=null||j!=null||halted
        b.profilePage.visibility=if(p==null)View.VISIBLE else View.GONE
        b.workPage.visibility=if(p!=null)View.VISIBLE else View.GONE
        b.profileEditor.visibility=if(editorOpen)View.VISIBLE else View.GONE
        b.btnEditProfile.text=if(editorOpen)"Закрыть настройки без сохранения" else "Настроить профиль"
        b.btnStartWork.text=if(editorOpen)"Сохранить и начать работу" else "Начать работу"
        b.btnStartWork.isEnabled=p==null&&!halted
        b.btnEditProfile.isEnabled=!locked
        val chosen=profiles.getOrNull(selected)
        b.tvProfileSummary.text=chosen?.let{"${it.title}\nИНН: ${it.inn.ifBlank{"не заполнен"}}\n${if(it.environment=="sandbox")"ТЕСТОВЫЙ контур" else "РЕАЛЬНЫЙ ЧЗ"}\nGTIN набора: ${it.template.setGtin.ifBlank{"не заполнен"}} · ${it.template.expected.values.sum()} шт"}?:"Выберите профиль"
        b.tvWorkProfile.text=p?.let{"${it.title}\nИНН ${it.inn} · ${if(it.environment=="sandbox")"ТЕСТОВЫЙ контур" else "РЕАЛЬНЫЙ ЧЗ"}\nПрофиль зафиксирован до выхода из сборки"}?:""
        b.btnFlow.text=if(work.streaming)"ПАУЗА потока" else "СТАРТ потока"
        b.btnFlow.isEnabled=p!=null&&!halted&&!pairMode&&dialogDepth==0
        b.btnScan.isEnabled=p!=null&&!halted&&!pairMode&&dialogDepth==0
        b.tvFlowState.text=when {work.streaming->"ПОТОК: подносите коды по очереди. После пачек — код следующего набора."; p!=null->"Поток на паузе. Нажмите СТАРТ один раз для всей серии наборов.";else->""}
        b.btnResetCurrentSet.isEnabled=j!=null&&!halted
        b.root.keepScreenOn=work.streaming

        listOf<View>(b.spinnerProfiles,b.etProfileTitle,b.etInn,b.spinnerEnvironment,b.etSetGtin,b.etComposition,b.btnSaveProfile,b.btnDeleteProfile).forEach{it.isEnabled=!locked}
        b.tvConnection.text=if(pc.url.isBlank())"ПК не подключён" else "ПК: ${pc.url}"
        b.tvScanState.text=if(halted)"ОСТАНОВЛЕНО: ошибка сохранения" else if(pairMode)"Сканируйте QR подключения ПК" else if(j==null)"Сканируйте DataMatrix НАБОРА" else "Сканируйте пачки: ${j.children.size} / ${j.template.expected.values.sum()}"
        b.tvCurrentSet.text=j?.let{"Набор: ${it.setCode}\nИНН ${it.inn} · ${it.environment}"}?:"Текущий набор не открыт"
        b.tvProgress.text=j?.template?.expected?.entries?.joinToString("\n"){(g,n)->"$g: ${j.children.count{it.substring(2,16)==g}} / $n"}?:"Проверьте шаблон перед сборкой"
        val visibleJobs=snapshot.jobs.filter{work.owns(it)}
        b.tvTotalCompletedSets.text="Этот профиль: ${visibleJobs.size}; на ТСД: ${visibleJobs.count{!it.uploaded}}; подтверждено ЧЗ: ${visibleJobs.count{it.status=="VERIFIED"}}"
        b.btnSendDraft.isEnabled=!networkBusy&&!halted;b.btnRefresh.isEnabled=!networkBusy&&!halted
        b.tvQueue.text=visibleJobs.asReversed().take(100).joinToString("\n\n"){
            "…${it.setCode.takeLast(6)} · ИНН ${it.inn} · ${it.children.size} шт\n${Labels.status(it.status)}${if(it.detail.isNotBlank())"\n"+it.detail.take(350)else ""}${it.documentId?.let{d->"\nДокумент: $d"}?:""}"
        }.ifBlank{"Готовых наборов пока нет"}
    }
    private fun attempt(block:()->Unit){try{block()}catch(e:Exception){toast(e.message?:"Ошибка");log(e.message?:"Ошибка")}}
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

    private fun saveProfile(p:OrganizationProfile){
        val next=profiles.toMutableList();val at=next.indexOfFirst{it.title==p.title}
        val index=if(at>=0){next[at]=p;at}else{next.add(p);next.lastIndex}
        check(prefs.edit().putString("profiles_json",gson.toJson(next)).putInt("selected_profile_index",index).commit()) {"Не удалось сохранить профиль"}
        profiles.clear();profiles.addAll(next);selected=index;profileSpinner()
    }
    private fun restoreWork(){
        if(halted)return
        val saved=runCatching{gson.fromJson(prefs.getString("work_session_profile",null),OrganizationProfile::class.java)}.getOrNull()
        val current=snapshot.current
        val candidate=if(current!=null){
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
        if(snapshot.current!=null){
            guardDialog().setTitle("Набор ещё не закончен")
                .setMessage("Переключение профиля заблокировано. Завершите сборку или отдельно сбросьте незавершённый набор. Его состав сохранён.")
                .setPositiveButton("Продолжить сборку",null).show();return
        }
        guardDialog().setTitle("Выйти к выбору профиля?")
            .setMessage("Поток остановлен. Готовые наборы останутся в очереди со своим ИНН и составом. На следующем экране можно выбрать другой профиль.")
            .setNegativeButton("Остаться",null).setPositiveButton("К профилям"){_,_->attempt{
                check(prefs.edit().remove("work_session_profile").commit()) {"Не удалось сохранить выход из профиля"}
                work.leave(snapshot.current);clearScanInput();applyProfile(profiles[selected]);render();b.profilePage.scrollTo(0,0)
            }}.show()
    }
    private fun guardDialog():AlertDialog.Builder{
        pauseFlow();dialogDepth++
        return AlertDialog.Builder(this).setOnDismissListener{dialogDepth=(dialogDepth-1).coerceAtLeast(0);render()}
    }
    private fun canReceive()=work.canReceive(foreground,halted,dialogDepth>0)&&!pairMode
    private fun canFlow()=work.canTrigger(foreground,halted,dialogDepth>0)&&!pairMode
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
