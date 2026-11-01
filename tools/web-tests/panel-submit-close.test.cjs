const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const crypto=require('node:crypto').webcrypto;
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(results,{refreshFails=false}={}) {
 const input={value:'https://example.invalid/a\nhttps://example.invalid/b',isConnected:true,disabled:false};
 const output={textContent:'',isConnected:true};const control={disabled:false,isConnected:true};
 let closed=0;const modal={open:true,addEventListener(){},querySelectorAll:()=>[],close(){closed++;this.open=false}};
 let current=input;
 const document={addEventListener(){},querySelectorAll:()=>[],querySelector:s=>s==='#links'?current:s==='#submit-results'?output:s==='#modal'?modal:s==='[data-action="submit-links"]'?control:s==='[name="add-start"]:checked'?{value:'now'}:{}};
 const c=vm.createContext({document,AbortSignal,crypto,localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,c);
 c.send=async()=>({results});c.update=async()=>{if(refreshFails)throw Error('Snapshot unavailable')};c.message=()=>{};
 vm.runInContext('state.cookieSession=true;readSubmissionOptions=()=>({});api=send;refresh=update;toast=message',c);
 return {c,input,output,modal,get closed(){return closed},replace(){input.isConnected=false;current={value:'new dialog draft',isConnected:true}}};
}
test('a fully confirmed batch closes the add dialog and clears its draft',async()=>{
 const x=setup([{accepted:true,name:'a'},{accepted:true,name:'b'}]);
 await vm.runInContext("act('submit-links')",x.c);
 assert.equal(x.closed,1);assert.equal(x.input.value,'');assert.equal(x.modal.open,false);
});
test('mixed results keep the dialog open with failed links only',async()=>{
 const x=setup([{accepted:true,name:'a'},{accepted:false,error:'Connection reset'}]);
 await vm.runInContext("act('submit-links')",x.c);
 assert.equal(x.closed,0);assert.equal(x.input.value,'https://example.invalid/b');assert.match(x.output.textContent,/Connection reset/);
});
test('confirmed success closes before a later snapshot failure',async()=>{
 const x=setup([{accepted:true,name:'a'},{accepted:true,name:'b'}],{refreshFails:true});
 await assert.rejects(vm.runInContext("act('submit-links')",x.c),/Snapshot unavailable/);
 assert.equal(x.closed,1);assert.equal(x.input.value,'');
});
test('an incomplete or uncertain reply retains the draft and retry identity',async()=>{
 const x=setup([{accepted:true,name:'a'}]);
 await assert.rejects(vm.runInContext("act('submit-links')",x.c),/Incomplete response/);
 assert.equal(x.closed,0);assert.match(x.input.value,/\/a\n/);assert.equal(vm.runInContext('Boolean(state.httpSubmission?.key)',x.c),true);
});
test('a late confirmed result does not close a replacement dialog',async()=>{
 const x=setup([]);let resolve;x.c.send=()=>new Promise(r=>resolve=r);vm.runInContext('api=send',x.c);
 const pending=vm.runInContext("act('submit-links')",x.c);x.replace();resolve({results:[{accepted:true},{accepted:true}]});await pending;
 assert.equal(x.closed,0);
});
