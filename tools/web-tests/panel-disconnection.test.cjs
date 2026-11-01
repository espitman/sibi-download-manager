const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const crypto=require('node:crypto').webcrypto;
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
  let banner=null, requests=0, renders=0;const listeners={};
  const controls=[{dataset:{action:'save-settings'},disabled:false},{dataset:{action:'submit-torrent'},disabled:true},{dataset:{action:'close-modal'},disabled:false}];
  const speedMetric={dataset:{liveSpeedRow:'fixture',liveSpeedField:'speed'},textContent:''};
  const etaMetric={dataset:{liveEta:'fixture'},textContent:''};
  const workspace={querySelector:s=>s==='.connection-banner'?banner:{after:e=>banner=e}};
  const status={};
  const app={};
  const modal={open:true,addEventListener(){}};
  const document={activeElement:{matches:()=>true},querySelector:s=>({'.workspace':workspace,'.device-status p':status,'#modal':modal,'#app':app}[s]),querySelectorAll:s=>s==='[data-live-speed]'?[speedMetric]:s==='[data-live-eta]'?[etaMetric]:controls,addEventListener:(type,callback)=>(listeners[type]??=[]).push(callback),createElement:()=>({setAttribute(){},remove(){banner=null}})};
  const context=vm.createContext({AbortSignal,document,localStorage:{getItem:()=>null},sessionStorage:{},navigator:{},crypto,console,setTimeout,clearTimeout,setInterval(){},fetch:async()=>{requests++;throw Error('connection lost')}});
  vm.runInContext(source,context);
  // Set up the real script state; render is counted so rebuilding the form is observable.
  context.countRender=()=>renders++;
  vm.runInContext("state.cookieSession=true;state.page='settings';state.snapshot={time:1000,downloads:[],settings:{}};render=countRender",context);
  listeners.input.forEach(callback=>callback({target:{dataset:{setting:'retryCount'},type:'number',value:'7',hasAttribute:()=>false}}));
  return {context,controls,workspace,status,speedMetric,etaMetric,get requests(){return requests},get renders(){return renders},get banner(){return banner}};
}
test('repeated failed polling preserves the open form and blocks mutation without dispatch',async()=>{
  const x=setup();
  await vm.runInContext('refresh()',x.context);
  await vm.runInContext('refresh()',x.context);
  assert.equal(x.renders,0);
  assert.equal(vm.runInContext('state.settingsDraft.retryCount',x.context),7);
  assert.equal(x.controls[0].disabled,true);
  assert.equal(x.controls[1].disabled,true);
  assert.equal(x.controls[2].disabled,false);
  assert.ok(x.banner.innerHTML.includes('Last sync:'));
  const before=x.requests;
  await assert.rejects(vm.runInContext("api('settings',{retryCount:7})",x.context),/Reconnect/);
  assert.equal(x.requests,before);
});
test('reconnection restores only offline-disabled controls and never replays a blocked command',async()=>{
  const x=setup();await vm.runInContext('refresh()',x.context);
  x.context.fetch=async()=>({ok:true,status:200,json:async()=>({time:2000,device:'Fixture',downloads:[],settings:{}})});
  await vm.runInContext('refresh()',x.context);
  assert.equal(x.controls[0].disabled,false);
  assert.equal(x.controls[1].disabled,true);
  assert.equal(x.banner,null);
  assert.equal(x.renders,0);
  assert.equal(vm.runInContext('state.settingsDraft.retryCount',x.context),7);
  assert.equal(vm.runInContext('state.offline',x.context),false);
});

test('reconnect uses fresh phone rates and ETA while keeping a focused draft form intact',async()=>{
 const x=setup();
 vm.runInContext("state.snapshot.downloads=[{id:'fixture',state:'DOWNLOADING',downloaded:0,total:1000000,speed:123000}];updateConnectionUI()",x.context);
 assert.equal(x.speedMetric.textContent,'123 KB/s');
 await vm.runInContext('refresh()',x.context);
 assert.equal(x.speedMetric.textContent,'—');assert.equal(x.etaMetric.textContent,'—');
 x.context.fetch=async()=>({ok:true,status:200,json:async()=>({time:2000,device:'Fixture',downloads:[{id:'fixture',state:'DOWNLOADING',downloaded:200000,total:1000000,speed:200000}],settings:{}})});
 await vm.runInContext('refresh()',x.context);
 assert.equal(x.speedMetric.textContent,'200 KB/s');assert.equal(x.etaMetric.textContent,'00:04');
 assert.equal(x.renders,0);assert.equal(vm.runInContext('state.settingsDraft.retryCount',x.context),7);
});

