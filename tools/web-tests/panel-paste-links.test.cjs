const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(clipboard){
  const input={value:'first\nreplace\nlast',selectionStart:6,selectionEnd:13,isConnected:true,focus(){this.focused=true},setRangeText(text,start,end){this.value=this.value.slice(0,start)+text+this.value.slice(end)}};
  const modal={open:true};let message='',reviews=0;
  const document={addEventListener(){},querySelector:s=>({'#links':input,'#modal':modal,'#http-review':{textContent:''}}[s]),querySelectorAll:()=>[]};
  const context=vm.createContext({AbortSignal,document,localStorage:{getItem:()=>null},navigator:{clipboard},setTimeout,clearTimeout,setInterval(){}});
  vm.runInContext(source,context);context.recordToast=text=>message=text;context.review=()=>reviews++;
  vm.runInContext('toast=recordToast;scheduleLinkReview=review',context);
  return {context,input,modal,get message(){return message},get reviews(){return reviews}};
}
test('clipboard paste replaces selection only and requests review, never submits',async()=>{
  const x=setup({readText:async()=> 'owned-link'});
  await vm.runInContext('pasteLinks()',x.context);
  assert.equal(x.input.value,'first\nowned-link\nlast');
  assert.equal(x.reviews,1);assert.equal(x.input.focused,true);
  assert.equal(vm.runInContext('Boolean(state.httpSubmitting)',x.context),false);
});
test('unsupported or denied clipboard preserves draft and explains manual paste',async()=>{
  for(const clipboard of [undefined,{readText:async()=>{throw Error('Denied')}}]){
    const x=setup(clipboard),before=x.input.value;
    await vm.runInContext('pasteLinks()',x.context);
    assert.equal(x.input.value,before);assert.equal(x.reviews,0);assert.match(x.message,/Ctrl\+V/);
  }
});
test('late clipboard result cannot overwrite an edited draft or closed dialog',async()=>{
  for(const close of [false,true]){
    let reply;const x=setup({readText:()=>new Promise(resolve=>reply=resolve)});
    const pending=vm.runInContext('pasteLinks()',x.context);
    if(close)x.modal.open=false;else x.input.value='new draft';
    const expected=x.input.value;reply('late clipboard');await pending;
    assert.equal(x.input.value,expected);assert.equal(x.reviews,0);
  }
});
