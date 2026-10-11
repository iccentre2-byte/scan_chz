'use strict';
const $=id=>document.getElementById(id);
const key=location.hash.slice(1)||sessionStorage.getItem('chzAdminKey')||'';
if(key)sessionStorage.setItem('chzAdminKey',key);
history.replaceState(null,'',location.pathname);
let jobs=[],certs=[],chosen=new Set(),busy=false,pairUrl=null;
const text=(tag,value,css)=>{const n=document.createElement(tag);n.textContent=value;if(css)n.className=css;return n;};
async function api(path,body){
 const r=await fetch(path,{method:body===undefined?'GET':'POST',headers:{'X-Admin-Key':key,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store',redirect:'error'});
 const data=await r.json();if(!r.ok)throw Error(data.error||('HTTP '+r.status));return data;
}
function message(value,error=false){$('message').textContent=value;$('message').className=error?'error':'';}
function draw(){
 const root=$('jobs');root.replaceChildren();
 if(!jobs.length){root.append(text('p','Очередь пуста. Соберите набор на ТСД и нажмите «Передать на ПК».'));return;}
 for(const j of jobs){
  const card=text('article','', 'job');const label=document.createElement('label');
  const cb=document.createElement('input');cb.type='checkbox';cb.checked=chosen.has(j.id);cb.onchange=()=>{cb.checked?chosen.add(j.id):chosen.delete(j.id);};
  label.append(cb,text('span',j.setCode,'title mono'));card.append(label);
  card.append(text('p',`${j.environment==='production'?'PRODUCTION — РЕАЛЬНЫЙ ЧЗ':'SANDBOX — ТЕСТОВЫЙ КОНТУР'} · ИНН ${j.inn} · ${j.children.length} пачек`,'meta'));
  card.append(text('p',j.statusRu,'status'+(j.status==='VERIFIED'?' success':j.status==='UNKNOWN'||j.status==='REJECTED'?' error':'')));
  card.append(text('p',j.detail||''));
  if(j.documentId)card.append(text('p','Документ ЧЗ: '+j.documentId,'mono'));
  const details=document.createElement('details');details.append(text('summary','Проверить состав и документ'));
  details.append(text('pre',Object.entries(j.template.expected).map(([g,n])=>`${g} × ${n}`).join('\n')));
  const product={participantId:j.inn,aggregationUnits:[{unitSerialNumber:j.setCode,sntins:j.children}]};
  details.append(text('pre',JSON.stringify(product,null,2)));card.append(details);root.append(card);
 }
}
async function refresh(){
 const data=await api('/api/state');jobs=data.jobs;
 $('version').textContent='Версия '+data.version+' · опытная сборка';$('dataDir').textContent=data.dataDir;
 $('operations').textContent=Object.values(data.operations).map(o=>`${o.action==='submit'?'Отправка':'Проверка'}: ${o.state}, ${o.done}/${o.total}${o.error?' · '+o.error:''}`).join('\n');
 const working=Object.values(data.operations).some(o=>['RUNNING','QUEUED'].includes(o.state));
 $('sign').disabled=working||busy;$('check').disabled=working||busy;draw();
}
async function perform(action){
 const selected=jobs.filter(j=>chosen.has(j.id));const thumb=$('certificates').value;
 if(!selected.length)throw Error('Выберите хотя бы один набор');if(!thumb)throw Error('Выберите сертификат УКЭП');
 if(new Set(selected.map(j=>j.inn+'|'+j.environment)).size!==1)throw Error('Выберите одну организацию и один контур');
 if(action==='submit'&&selected.some(j=>!['WAITING_SIGNATURE','BLOCKED'].includes(j.status)))throw Error('Есть уже отправленные наборы. Для них используйте «Проверить», а не повторную отправку.');
 const c=certs.find(c=>c.thumbprint===thumb);
 const msg=`${action==='submit'?'ПОДПИСАТЬ И ОТПРАВИТЬ в ЧЗ':'Проверить в ЧЗ (без повторной отправки)'}\nНаборов: ${selected.length}\nИНН: ${selected[0].inn}\nКонтур: ${selected[0].environment}\nСертификат: ${c?.subject||thumb}\n\n${action==='submit'?'Это не черновик. Вы проверили состав и действительно подтверждаете отправку?':'Будет выполнен вход по УКЭП и чтение сведений. Продолжить?'}`;
 if(!confirm(msg))return;
 busy=true;$('sign').disabled=true;$('check').disabled=true;
 try{await api('/api/'+action,{confirmed:true,thumbprint:thumb,items:selected.map(j=>({id:j.id,hash:j.payloadHash}))});message('Операция запущена. При запросе введите PIN в окне КриптоПро.');await refresh();}finally{busy=false;}
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
 message(certs.length?'Сертификаты найдены. Проверьте организацию.':'Не найдено действующих сертификатов с закрытым ключом.');
});
$('certificates').onchange=()=>{const c=certs.find(c=>c.thumbprint===$('certificates').value);$('certDetail').textContent=c?'Отпечаток: '+c.thumbprint:'';};
$('sign').onclick=run(()=>perform('submit'));$('check').onclick=run(()=>perform('refresh'));
$('refreshView').onclick=run(refresh);
$('backup').onclick=run(async()=>message('Копия сохранена: '+(await api('/api/backup',{})).path));
run(refresh)();setInterval(()=>{if(!document.querySelector('details[open]'))run(refresh)();},4000);

$('cancelUnsent').onclick=run(async()=>{
 const selected=jobs.filter(j=>chosen.has(j.id));
 if(!selected.length||selected.some(j=>!['WAITING_SIGNATURE','BLOCKED'].includes(j.status)))throw Error('Отменить можно только выбранные наборы, ещё не отправленные в ЧЗ');
 if(!confirm(`Отменить ${selected.length} неотправленных наборов? Затем обновите статусы на ТСД: коды можно будет собрать заново с правильным шаблоном.`))return;
 await api('/api/cancel',{confirmed:true,items:selected.map(j=>({id:j.id,hash:j.payloadHash}))});chosen.clear();await refresh();message('Неотправленные наборы отменены. Обновите статусы на ТСД перед повторной сборкой.');
});
