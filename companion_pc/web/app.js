'use strict';
const $=id=>document.getElementById(id);
const key=location.hash.slice(1)||sessionStorage.getItem('chzAdminKey')||'';
if(key)sessionStorage.setItem('chzAdminKey',key);
history.replaceState(null,'',location.pathname);
let jobs=[],certs=[],chosen=new Set(),busy=false,pairUrl=null,working=false,view='waiting',refreshing=false,selectionLimit=50;
let syncGroups=[],syncSupported=false,adminExpired=false,certBusy=false,busyAction=null,activeAction=null;
const SIGNABLE=new Set(['WAITING_SIGNATURE','BLOCKED']);
const CHECKABLE=new Set(['WAITING_SIGNATURE','BLOCKED','SUBMITTED','PROCESSING','CHECK_REQUIRED','UNKNOWN']);
const VIEW_LABELS={waiting:'Ожидают подписи',processing:'В обработке',processed:'Обработанные',problems:'Проблемы / отменённые'};
const VIEW_HINTS={waiting:'Выберите наборы одного ИНН, контура и товарной группы. Они попадут в один документ ЧЗ.',processing:'Переданные наборы можно проверять без повторной отправки.',processed:'Состав этих наборов проверен. Записи сохранены в архиве и не попадают в выбор для подписи.',problems:'Отменённые и отклонённые записи сохранены. При неизвестном результате доступна проверка без повторной отправки.'};
const cards=new Map();
const groupKey=j=>JSON.stringify([j.inn,j.environment,j.pg||'grocery']);
const groupLabel=j=>`ИНН ${j.inn} · ${j.environment==='production'?'PRODUCTION':'SANDBOX'} · ${j.pg||'grocery'}`;
const jobView=j=>SIGNABLE.has(j.status)?'waiting':j.status==='VERIFIED'?'processed':['SUBMITTING','SUBMITTED','PROCESSING','CHECK_REQUIRED'].includes(j.status)?'processing':'problems';
const selectable=j=>(SIGNABLE.has(j.status)&&!j.documentId)||CHECKABLE.has(j.status);
const visibleJobs=()=>jobs.filter(j=>jobView(j)===view&&(!$('groupFilter').value||groupKey(j)===$('groupFilter').value));
const selectedJobs=()=>jobs.filter(j=>chosen.has(j.id));
const text=(tag,value,css)=>{const n=document.createElement(tag);n.textContent=value;if(css)n.className=css;return n;};
async function api(path,body){
 if(adminExpired)throw Error('Эта панель относится к прежнему запуску. Откройте новую панель по ссылке из окна Start_PC.cmd.');
 const controller=new AbortController();const timeout=path==='/api/certificates'?190000:body===undefined?15000:20000;
 let timer;
 const expired=new Promise((_,reject)=>{timer=setTimeout(()=>{reject(Error(body===undefined?'Помощник не ответил вовремя. Повторите обновление списка.':'Ожидание ответа истекло. Запрос не повторялся; операция могла начаться. Обновите список и проверьте её состояние.'));controller.abort();},timeout);});
 try{
  return await Promise.race([(async()=>{
   const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'X-Admin-Key':key,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',redirect:'error',signal:controller.signal});
   if(r.status===401){adminExpired=true;working=false;busy=false;sessionStorage.removeItem('chzAdminKey');updateActions();throw Error('Помощник перезапущен: откройте новую панель по ссылке из окна Start_PC.cmd. Очередь сохранена.');}
   let data;try{data=await r.json();}catch(error){throw Error('Помощник вернул неполный или неверный ответ. Обновите список; запрос действия не повторяется.');}
   if(!r.ok)throw Error(data.error||('HTTP '+r.status));return data;
  })(),expired]);
 }finally{clearTimeout(timer);}
}
function message(value,error=false){$('message').textContent=value;$('message').className=error?'error':'';}
function updateActions(){
 const selected=selectedJobs();const locked=working||busy||adminExpired;
 const canSign=selected.length>0&&selected.every(j=>SIGNABLE.has(j.status)&&!j.documentId);
 const canCheck=selected.length>0&&selected.every(j=>CHECKABLE.has(j.status));
 const common=adminExpired?'Откройте новую панель из окна Start_PC.cmd: ключ прежней панели устарел.':working?'Выполняется операция; её этап и прогресс показаны ниже.':busy?'Запрос выполняется; дождитесь ответа помощника.':'';
 const certificate=certBusy?'Идёт поиск сертификатов на ПК.':!$('certificates').value?'Найдите и выберите сертификат УКЭП.':'';
 const scoped=jobs.filter(j=>!$('groupFilter').value||groupKey(j)===$('groupFilter').value);
 const unsent=scoped.some(j=>SIGNABLE.has(j.status)&&!j.documentId);
 const signReason=common||(!selected.length?(unsent?'Выберите наборы на вкладке «Ожидают подписи».':'Нет наборов для подписи: отправленные и обработанные повторно не подписываются.'):!canSign?'Подписать можно только наборы, ещё не отправленные в ЧЗ.':certificate);
 const checkReason=common||(!selected.length?(scoped.some(j=>CHECKABLE.has(j.status))?'Выберите неподтверждённые наборы для проверки.':'Нет неподтверждённых наборов для проверки.'):!canCheck?'Обработанные и отменённые наборы не требуют проверки.':certificate);
 const cancelReason=common||(!selected.length?(unsent?'Выберите наборы, ещё не отправленные в ЧЗ.':'Нет наборов до отправки, доступных для отмены.'):!canSign?'Отменить можно только наборы до отправки в ЧЗ.':'');
 for(const [id,reason] of [['sign',signReason],['check',checkReason],['cancelUnsent',cancelReason]]){$(id).disabled=!!reason;$(id).title=reason;}
 $('actionReasons').textContent=`Подписать: ${signReason||'доступно'}\nПроверить: ${checkReason||'доступно'}\nОтменить: ${cancelReason||'доступно'}`;
 const syncGroup=selectedSyncGroup();
 const syncReason=common||(!syncSupported?'Обновите помощник до версии 1.3.4.':!syncGroup?'Выберите организацию и контур над очередью.':!syncGroup.pending?(syncGroup.allVerified?'Все наборы этой организации подтверждены.':'Нет наборов, ожидающих проверки. Смотрите разделы обработанных и проблем.'):certificate);
 $('loginCheck').disabled=!!syncReason;$('loginCheck').title=syncReason;$('syncActionReason').textContent=syncReason||`Проверить все неподтверждённые: ${syncGroup.pending}. Новые документы не отправляются.`;
 $('refreshView').disabled=adminExpired||refreshing;
 $('loadCertificates').disabled=adminExpired||certBusy;
 for(const id of ['sign','check','cancelUnsent','loginCheck'])$(id).setAttribute('aria-busy',String(!adminExpired&&((busy&&busyAction===id)||(working&&activeAction===id))));
 $('loadCertificates').setAttribute('aria-busy',String(certBusy&&!adminExpired));
 $('refreshView').setAttribute('aria-busy',String(refreshing&&!adminExpired));
 $('adminExpired').hidden=!adminExpired;
 $('selectAll').disabled=locked||!visibleJobs().some(j=>selectable(j));
 $('selectAll').textContent=`Выбрать все подходящие (до ${selectionLimit})`;
 $('clearSelection').disabled=locked||!selected.length;
 $('selectionSummary').textContent=selected.length?`Выбрано: ${selected.length}${canSign?' · 1 документ ЧЗ':''}`:'Наборы не выбраны';
 $('selectionDocument').hidden=!canSign;
 if(canSign)$('selectedDocument').textContent=JSON.stringify({participantId:selected[0].inn,aggregationUnits:selected.map(j=>({unitSerialNumber:j.setCode,sntins:j.children}))},null,2);
 for(const [id,c] of cards){const j=jobs.find(j=>j.id===id);c.cb.checked=chosen.has(id);c.cb.disabled=locked||!j||!selectable(j);}
}
function selectedSyncGroup(){
 const explicit=$('groupFilter').value||(selectedJobs()[0]?groupKey(selectedJobs()[0]):'');
 if(explicit)return syncGroups.find(group=>groupKey(group)===explicit)||null;
 const pending=syncGroups.filter(group=>group.pending>0);return pending.length===1?pending[0]:syncGroups.length===1?syncGroups[0]:null;
}
function drawSync(){
 const relevant=syncGroups.filter(group=>!$('groupFilter').value||groupKey(group)===$('groupFilter').value);
 const label=stamp=>stamp?new Date(stamp*1000).toLocaleString('ru-RU'):'ещё не было';
 $('syncStatus').textContent=relevant.map(group=>{
  let state=!group.pending?(group.allVerified?'Все наборы подтверждены.':'Нет наборов, ожидающих проверки. Смотрите разделы обработанных и проблем.'):group.requiresLogin?(group.session==='expired'?'Вход в ЧЗ истёк.':'После запуска помощника вход в ЧЗ ещё не выполнен.')+' Нажмите «Войти в ЧЗ и проверить все неподтверждённые».':group.error?'Проверка не завершена; вход сохранён. Автопроверка повторится.':'Вход активен; автопроверка выполняется каждые 20 секунд.';
  return `${groupLabel(group)} · ожидают проверки: ${group.pending}${group.verified!==undefined?' · подтверждены: '+group.verified:''}${group.failed?' · отклонены: '+group.failed:''}${group.cancelled?' · отменены: '+group.cancelled:''}\n${state}\nПоследняя попытка: ${label(group.lastAttempt)} · завершённая проверка: ${label(group.lastSuccess)}${group.error?'\nОшибка: '+group.error:''}`;
 }).join('\n\n')||'Наборов для проверки пока нет.';
}
function pruneSelection(){
 const eligible=new Set(visibleJobs().filter(selectable).map(j=>j.id));
 chosen=new Set([...chosen].filter(id=>eligible.has(id)).slice(0,selectionLimit));
}
function changeSelection(j,checked){
 if(working||busy||!selectable(j)){draw();return;}
 if(checked){
  const first=selectedJobs()[0];
  if(first&&groupKey(first)!==groupKey(j)){message('Выберите одну организацию, один контур и одну товарную группу. Сначала снимите текущий выбор.',true);draw();return;}
  if(chosen.size>=selectionLimit){message(`За одну операцию можно выбрать до ${selectionLimit} наборов. Остальные можно обработать следующей партией.`,true);draw();return;}
  chosen.add(j.id);
 }else chosen.delete(j.id);
 updateActions();
}
function updateGroups(){
 const groups=new Map(jobs.map(j=>[groupKey(j),groupLabel(j)]));
 const values=[['','Все организации'],...groups.entries()];
 const signature=JSON.stringify(values);
 if($('groupFilter').dataset.signature===signature)return;
 const previous=$('groupFilter').value;
 $('groupFilter').replaceChildren(...values.map(([value,label])=>{const option=text('option',label);option.value=value;return option;}));
 $('groupFilter').value=groups.has(previous)?previous:'';
 $('groupFilter').dataset.signature=signature;
}
function updateCard(j){
 let c=cards.get(j.id);
 if(!c){
  const card=text('article','','job');const label=document.createElement('label');
  const cb=document.createElement('input');cb.type='checkbox';
  const title=text('span','','title mono');label.append(cb,title);card.append(label);
  const meta=text('p','','meta'),status=text('p','','status'),detail=text('p',''),documentLine=text('p','','mono');
  card.append(meta,status,detail,documentLine);
  const details=document.createElement('details');details.append(text('summary','Проверить состав набора'));
  const expected=text('pre',''),product=text('pre','');details.append(expected,product);card.append(details);
  c={card,cb,title,meta,status,detail,documentLine,expected,product,details,payloadHash:null};cards.set(j.id,c);
  cb.onchange=()=>{const current=jobs.find(x=>x.id===j.id);if(current)changeSelection(current,cb.checked);};
 }
 c.cb.hidden=!selectable(j);c.cb.setAttribute('aria-label','Выбрать набор '+j.setCode);
 c.title.textContent=j.setCode;
 c.meta.textContent=`${j.environment==='production'?'PRODUCTION — РЕАЛЬНЫЙ ЧЗ':'SANDBOX — ТЕСТОВЫЙ КОНТУР'} · ИНН ${j.inn} · ${j.children.length} пачек · ${j.pg||'grocery'}`;
 c.status.textContent=j.statusRu;c.status.className='status'+(j.status==='VERIFIED'?' success':['UNKNOWN','REJECTED','BLOCKED'].includes(j.status)?' error':'');
 c.detail.textContent=j.detail||'';
 const count=j.documentId?jobs.filter(x=>x.documentId===j.documentId&&groupKey(x)===groupKey(j)).length:0;
 c.documentLine.hidden=!j.documentId;c.documentLine.textContent=j.documentId?`Документ ЧЗ: ${j.documentId}${count>1?` · общий для ${count} наборов`:''}`:'';
 if(c.payloadHash!==j.payloadHash){
  c.expected.textContent=Object.entries(j.template.expected).map(([g,n])=>`${g} × ${n}`).join('\n');
  c.product.textContent=JSON.stringify({participantId:j.inn,aggregationUnits:[{unitSerialNumber:j.setCode,sntins:j.children}]},null,2);
  c.payloadHash=j.payloadHash;
 }
 return c.card;
}
function draw(){
 updateGroups();pruneSelection();
 drawSync();
 for(const [name,label] of Object.entries(VIEW_LABELS)){
  const tab=$('tab-'+name);tab.textContent=`${label} (${jobs.filter(j=>jobView(j)===name).length})`;tab.setAttribute('aria-pressed',String(view===name));
 }
 $('queueHint').textContent=VIEW_HINTS[view];
 const existing=new Set(jobs.map(j=>j.id));for(const id of cards.keys())if(!existing.has(id))cards.delete(id);
 const root=$('jobs');const nodes=visibleJobs().map(updateCard);const wanted=new Set(nodes);
 for(const child of [...root.children])if(!wanted.has(child))root.removeChild(child);
 nodes.forEach((node,i)=>{if(root.children[i]!==node)root.insertBefore(node,root.children[i]||null);});
 if(!nodes.length)root.append(text('p',!jobs.length?'Очередь пуста. Соберите набор на ТСД и нажмите «Передать на ПК».':'В этом разделе и выбранной организации наборов нет.','empty-queue'));
 updateActions();
}
async function refresh(){
 if(refreshing||adminExpired)return;
 refreshing=true;updateActions();
 try{
  const data=await api('/api/state');jobs=data.jobs;
  syncSupported=Array.isArray(data.syncGroups);syncGroups=data.syncGroups||[];
  selectionLimit=Number.isInteger(data.selectionLimit)&&data.selectionLimit>0&&data.selectionLimit<=500?data.selectionLimit:50;
  $('version').textContent='Версия '+data.version+' · опытная сборка';$('dataDir').textContent=data.dataDir;
  const stages={login:'Вход по УКЭП: при запросе введите PIN в окне КриптоПро',check:'Проверка статусов и составов',submit:'Подпись и отправка документа'};
  const states={RUNNING:'выполняется',QUEUED:'ожидает запуска',DONE:'завершено',ERROR:'ошибка'};
  $('operations').textContent=Object.values(data.operations).map(o=>`${o.action==='submit'?'Отправка':'Проверка'}: ${states[o.state]||o.state}, ${o.done}/${o.total}${['RUNNING','QUEUED'].includes(o.state)&&o.stage?' · '+(stages[o.stage]||o.stage):''}${o.documentId?' · документ '+o.documentId:''}${o.error?' · '+o.error:''}`).join('\n');
  const active=Object.values(data.operations).find(o=>['RUNNING','QUEUED'].includes(o.state));
  working=!!active;activeAction=active?(active.action==='submit'?'sign':active.action==='login-check'?'loginCheck':'check'):null;draw();
 }catch(error){working=false;activeAction=null;throw error;}finally{refreshing=false;updateActions();}
}
async function perform(action){
 if(working||busy)throw Error('Дождитесь завершения текущей операции');
 const selected=selectedJobs();const thumb=$('certificates').value;
 if(!selected.length)throw Error('Выберите хотя бы один набор');if(!thumb)throw Error('Выберите сертификат УКЭП');
 if(new Set(selected.map(groupKey)).size!==1)throw Error('Выберите одну организацию, один контур и одну товарную группу');
 if(action==='submit'&&selected.some(j=>!SIGNABLE.has(j.status)||j.documentId))throw Error('Есть уже отправленные наборы. Для них используйте «Проверить», а не повторную отправку.');
 if(action==='refresh'&&selected.some(j=>!CHECKABLE.has(j.status)))throw Error('Для проверки выберите переданные наборы с неподтверждённым результатом.');
 const c=certs.find(c=>c.thumbprint===thumb);
 const msg=`${action==='submit'?'ПОДПИСАТЬ И ОТПРАВИТЬ в ЧЗ':'Проверить в ЧЗ (без повторной отправки)'}\nНаборов: ${selected.length}${action==='submit'?' · один общий документ':''}\nИНН: ${selected[0].inn}\nКонтур: ${selected[0].environment}\nТоварная группа: ${selected[0].pg||'grocery'}\nСертификат: ${c?.subject||thumb}\n\n${action==='submit'?'Это не черновик. Вы проверили состав и действительно подтверждаете отправку?':'Будет выполнен вход по УКЭП и чтение сведений. Продолжить?'}`;
 if(!confirm(msg))return;
 busy=true;busyAction=action==='submit'?'sign':'check';updateActions();
 try{await api('/api/'+action,{confirmed:true,thumbprint:thumb,items:selected.map(j=>({id:j.id,hash:j.payloadHash}))});working=true;activeAction=busyAction;message('Операция запущена. При запросе введите PIN в окне КриптоПро.');await refresh();}finally{busy=false;busyAction=null;updateActions();}
}
async function showQr(){
 if(pairUrl)URL.revokeObjectURL(pairUrl);
 const host=$('hostSelect').value;
 const r=await fetch('/api/pair.svg?host='+encodeURIComponent(host),{headers:{'X-Admin-Key':key},cache:'no-store'});
 if(!r.ok)throw Error((await r.json()).error);pairUrl=URL.createObjectURL(await r.blob());$('pairImage').src=pairUrl;
}
function run(fn){return async()=>{try{await fn();}catch(e){message(String(e.message||e),true);}};}
$('showPair').onclick=run(async()=>{
 const c=await api('/api/connection');$('pairBox').hidden=false;$('hostSelect').replaceChildren();
 for(const h of c.addresses){const option=text('option',h);option.value=h;$('hostSelect').append(option);}
 $('manualPair').textContent=`Адрес: ${c.url||'Не определён'}\nКлюч ПК: ${c.deviceKey}\nSHA-256 сертификата ПК: ${c.certificateSha256}`;
 $('connectionText').textContent=c.url||'ПК не получил локальный адрес. Проверьте подключение к роутеру.';await showQr();
});
$('hostSelect').onchange=run(async()=>{const c=await api('/api/connection');const url=`https://${$('hostSelect').value}:${c.lanPort}`;$('connectionText').textContent=url;$('manualPair').textContent=`Адрес: ${url}\nКлюч ПК: ${c.deviceKey}\nSHA-256 сертификата ПК: ${c.certificateSha256}`;await showQr();});
$('loadCertificates').onclick=run(async()=>{
 certBusy=true;updateActions();message('Чтение сертификатов с ПК...');
 try{
  certs=(await api('/api/certificates',{})).certificates;
  $('certificates').replaceChildren(text('option','Выберите сертификат'));$('certificates').options[0].value='';
  for(const c of certs){const o=text('option',c.subject+' · до '+c.validTo);o.value=c.thumbprint;$('certificates').append(o);}
  message(certs.length?'Сертификаты найдены. Проверьте организацию.':'Не найдено действующих сертификатов с закрытым ключом.');
 }finally{certBusy=false;updateActions();}
});
$('certificates').onchange=()=>{const c=certs.find(c=>c.thumbprint===$('certificates').value);$('certDetail').textContent=c?'Отпечаток: '+c.thumbprint:'';updateActions();};
$('sign').onclick=run(()=>perform('submit'));$('check').onclick=run(()=>perform('refresh'));
$('refreshView').onclick=run(refresh);
$('backup').onclick=run(async()=>message('Копия сохранена: '+(await api('/api/backup',{})).path));
for(const name of Object.keys(VIEW_LABELS))$('tab-'+name).onclick=()=>{view=name;chosen.clear();draw();message('Раздел изменён; выбор снят. Отметьте наборы в этом разделе.');};
$('groupFilter').onchange=()=>{chosen.clear();draw();message('Организация или контур изменены; выбор наборов снят.');};
$('clearSelection').onclick=()=>{chosen.clear();updateActions();};
$('selectAll').onclick=run(async()=>{
 const candidates=visibleJobs().filter(selectable);if(!candidates.length)return;
 const group=$('groupFilter').value||(selectedJobs()[0]?groupKey(selectedJobs()[0]):'');
 const groups=new Set(candidates.map(groupKey));
 if(!group&&groups.size>1)throw Error('Выберите организацию и контур в списке над очередью, затем нажмите «Выбрать все подходящие».');
 const matching=candidates.filter(j=>!group||groupKey(j)===group);
 chosen=new Set(matching.slice(0,selectionLimit).map(j=>j.id));updateActions();
 message(matching.length>selectionLimit?`Всего подходящих: ${matching.length}. Лимит одной операции: ${selectionLimit}. Выбраны первые ${selectionLimit}; ещё ${matching.length-selectionLimit} останутся для следующей партии.`:`Выбрано ${chosen.size} ${view==='waiting'?'наборов для одного документа ЧЗ':'наборов для проверки результата'}.`);
});
run(refresh)();setInterval(run(refresh),2000);

