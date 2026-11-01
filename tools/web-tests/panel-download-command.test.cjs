const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const crypto=require('node:crypto').webcrypto;
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
 const sent=[],messages=[];let finish,reject;
 const pending=new Promise((yes,no)=>{finish=yes;reject=no});
 const controls=[{disabled:false},{disabled:false}];
 const c=vm.createContext({document:{addEventListener(){},querySelectorAll:()=>controls},AbortSignal,crypto,localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,c);c.dispatch=(path,body,options)=>{sent.push({path,body:JSON.parse(JSON.stringify(body)),key:options.commandKey});return pending;};c.message=text=>messages.push(text);
 vm.runInContext("state.snapshot={downloads:[{id:'a',name:'first.zip'},{id:'b',name:'second.zip'}]};state.cookieSession=true;state.selected=new Set(['a','b']);api=dispatch;refresh=async()=>{};render=()=>{};toast=message",c);
 return {c,sent,messages,controls,finish,reject};
}
test('repeated priority clicks admit one immutable command and busy controls until reply',async()=>{
 const x=setup();const first=vm.runInContext("command('priority',['a','a'])",x.c);
 await vm.runInContext("command('priority',['a'])",x.c);
 assert.equal(x.sent.length,1);assert.deepEqual(x.sent[0].body,{action:'priority',ids:['a']});assert.ok(x.controls.every(x=>x.disabled));
 x.finish({results:[{id:'a',accepted:true}]});await first;assert.equal(vm.runInContext('state.downloadCommandPending',x.c),false);
});
test('mixed group result clears only accepted selections and shows refused row error',async()=>{
 const x=setup();const call=vm.runInContext("command('resume',['a','b'])",x.c);
 x.finish({results:[{id:'a',accepted:true},{id:'b',accepted:false,error:'Waiting for scheduled time'}]});await call;
 assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify([...state.selected])',x.c)),['b']);assert.deepEqual(x.messages,['second.zip: Waiting for scheduled time']);
});
test('manual retry after a lost reply reuses the command key and never auto dispatches',async()=>{
 const x=setup();const first=vm.runInContext("command('priority',['a'])",x.c);x.reject(new Error('Connection lost'));await first;
 assert.equal(x.sent.length,1);const key=x.sent[0].key;
 x.c.dispatch=(path,body,options)=>{x.sent.push({key:options.commandKey});return Promise.resolve({results:[{id:'a',accepted:true}]});};vm.runInContext('api=dispatch',x.c);
 await vm.runInContext("command('priority',['a'])",x.c);assert.equal(x.sent[1].key,key);assert.equal(vm.runInContext('state.downloadCommandRetry',x.c),null);
});

test('reordered selection after a lost reply retains the same body and command key',async()=>{
 const x=setup();const first=vm.runInContext("command('priority',['b','a'])",x.c);x.reject(new Error('Connection lost'));await first;
 const original=x.sent[0];x.c.dispatch=(path,body,options)=>{x.sent.push({body:JSON.parse(JSON.stringify(body)),key:options.commandKey});return Promise.resolve({results:[]});};vm.runInContext('api=dispatch',x.c);
 await vm.runInContext("command('priority',['a','b'])",x.c);assert.deepEqual(x.sent[1].body,original.body);assert.equal(x.sent[1].key,original.key);
});
