const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const crypto=require('node:crypto').webcrypto;
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
  const sent=[];const control={disabled:false,isConnected:true};const modal={closed:false,close(){this.closed=true}};
  const document={addEventListener(){},querySelector:s=>{
    if(s.startsWith('#add-'))throw Error('Unrelated add-form field accessed');
    if(s==='#modal')return modal;
    if(s==='[data-action="apply-link"]'||s==='[data-action="restore-backup"]')return control;
    return null;
  },querySelectorAll:()=>[]};
  const context=vm.createContext({AbortSignal,document,localStorage:{getItem:()=>null},navigator:{},crypto,setTimeout,clearTimeout,setInterval(){}});
  vm.runInContext(source,context);
  context.dispatch=async(path,body)=>{sent.push({path,body:JSON.parse(JSON.stringify(body))});return {restored:1,skipped:0}};
  vm.runInContext("api=dispatch;refresh=async()=>{};toast=()=>{};state.linkReview={review:'owned-link',preserve:true};state.backupReview={review:'owned-backup'}",context);
  return {context,sent,modal};
}
test('link replacement uses only its own reviewed identity and confirmation',async()=>{
  const x=setup();await vm.runInContext("act('apply-link')",x.context);
  assert.deepEqual(x.sent,[{path:'download/link/apply',body:{review:'owned-link',restartConfirmed:false}}]);
  assert.equal(x.modal.closed,true);assert.equal(vm.runInContext('state.linkWorking',x.context),false);
});
test('backup restore uses only its own review/settings confirmation',async()=>{
  const x=setup();await vm.runInContext("act('restore-backup')",x.context);
  assert.deepEqual(x.sent,[{path:'backup/restore',body:{review:'owned-backup',settings:false,confirmed:true}}]);
  assert.equal(x.modal.closed,true);assert.equal(vm.runInContext('state.backupWorking',x.context),false);
});
test('edited single-link draft invalidates old review and cannot apply it',async()=>{
 const x=setup();
 vm.runInContext(`document.querySelector=selector=>selector==='[data-action="apply-link"]'?{remove(){}}:null;invalidateSingleLinkReview();`,x.context);
 assert.equal(vm.runInContext('state.linkReview',x.context),null);
 await vm.runInContext("act('apply-link')",x.context);
 assert.deepEqual(x.sent,[]);
});
test('single-link request disables URL, confirmation and check/apply until it finishes',()=>{
 const x=setup();
 vm.runInContext(`
   const fields=[{disabled:false},{disabled:false},{disabled:false}];document.querySelectorAll=()=>fields;
   setSingleLinkBusy(true);if(fields.some(field=>!field.disabled)||!state.linkWorking)throw Error('pending editable');
   setSingleLinkBusy(false);if(fields.some(field=>field.disabled)||state.linkWorking)throw Error('not restored');
 `,x.context);
});