$('loginCheck').onclick=run(async()=>{
 if(working||busy)throw Error('Дождитесь завершения текущей операции');
 const group=selectedSyncGroup();const thumb=$('certificates').value;
 if(!group||!group.pending)throw Error('Выберите организацию с неподтверждёнными наборами');
 if(!thumb)throw Error('Выберите сертификат УКЭП');
 const certificate=certs.find(c=>c.thumbprint===thumb);
 if(!confirm(`Войти в ЧЗ по УКЭП и проверить все неподтверждённые наборы\n${groupLabel(group)}\nНаборов: ${group.pending}\nСертификат: ${certificate?.subject||thumb}\n\nПодпись используется для входа. Новые документы не отправляются. При запросе введите PIN в окне КриптоПро. Продолжить?`))return;
 busy=true;busyAction='loginCheck';updateActions();
 try{await api('/api/login-check',{confirmed:true,thumbprint:thumb,group:{inn:group.inn,environment:group.environment,pg:group.pg||'grocery'}});working=true;activeAction='loginCheck';message('Вход и проверка запущены. После входа автопроверка возобновится; документы повторно не отправляются.');await refresh();}
 finally{busy=false;busyAction=null;updateActions();}
});

$('cancelUnsent').onclick=run(async()=>{
 if(working||busy)throw Error('Дождитесь завершения текущей операции');
 const selected=selectedJobs();
 if(!selected.length||selected.some(j=>!SIGNABLE.has(j.status)||j.documentId))throw Error('Отменить можно только выбранные наборы, ещё не отправленные в ЧЗ');
 if(!confirm(`Отменить ${selected.length} неотправленных наборов? Затем обновите статусы на ТСД: коды можно будет собрать заново с правильным шаблоном.`))return;
 busy=true;busyAction='cancelUnsent';updateActions();
 try{await api('/api/cancel',{confirmed:true,items:selected.map(j=>({id:j.id,hash:j.payloadHash}))});chosen.clear();await refresh();message('Неотправленные наборы отменены. Обновите статусы на ТСД перед повторной сборкой.');}finally{busy=false;busyAction=null;updateActions();}
});