test('revoked authorization discards authenticated drafts and closed dialog content',async()=>{
 const x=setup();
 const modal=x.context.document.querySelector('#modal');
 modal.innerHTML='<textarea>private draft</textarea>';
 modal.close=()=>{modal.open=false;};
 x.context.localStorage.removeItem=()=>{};
 x.context.sessionStorage.removeItem=()=>{};
 x.context.fetch=async()=>({ok:false,status:401,json:async()=>({error:'Access revoked'})});
 vm.runInContext("state.files={private:'file'};state.detail={private:'detail'};state.selected.add('fixture');state.bulkLink={review:'old-owner'};state.addDestination={id:'old-folder'};state.downloadCommandRetry={key:'old-owner-command'};renderPairing=()=>{}",x.context);
 await assert.rejects(vm.runInContext("api('state')",x.context),/Access revoked/);
 assert.equal(modal.open,false);assert.equal(modal.innerHTML,'');
 assert.equal(vm.runInContext('state.snapshot',x.context),null);
 assert.equal(vm.runInContext('state.files',x.context),null);
 assert.equal(vm.runInContext('state.detail',x.context),null);
 assert.equal(vm.runInContext('state.settingsDraft',x.context),null);
 assert.equal(vm.runInContext('state.selected.size',x.context),0);
 for(const key of ['bulkLink','addDestination','downloadCommandRetry'])assert.equal(vm.runInContext('state.'+key,x.context),null);
});

test('a hanging state read aborts and marks controls offline without replaying mutations',async()=>{
 const x=setup();const controller=new AbortController();let budget;
 x.context.AbortSignal={timeout:ms=>{budget=ms;return controller.signal;}};
 x.context.fetch=async(_url,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(Error('timeout')),{once:true}));
 const pending=vm.runInContext('refresh()',x.context);
 controller.abort();await pending;
 assert.ok(budget>0&&budget<=10000);
 assert.equal(vm.runInContext('state.offline',x.context),true);
 assert.equal(vm.runInContext('state.refreshing',x.context),false);
 assert.equal(x.controls[0].disabled,true);
 assert.equal(x.renders,0);
 assert.equal(vm.runInContext('state.settingsDraft.retryCount',x.context),7);
});

test('temporary approval polling failure retains the ticket and does not create another request',async()=>{
 const x=setup();const status={textContent:''};const control={disabled:true};
 const query=x.context.document.querySelector;
 x.context.document.querySelector=s=>s==='#pair-status'?status:s==='[data-action="request-access"]'?control:query(s);
 vm.runInContext("state.approval={id:'owned-request',ticket:'owned-ticket'};api=async()=>{throw Error('offline')}",x.context);
 await vm.runInContext('pollApproval()',x.context);
 assert.equal(vm.runInContext('state.approval.id',x.context),'owned-request');
 assert.equal(control.disabled,true);assert.match(status.textContent,/reconnect/);
 vm.runInContext("api=async()=>({})",x.context);await vm.runInContext('pollApproval()',x.context);
 assert.match(status.textContent,/Waiting for approval/);
 vm.runInContext("api=async()=>{throw Object.assign(Error('denied'),{status:401})}",x.context);
 await vm.runInContext('pollApproval()',x.context);
 assert.equal(vm.runInContext('state.approval',x.context),null);
 assert.equal(control.disabled,false);assert.match(status.textContent,/expired or was declined/);
});

 test('rapid access requests admit one in-flight call and restore the button after rejection',async()=>{
 const x=setup(),control={disabled:false},status={textContent:''};let resolve,reject,calls=0;
 x.context.navigator.userAgent='Chrome';
 x.context.document.querySelector=s=>s==='[data-action="request-access"]'?control:s==='#pair-status'?status:null;
 x.context.pendingRequest=()=>{calls++;return new Promise((a,b)=>{resolve=a;reject=b})};
 vm.runInContext('state.cookieSession=false;state.snapshot=null;api=pendingRequest',x.context);
 const first=vm.runInContext("act('request-access')",x.context);
 assert.equal(control.disabled,true);
 await vm.runInContext("act('request-access')",x.context);assert.equal(calls,1);
 reject(Error('offline'));await assert.rejects(first,/offline/);assert.equal(control.disabled,false);
 const retry=vm.runInContext("act('request-access')",x.context);assert.equal(calls,2);
 resolve({id:'owned',ticket:'ticket'});await retry;
 assert.equal(control.disabled,true);assert.match(status.textContent,/Waiting for approval/);
 await vm.runInContext("act('request-access')",x.context);assert.equal(calls,2);
 });

test('saving settings preserves edits made while awaiting response and does not send a second save',async()=>{
 const x=setup();let finish,calls=0;const saved=[];
 x.context.saveRequest=(_path,body)=>{calls++;saved.push(JSON.parse(JSON.stringify(body)));return new Promise(resolve=>finish=resolve)};
 vm.runInContext('api=saveRequest;refresh=async()=>{};toast=()=>{};state.settingsDraft={retryCount:2}',x.context);
 const pending=vm.runInContext("act('save-settings')",x.context);
 vm.runInContext('state.settingsDraft.retryCount=5;state.settingsDraft.simultaneous=3',x.context);
 await vm.runInContext("act('save-settings')",x.context);assert.equal(calls,1);
 finish({ok:true});await pending;
 assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(state.settingsDraft)',x.context)),{retryCount:5,simultaneous:3});
 const second=vm.runInContext("act('save-settings')",x.context);finish({ok:true});await second;
 assert.deepEqual(saved,[{retryCount:2},{retryCount:5,simultaneous:3}]);assert.equal(vm.runInContext('Object.keys(state.settingsDraft).length',x.context),0);
});
