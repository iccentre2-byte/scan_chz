const fs=require('fs'),vm=require('vm'),assert=require('assert');
class Element{
 constructor(tag='div'){this.tagName=tag;this.children=[];this.value='';this.textContent='';this.dataset={};this.attributes={};this.disabled=false;this.checked=false;this.hidden=false;this.parentNode=null;}
 append(...nodes){for(const node of nodes){if(node.parentNode)node.parentNode.removeChild(node);this.children.push(node);node.parentNode=this;}}
 replaceChildren(...nodes){for(const node of [...this.children])this.removeChild(node);this.append(...nodes);}
 insertBefore(node,before){if(node.parentNode)node.parentNode.removeChild(node);const index=before?this.children.indexOf(before):-1;if(index===-1)this.append(node);else{this.children.splice(index,0,node);node.parentNode=this;}}
 removeChild(node){const index=this.children.indexOf(node);assert(index!==-1);this.children.splice(index,1);node.parentNode=null;}
 setAttribute(key,value){this.attributes[key]=value;}
 get options(){return this.children;}
}
const elements=new Map();
const document={getElementById:id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);},createElement:tag=>new Element(tag),activeElement:null};
let state={version:'test',dataDir:'LOCAL TEST',operations:{},jobs:[]},calls=[],prompts=[],fetchMode='',removedKey=false;
let timerId=0;const timers=new Map();
function stateReply(){
 const groups=new Map();
 for(const j of state.jobs){const id=JSON.stringify([j.inn,j.environment,j.pg]);if(!groups.has(id))groups.set(id,{inn:j.inn,environment:j.environment,pg:j.pg,pending:0,session:'missing',requiresLogin:true,lastAttempt:null,lastSuccess:null,error:''});if(['WAITING_SIGNATURE','BLOCKED','SUBMITTED','PROCESSING','CHECK_REQUIRED','UNKNOWN'].includes(j.status))groups.get(id).pending++;}
 state.syncGroups=[...groups.values()].map(group=>{const items=state.jobs.filter(j=>j.inn===group.inn&&j.environment===group.environment&&j.pg===group.pg);return{...group,total:items.length,verified:items.filter(j=>j.status==='VERIFIED').length,allVerified:items.every(j=>j.status==='VERIFIED')};});return state;
}
const context=vm.createContext({document,location:{hash:'',pathname:'/'},sessionStorage:{getItem:()=>null,setItem:()=>{},removeItem:()=>{removedKey=true;}},history:{replaceState:()=>{}},URL:{},AbortController,setInterval:()=>{},setTimeout:(callback,ms)=>{timers.set(++timerId,{callback,ms});return timerId;},clearTimeout:id=>timers.delete(id),console,confirm:msg=>{prompts.push(msg);return true;},fetch:async(path,options)=>{
 calls.push({path,body:options?.body?JSON.parse(options.body):null});
 if(fetchMode==='hang:'+path)return new Promise(()=>{});
 if(fetchMode==='401')return{ok:false,status:401,json:async()=>({error:'stale admin key'})};
 return{ok:true,status:200,json:async()=>path==='/api/state'?stateReply():path==='/api/certificates'?{certificates:[]}:{operationId:'test'}};
}});
vm.runInContext(fs.readFileSync(require('path').join(__dirname,'../companion_pc/web/app.js'),'utf8'),context);
const run=js=>vm.runInContext(js,context);
const make=(id,status,inn='1111111111',environment='sandbox',doc=null)=>({id,status,inn,environment,pg:'grocery',payloadHash:'hash-'+id,statusRu:status,detail:'',documentId:doc,setCode:'set-'+id,children:['child-'+id],template:{expected:{'04607010740085':1}}});
const $=id=>document.getElementById(id);
(async()=>{
 await new Promise(resolve=>setImmediate(resolve));
 state.jobs=[make('a','WAITING_SIGNATURE'),make('b','BLOCKED'),make('c','SUBMITTED',undefined,undefined,'doc'),make('d','PROCESSING',undefined,undefined,'doc'),make('e','CHECK_REQUIRED',undefined,undefined,'doc'),make('f','VERIFIED',undefined,undefined,'doc'),make('g','UNKNOWN'),make('h','REJECTED'),make('i','CANCELLED_LOCAL')];
 await run('refresh()');
 assert($('tab-waiting').textContent.endsWith('(2)'));assert($('tab-processing').textContent.endsWith('(3)'));assert($('tab-processed').textContent.endsWith('(1)'));assert($('tab-problems').textContent.endsWith('(3)'));
 assert.strictEqual($('jobs').children.length,2);
 await $('selectAll').onclick();assert.strictEqual(run('chosen.size'),2);assert.deepStrictEqual(JSON.parse(JSON.stringify(run('selectedJobs().map(j=>j.id)'))),['a','b']);
 assert.strictEqual(JSON.parse($('selectedDocument').textContent).aggregationUnits.length,2);
 const cb=run("cards.get('a').cb"),card=run("cards.get('a').card"),details=run("cards.get('a').details");details.open=true;document.activeElement=cb;
 await run('refresh()');assert.strictEqual(run("cards.get('a').cb"),cb);assert.strictEqual($('jobs').children[0],card);assert.strictEqual(details.open,true);assert.strictEqual(document.activeElement,cb);assert.strictEqual(cb.checked,true);
 state.jobs.push(make('other','WAITING_SIGNATURE','2222222222'));await run('refresh()');
 const other=run("cards.get('other').cb");other.checked=true;other.onchange();assert.strictEqual(other.checked,false);assert.strictEqual(run('chosen.size'),2);
 $('clearSelection').onclick();await $('selectAll').onclick();assert.strictEqual(run('chosen.size'),0);assert($('message').textContent.includes('Выберите организацию'));
 $('groupFilter').value=run("groupKey(jobs[0])");$('groupFilter').onchange();await $('selectAll').onclick();assert.strictEqual(run('chosen.size'),2);
 assert($('sign').title.includes('сертификат'));assert($('check').title.includes('сертификат'));assert.strictEqual($('cancelUnsent').disabled,false);
 $('certificates').value='TEST-THUMB';$('certificates').onchange();assert.strictEqual($('sign').disabled,false);assert.strictEqual($('check').disabled,false);
 await $('sign').onclick();const submit=calls.find(c=>c.path==='/api/submit');assert(submit);assert.deepStrictEqual(submit.body.items.map(j=>j.id),['a','b']);assert.strictEqual(submit.body.confirmed,true);assert(prompts[0].includes('один общий документ'));
 $('tab-processing').onclick();await $('selectAll').onclick();assert.strictEqual(run('chosen.size'),3);assert.strictEqual($('sign').disabled,true);assert.strictEqual($('check').disabled,false);assert(run("cards.get('c').documentLine.textContent").includes('общий для 4 наборов'));
 $('tab-processed').onclick();assert.strictEqual(run('chosen.size'),0);assert.strictEqual($('sign').disabled,true);assert.strictEqual($('check').disabled,true);assert.strictEqual($('selectAll').disabled,true);assert.strictEqual(run("cards.get('f').cb.hidden"),true);
 $('tab-problems').onclick();await $('selectAll').onclick();assert.deepStrictEqual(JSON.parse(JSON.stringify(run('selectedJobs().map(j=>j.id)'))),['g']);assert.strictEqual($('check').disabled,false);assert.strictEqual(run("cards.get('i').cb.hidden"),true);
 state.jobs.find(j=>j.id==='g').status='VERIFIED';await run('refresh()');assert.strictEqual(run('chosen.size'),0);
 state.jobs=Array.from({length:55},(_,i)=>make(String(i),'WAITING_SIGNATURE'));$('groupFilter').value='';$('tab-waiting').onclick();await run('refresh()');await $('selectAll').onclick();assert.strictEqual(run('chosen.size'),50);assert($('message').textContent.includes('первые 50'));
 state.selectionLimit=500;state.jobs=Array.from({length:505},(_,i)=>make(String(i),'WAITING_SIGNATURE'));await run('refresh()');await $('selectAll').onclick();assert.strictEqual(run('chosen.size'),500);assert($('message').textContent.includes('ещё 5'));assert($('selectAll').textContent.includes('500'));
 state.jobs=state.jobs.slice(0,250);await run('refresh()');await $('selectAll').onclick();assert.strictEqual(run('chosen.size'),250);
 assert($('syncStatus').textContent.includes('вход в ЧЗ ещё не выполнен'));assert.strictEqual($('loginCheck').disabled,false);
 const submitsBefore=calls.filter(c=>c.path==='/api/submit').length;
 await $('loginCheck').onclick();const login=calls.find(c=>c.path==='/api/login-check');assert(login);assert.strictEqual(login.body.confirmed,true);assert.strictEqual(login.body.group.inn,state.jobs[0].inn);assert.strictEqual(login.body.items,undefined);assert.strictEqual(calls.filter(c=>c.path==='/api/submit').length,submitsBefore);assert(prompts.at(-1).includes('Новые документы не отправляются'));
 state.operations={x:{state:'RUNNING',action:'refresh',stage:'login',done:0,total:250}};await run('refresh()');assert.strictEqual($('check').attributes['aria-busy'],'true');assert.strictEqual($('sign').attributes['aria-busy'],'false');assert($('actionReasons').textContent.includes('Выполняется операция'));
 state.operations={};await run('refresh()');
 const savedJobs=state.jobs;state.jobs=[make('done','VERIFIED'),make('rejected','REJECTED'),make('cancelled','CANCELLED_LOCAL')];await run('refresh()');assert(!$('syncStatus').textContent.includes('Все наборы подтверждены'));assert($('syncActionReason').textContent.includes('Нет наборов, ожидающих проверки'));assert($('sign').title.includes('Нет наборов для подписи'));assert($('check').title.includes('Нет неподтверждённых'));assert($('cancelUnsent').title.includes('Нет наборов до отправки'));state.jobs=savedJobs;await run('refresh()');
 fetchMode='hang:/api/state';const pending=run('refresh()');assert.strictEqual(run('refreshing'),true);assert.strictEqual(timers.size,1);[...timers.values()][0].callback();await assert.rejects(pending,/не ответил вовремя/);assert.strictEqual(run('refreshing'),false);assert.strictEqual($('refreshView').disabled,false);assert.strictEqual(run('working'),false);
 fetchMode='hang:/api/login-check';const before=calls.filter(c=>c.path==='/api/login-check').length;const pendingLogin=$('loginCheck').onclick();assert.strictEqual(run('busy'),true);assert.strictEqual($('loginCheck').attributes['aria-busy'],'true');[...timers.values()][0].callback();await pendingLogin;assert.strictEqual(run('busy'),false);assert.strictEqual(calls.filter(c=>c.path==='/api/login-check').length,before+1);assert($('message').textContent.includes('Запрос не повторялся'));
 fetchMode='hang:/api/certificates';const pendingCerts=$('loadCertificates').onclick();assert.strictEqual(run('certBusy'),true);assert.strictEqual([...timers.values()][0].ms,190000);[...timers.values()][0].callback();await pendingCerts;assert.strictEqual(run('certBusy'),false);assert.strictEqual($('loadCertificates').disabled,false);
 fetchMode='401';await $('refreshView').onclick();assert.strictEqual(run('adminExpired'),true);assert.strictEqual(run('working'),false);assert.strictEqual(run('refreshing'),false);assert.strictEqual(removedKey,true);for(const id of ['sign','check','cancelUnsent']){assert.strictEqual($(id).disabled,true);assert($(id).title.includes('Start_PC.cmd'));assert.strictEqual($(id).attributes['aria-busy'],'false');}
 const callsBefore=calls.length;await run('refresh()');assert.strictEqual(calls.length,callsBefore);assert.strictEqual($('adminExpired').hidden,false);
 const css=fs.readFileSync(require('path').join(__dirname,'../companion_pc/web/style.css'),'utf8');assert(!/button:disabled\{[^}]*cursor:wait/.test(css));assert(css.includes('button[aria-busy="true"]{cursor:progress}'));
 console.log('PASS: stable safe selection, group login/read-only check, visible disabled reasons, genuine busy cursor only, API timeouts/finally recovery, no POST retry, stale-panel401 recovery guidance. No real API/signature.');
})().catch(error=>{console.error(error);process.exitCode=1;});
