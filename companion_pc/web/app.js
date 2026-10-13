'use strict';
const $=id=>document.getElementById(id);
const key=location.hash.slice(1)||sessionStorage.getItem('chzAdminKey')||'';
if(key)sessionStorage.setItem('chzAdminKey',key);
history.replaceState(null,'',location.pathname);
let jobs=[],certs=[],chosen=new Set(),busy=false,pairUrl=null,working=false,view='waiting',refreshing=false,selectionLimit=50;
const SIGNABLE=new Set(['WAITING_SIGNATURE','BLOCKED']);
const CHECKABLE=new Set(['SUBMITTED','PROCESSING','CHECK_REQUIRED','UNKNOWN']);
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
 const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'X-Admin-Key':key,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',redirect:'error'});
 const data=await r.json();if(!r.ok)throw Error(data.error||('HTTP '+r.status));return data;
}
function message(value,error=false){$('message').textContent=value;$('message').className=error?'error':'';}
function updateActions(){
 const selected=selectedJobs();const locked=working||busy;
 const canSign=selected.length>0&&selected.every(j=>SIGNABLE.has(j.status)&&!j.documentId);
 const canCheck=selected.length>0&&selected.every(j=>CHECKABLE.has(j.status));
 $('sign').disabled=locked||!canSign||!$('certificates').value;
 $('check').disabled=locked||!canCheck||!$('certificates').value;
 $('cancelUnsent').disabled=locked||!canSign;
 $('selectAll').disabled=locked||!visibleJobs().some(j=>selectable(j));
 $('selectAll').textContent=`Выбрать все подходящие (до ${selectionLimit})`;
 $('clearSelection').disabled=locked||!selected.length;
 $('selectionSummary').textContent=selected.length?`Выбрано: ${selected.length}${canSign?' · 1 документ ЧЗ':''}`:'Наборы не выбраны';
 $('selectionDocument').hidden=!canSign;
 if(canSign)$('selectedDocument').textContent=JSON.stringify({participantId:selected[0].inn,aggregationUnits:selected.map(j=>({unitSerialNumber:j.setCode,sntins:j.children}))},null,2);
 for(const [id,c] of cards){const j=jobs.find(j=>j.id===id);c.cb.checked=chosen.has(id);c.cb.disabled=locked||!j||!selectable(j);}
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
 if(refreshing)return;
 refreshing=true;
 try{
  const data=await api('/api/state');jobs=data.jobs;
  selectionLimit=Number.isInteger(data.selectionLimit)&&data.selectionLimit>0&&data.selectionLimit<=500?data.selectionLimit:50;
  $('version').textContent='Версия '+data.version+' · опытная сборка';$('dataDir').textContent=data.dataDir;
  $('operations').textContent=Object.values(data.operations).map(o=>`${o.action==='submit'?'Отправка':'Проверка'}: ${o.state}, ${o.done}/${o.total}${o.documentId?' · документ '+o.documentId:''}${o.error?' · '+o.error:''}`).join('\n');
  working=Object.values(data.operations).some(o=>['RUNNING','QUEUED'].includes(o.state));draw();
 }finally{refreshing=false;}
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
 busy=true;updateActions();
 try{await api('/api/'+action,{confirmed:true,thumbprint:thumb,items:selected.map(j=>({id:j.id,hash:j.payloadHash}))});message('Операция запущена. При запросе введите PIN в окне КриптоПро.');await refresh();}finally{busy=false;updateActions();}
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
 message('Чтение сертификатов с ПК...');certs=(await api('/api/certificates',{})).certificates;
 $('certificates').replaceChildren(text('option','Выберите сертификат'));$('certificates').options[0].value='';
 for(const c of certs){const o=text('option',c.subject+' · до '+c.validTo);o.value=c.thumbprint;$('certificates').append(o);}
 message(certs.length?'Сертификаты найдены. Проверьте организацию.':'Не найдено действующих сертификатов с закрытым ключом.');updateActions();
});
$('certificates').onchange=()=>{const c=certs.find(c=>c.thumbprint===$('certificates').value);$('certDetail').textContent=c?'Отпечаток: '+c.thumbprint:'';updateActions();};
$('sign').onclick=run(()=>perform('submit'));$('check').onclick=run(()=>perform('refresh'));
$('refreshView').onclick=run(refresh);
$('backup').onclick=run(async()=>message('Копия сохранена: '+(await api('/api/backup',{})).path));
for(const name of Object.keys(VIEW_LABELS))$('tab-'+name).onclick=()=>{view=name;chosen.clear();draw();};
$('groupFilter').onchange=()=>{chosen.clear();draw();};
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

$('cancelUnsent').onclick=run(async()=>{
 if(working||busy)throw Error('Дождитесь завершения текущей операции');
 const selected=selectedJobs();
 if(!selected.length||selected.some(j=>!SIGNABLE.has(j.status)||j.documentId))throw Error('Отменить можно только выбранные наборы, ещё не отправленные в ЧЗ');
 if(!confirm(`Отменить ${selected.length} неотправленных наборов? Затем обновите статусы на ТСД: коды можно будет собрать заново с правильным шаблоном.`))return;
 busy=true;updateActions();
 try{await api('/api/cancel',{confirmed:true,items:selected.map(j=>({id:j.id,hash:j.payloadHash}))});chosen.clear();await refresh();message('Неотправленные наборы отменены. Обновите статусы на ТСД перед повторной сборкой.');}finally{busy=false;updateActions();}
});
