const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const crypto=require('node:crypto').webcrypto;
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
  const input={value:'https://example.invalid/first',isConnected:true};
  const output={textContent:'',innerHTML:'',isConnected:true};
  const modal={open:true};let resolve,requests=0;
  const document={addEventListener(){},querySelector:s=>({'#links':input,'#http-review':output,'#modal':modal}[s]),querySelectorAll:()=>[]};
  const context=vm.createContext({AbortSignal,document,localStorage:{getItem:()=>null},navigator:{},crypto,setTimeout,clearTimeout,setInterval(){},fetch:async()=>{requests++;return new Promise(r=>resolve=r)}});
  vm.runInContext(source,context);vm.runInContext('state.cookieSession=true',context);
  return {context,input,output,modal,get requests(){return requests},reply(){resolve({ok:true,status:200,json:async()=>({results:[{ready:true,name:'first.bin',size:128,line:1}]})})}};
}
test('late metadata never overwrites changed links or a closed dialog',async()=>{
  for(const closed of [false,true]){
    const x=setup(),pending=vm.runInContext('reviewLinks()',x.context);
    if(closed)x.modal.open=false;else x.input.value='https://example.invalid/second';
    x.reply();await pending;
    assert.equal(x.requests,1);assert.equal(x.output.innerHTML,'');
    assert.equal(vm.runInContext('state.reviewingLinks',x.context),false);
  }
});
test('ready rows display name and size without dispatching a submission',async()=>{
  const x=setup(),pending=vm.runInContext('reviewLinks()',x.context);
  x.reply();await pending;
  assert.equal(x.requests,1);assert.match(x.output.innerHTML,/first.bin/);assert.match(x.output.innerHTML,/128 B/);
  assert.equal(x.input.value,'https://example.invalid/first');
  assert.equal(vm.runInContext('Boolean(state.httpSubmitting)',x.context),false);
});
test('an expired apply clears the old success review and action but keeps the replacement URL',async()=>{
 const x=setup();
 vm.runInContext(`
  let removed=false;
  const linkInput={value:'https://example.invalid/new'},review={textContent:'Link is valid'},error={textContent:''},apply={disabled:false,remove(){removed=true;}};
  document.querySelector=s=>({'#refresh-url':linkInput,'#link-review':review,'#link-error':error,'[data-action="apply-link"]':apply}[s]||null);
  document.querySelectorAll=()=>[];
  state.linkReview={review:'expired-token',preserve:true};
  api=async()=>{throw new Error('Link review expired. Check the link again');};
 `,x.context);
 await vm.runInContext("act('apply-link')",x.context);
 assert.equal(vm.runInContext('state.linkReview',x.context),null);
 assert.equal(vm.runInContext('review.textContent',x.context),'');
 assert.match(vm.runInContext('error.textContent',x.context),/expired/);
 assert.equal(vm.runInContext('removed',x.context),true);
 assert.equal(vm.runInContext('linkInput.value',x.context),'https://example.invalid/new');
});
