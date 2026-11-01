const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
 const listeners={},calls=[];let renders=0;
 const document={addEventListener:(type,fn)=>(listeners[type]??=[]).push(fn),querySelectorAll:()=>[],querySelector:()=>null};
 const c=vm.createContext({document,AbortSignal,localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,c);c.record=(path,body)=>calls.push({path,body:JSON.parse(JSON.stringify(body))});c.rendered=()=>renders++;
 vm.runInContext("state.cookieSession=true;state.page='downloads';state.tab='queue';state.snapshot={downloads:['a','b','c'].map((id,n)=>({id,name:id,state:'PAUSED',priority:0,order:n}))};api=async(path,body)=>record(path,body);refresh=async()=>{};render=rendered",c);
 const row=id=>({dataset:{id},classList:{add(){},remove(){}}});
 const event=(id,top=false)=>({target:{closest:s=>s==='[data-queue-drop-top]'?(top?{}:null):s.includes('tr[data-id]')?row(id):null},dataTransfer:{setData(){}},preventDefault(){}});
 return {c,listeners,calls,event,get renders(){return renders}};
}
test('group drag follows current queue order, drops before target, and clears cancellation state',async()=>{
 const x=setup();vm.runInContext("state.selected=new Set(['c','b','missing'])",x.c);
 x.listeners.dragstart[0](x.event('b'));
 await x.listeners.drop[0](x.event('a'));
 assert.deepEqual(x.calls,[{path:'queue/reorder',body:{ids:['b','c'],target:'a',after:false}}]);
 assert.equal(vm.runInContext('dragId',x.c),null);
 x.listeners.dragstart[0](x.event('c'));x.listeners.dragend[0]();
 assert.equal(vm.runInContext('dragId',x.c),null);assert.equal(x.calls.length,1);
});
test('connection loss or deleted source during drag never dispatches a stale reorder',async()=>{
 for(const mutation of ["state.offline=true","state.snapshot.downloads=state.snapshot.downloads.filter(r=>r.id!=='b')"]){
  const x=setup();vm.runInContext("state.selected=new Set(['b','c'])",x.c);x.listeners.dragstart[0](x.event('b'));vm.runInContext(mutation,x.c);
  await x.listeners.drop[0](x.event('a'));assert.equal(x.calls.length,0);assert.equal(vm.runInContext('dragId',x.c),null);
 }
});

test('filtered group drag retains selected hidden rows in actual queue order',async()=>{
 const x=setup();vm.runInContext("state.selected=new Set(['c','b']);state.query='b'",x.c);
 x.listeners.dragstart[0](x.event('b'));
 assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(queueDragIds())',x.c)),['b','c']);
 vm.runInContext("state.query=''",x.c);
 await x.listeners.drop[0](x.event('a'));
 assert.deepEqual(x.calls,[{path:'queue/reorder',body:{ids:['b','c'],target:'a',after:false}}]);
});
