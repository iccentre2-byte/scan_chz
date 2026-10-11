package com.tsd.czsetcollector

import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        b.tvAppVersion.text="1.3.0-rc1 · ОСУ / подпись на ПК"
        try {snapshot=store.load()} catch(e:Exception){storageFailure(e)}
        try {pc=gson.fromJson(prefs.getString("pc_connection",null),PcConfig::class.java)?:PcConfig()}catch(e:Exception){pc=PcConfig()}
        b.spinnerEnvironment.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,
            listOf("PRODUCTION — реальный ЧЗ","SANDBOX — тестовый контур"))
        loadProfiles();listeners();render();log("Запуск 1.3.0-rc1. На Android нет подписи и прямой отправки в ЧЗ.")
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
        foreground=false;mainHandler.removeCallbacks(poll);setContinuousScanMode(false)
        try{unregisterReceiver(receiver)}catch(e:Exception){};super.onPause()
    }
    override fun onDestroy(){scope.cancel();mainHandler.removeCallbacksAndMessages(null);super.onDestroy()}
    private fun listeners(){
        b.spinnerProfiles.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                selected=position;prefs.edit().putInt("selected_profile_index",position).apply();applyProfile(profiles[position])
            }
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        b.btnSaveProfile.setOnClickListener {attempt{
            val p=inputProfile();val old=profiles.indexOfFirst{it.title==p.title}
            if(old>=0){profiles[old]=p;selected=old}else{profiles.add(p);selected=profiles.lastIndex}
            prefs.edit().putString("profiles_json",gson.toJson(profiles)).putInt("selected_profile_index",selected).commit()
            profileSpinner();log("Профиль и состав сохранены")
        }}
        b.btnDeleteProfile.setOnClickListener {
            if(profiles.size<=1){toast("Единственный профиль не удаляется");return@setOnClickListener}
            AlertDialog.Builder(this).setTitle("Удалить профиль?").setMessage("Собранные наборы останутся в очереди со своим ИНН и составом.")
                .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->
                    profiles.removeAt(selected);selected=0
                    prefs.edit().putString("profiles_json",gson.toJson(profiles)).putInt("selected_profile_index",0).commit();profileSpinner()
                }.show()
        }
        b.btnConnectPc.setOnClickListener{connectDialog()}
        b.btnSendDraft.setOnClickListener{setContinuousScanMode(false);sync(true,true)}
        b.btnRefresh.setOnClickListener{sync(false,true)}
        b.btnResetCurrentSet.setOnClickListener{
            val j=snapshot.current?:return@setOnClickListener
            AlertDialog.Builder(this).setTitle("Сбросить незавершённый набор?")
                .setMessage("Пачек: ${j.children.size}. Этот набор ещё не передавался на ПК. Готовые наборы не будут удалены.")
                .setNegativeButton("Отмена",null).setPositiveButton("Сбросить"){_,_->snapshot.current=null;persist();setContinuousScanMode(false);render()}.show()
        }
        b.btnShareLog.setOnClickListener{share(logFile,"text/plain")}
        b.btnBackup.setOnClickListener{
            AlertDialog.Builder(this).setTitle("Экспорт резервной копии")
                .setMessage("Файл содержит коды маркировки и ИНН, но не ключ подключения к ПК. Передавайте только себе.")
                .setNegativeButton("Отмена",null).setPositiveButton("Экспортировать"){_,_->attempt{
                    val f=File(getExternalFilesDir(null),"chz-queue-backup.json");f.writeText(gson.toJson(snapshot));share(f,"application/json")
                }}.show()
        }
        b.btnCheckUpdate.setOnClickListener{
            AlertDialog.Builder(this).setTitle("Обновление опытной версии")
                .setMessage("Автозагрузка старого app-release.apk отключена. Новый APK устанавливайте только после проверки версии и подписи. Старую программу и её данные не удаляйте.")
                .setPositiveButton("Понятно",null).show()
        }
        b.etScanInput.showSoftInputOnFocus=false
        b.etScanInput.setOnEditorActionListener{_,_,_->val value=b.etScanInput.text.toString();b.etScanInput.setText("");if(value.isNotBlank())scan(value);true}
        b.btnScan.setOnClickListener{b.etScanInput.requestFocus();startSoftwareScanTrigger()}
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
        val active=snapshot.current
        b.etProfileTitle.setText(p.title);b.etInn.setText(active?.inn?:p.inn)
        b.spinnerEnvironment.setSelection(if((active?.environment?:p.environment)=="sandbox")1 else 0)
        val template=active?.template?:p.template
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
        if(halted)return
        if(pairMode){pairMode=false;setContinuousScanMode(false);attempt{
            require(raw.length<=4096){"Слишком длинный QR подключения"};val code=Codes.withoutPrefix(raw);require(code.startsWith("CZPAIR1:")){"Это не QR подключения из помощника ПК"}
            val decoded=String(Base64.decode(code.removePrefix("CZPAIR1:"),Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),Charsets.UTF_8)
            val c=gson.fromJson(decoded,PcConfig::class.java);PcClient.validate(c)
            AlertDialog.Builder(this).setTitle("Подключить этот ПК?")
                .setMessage("Адрес: ${c.url}\nSHA-256: ${c.pin}\n\nСверьте адрес и отпечаток с экраном ПК. КЭП не передаётся на ТСД.")
                .setNegativeButton("Отмена",null).setPositiveButton("Подключить"){_,_->savePc(c)}.show()
        };return}
        if(Codes.withoutPrefix(raw).startsWith("CZPAIR1:")){toast("Сначала нажмите «Подключить ПК» → «Сканировать QR»");return}
        attempt{
            val code=Codes.normalize(raw)
            val current=snapshot.current
            val env=current?.environment ?: if(b.spinnerEnvironment.selectedItemPosition==1)"sandbox" else "production"
            require(snapshot.jobs.none{it.status!="CANCELLED_LOCAL"&&it.environment==env&&(it.setCode==code||it.children.contains(code))}){"Код уже использован в другом собранном наборе"}
            if(current==null){
                val profile=inputProfile();require(code.substring(2,16)==profile.template.setGtin){"Сначала сканируйте код НАБОРА с GTIN ${profile.template.setGtin}"}
                snapshot.current=Job(environment=profile.environment,inn=profile.inn,template=profile.template,setCode=code)
                if(!persist())return@attempt
                log("Набор открыт: …${code.takeLast(6)}");setContinuousScanMode(true)
            }else{
                require(code!=current.setCode){"Это код самого набора"};require(!current.children.contains(code)){"Дубликат пачки в наборе"}
                val gtin=code.substring(2,16);val expected=current.template.expected[gtin]?:0
                require(expected>0){"Этого GTIN нет в составе шаблона: $gtin"}
                require(current.children.count{it.substring(2,16)==gtin}<expected){"По GTIN $gtin уже набрано нужное количество"}
                current.children.add(code)
                if(current.children.size==current.template.expected.values.sum()){
                    current.status="LOCAL_READY";snapshot.jobs.add(current);snapshot.current=null;setContinuousScanMode(false)
                    if(!persist())return@attempt
                    log("Собран на ТСД, не в ЧЗ: …${current.setCode.takeLast(6)}");toast("Набор сохранён. Можно сканировать следующий.")
                }else{
                    if(!persist())return@attempt
                    mainHandler.postDelayed({if(foreground)startSoftwareScanTrigger()},120)
                }
            }
            render()
        }
    }
    private fun sync(upload:Boolean,notify:Boolean){
        if(networkBusy||halted)return
        if(pc.url.isBlank()){if(notify)connectDialog();return}
        val toUpload=if(upload)snapshot.jobs.filter{!it.uploaded&&it.status=="LOCAL_READY"}else emptyList()
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
        val layout=LinearLayout(this);layout.orientation=LinearLayout.VERTICAL;layout.setPadding(24,12,24,12)
        val url=EditText(this).apply{hint="https://192.168.1.10:18765";setText(pc.url)}
        val key=EditText(this).apply{hint="Ключ подключения к ПК";setText(pc.key);inputType=129}
        val pin=EditText(this).apply{hint="SHA-256 сертификата ПК (64 символа)";setText(pc.pin)}
        listOf(url,key,pin).forEach{layout.addView(it)}
        AlertDialog.Builder(this).setTitle("Подключить помощник ПК").setView(layout)
            .setNegativeButton("Отмена",null).setNeutralButton("Сканировать QR"){_,_->pairMode=true;toast("Отсканируйте QR из окна помощника ПК");startSoftwareScanTrigger()}
            .setPositiveButton("Сохранить"){_,_->attempt{val c=PcConfig(url.text.toString().trim().trimEnd('/'),key.text.toString().trim(),pin.text.toString().trim());PcClient.validate(c);savePc(c)}}.show()
    }
    private fun savePc(c:PcConfig){pc=c;prefs.edit().putString("pc_connection",gson.toJson(c)).commit();render();log("Подключение к ПК сохранено: ${c.url}")}
    private fun persist():Boolean=try{store.save(snapshot);true}catch(e:Exception){storageFailure(e);false}
    private fun storageFailure(e:Exception){
        halted=true;setContinuousScanMode(false)
        AlertDialog.Builder(this).setTitle("Сканирование остановлено: ошибка хранения")
            .setMessage("${e.message}\nНе удаляйте приложение и его данные. Очередь не будет перезаписана пустой.")
            .setPositiveButton("Понятно",null).show();render()
    }
    private fun render(){
        val j=snapshot.current;val locked=j!=null||halted
        listOf<View>(b.spinnerProfiles,b.etProfileTitle,b.etInn,b.spinnerEnvironment,b.etSetGtin,b.etComposition,b.btnSaveProfile,b.btnDeleteProfile).forEach{it.isEnabled=!locked}
        b.tvConnection.text=if(pc.url.isBlank())"ПК не подключён" else "ПК: ${pc.url}"
        b.tvScanState.text=if(halted)"ОСТАНОВЛЕНО: ошибка сохранения" else if(pairMode)"Сканируйте QR подключения ПК" else if(j==null)"Сканируйте DataMatrix НАБОРА" else "Сканируйте пачки: ${j.children.size} / ${j.template.expected.values.sum()}"
        b.tvCurrentSet.text=j?.let{"Набор: ${it.setCode}\nИНН ${it.inn} · ${it.environment}"}?:"Текущий набор не открыт"
        b.tvProgress.text=j?.template?.expected?.entries?.joinToString("\n"){(g,n)->"$g: ${j.children.count{it.substring(2,16)==g}} / $n"}?:"Проверьте шаблон перед сборкой"
        b.tvTotalCompletedSets.text="Всего в очереди: ${snapshot.jobs.size}; на ТСД: ${snapshot.jobs.count{!it.uploaded}}; подтверждено ЧЗ: ${snapshot.jobs.count{it.status=="VERIFIED"}}"
        b.btnSendDraft.isEnabled=!networkBusy&&!halted;b.btnRefresh.isEnabled=!networkBusy&&!halted
        b.tvQueue.text=snapshot.jobs.asReversed().take(100).joinToString("\n\n"){
            "…${it.setCode.takeLast(6)} · ИНН ${it.inn} · ${it.children.size} шт\n${Labels.status(it.status)}${if(it.detail.isNotBlank())"\n"+it.detail.take(350)else ""}${it.documentId?.let{d->"\nДокумент: $d"}?:""}"
        }.ifBlank{"Готовых наборов пока нет"}
    }
    private fun attempt(block:()->Unit){try{block()}catch(e:Exception){toast(e.message?:"Ошибка");log(e.message?:"Ошибка");if(snapshot.current!=null)mainHandler.postDelayed({if(foreground)startSoftwareScanTrigger()},200)}}
    private fun toast(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
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
    private fun setContinuousScanMode(enable: Boolean) {
        val m3Intent = Intent("com.m3.scan.action.SCANNER_SETTING_CHANGE").apply {
            putExtra("setting_name", "continuous_scan")
            putExtra("setting_value", if (enable) 1 else 0)
        }
        sendBroadcast(m3Intent)

        val directIntent = Intent("com.m3.scan.action.CONTINUOUS_SCAN").apply {
            putExtra("enable", enable)
        }
        sendBroadcast(directIntent)

        if (enable) {
            startSoftwareScanTrigger()
        } else {
            stopSoftwareScanTrigger()
        }
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
