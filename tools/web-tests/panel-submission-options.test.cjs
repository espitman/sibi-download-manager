const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(values={}) {
  let requests=0;
  const defaults={'#links':'https://example.invalid/owned.bin','#add-schedule-kind':'NONE','#add-schedule-start':'','#add-schedule-end':'','#add-daily-start':'01:00','#add-daily-end':'07:00','#add-limit-value':'','#add-limit-unit':'MB/s'};
  const fields=Object.fromEntries(Object.entries({...defaults,...values}).map(([key,value])=>[key,{value,disabled:false}]));
  const document={addEventListener(){},querySelector:s=>s==='[name="add-start"]:checked'?{value:'now'}:fields[s]||{},querySelectorAll:()=>[]};
  const context=vm.createContext({AbortSignal,document,localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){},fetch:async()=>{requests++;throw Error('Unexpected network dispatch')}});
  vm.runInContext(source,context);
  vm.runInContext("state.cookieSession=true;state.snapshot={zone:'Asia/Tehran'}",context);
  return {context,fields,get requests(){return requests}};
}
test('invalid submission options do not send or clear the link draft',async()=>{
  for(const values of [
    {'#add-limit-value':'0'}, {'#add-limit-value':'Infinity'}, {'#add-limit-value':'-2'},
    {'#add-schedule-kind':'ONCE'},
    {'#add-schedule-kind':'ONCE','#add-schedule-start':'2026-10-04T12:00','#add-schedule-end':'2026-10-04T11:00'},
    {'#add-schedule-kind':'DAILY','#add-daily-start':'24:00'},
    {'#add-schedule-kind':'DAILY','#add-daily-start':'07:00'},
  ]) {
    const x=setup(values);
    await assert.rejects(vm.runInContext("act('submit-links')",x.context));
    assert.equal(x.requests,0);
    assert.equal(x.fields['#links'].value,'https://example.invalid/owned.bin');
    assert.equal(x.fields['#links'].disabled,false);
    assert.equal(vm.runInContext('Boolean(state.httpSubmitting)',x.context),false);
  }
});
test('overnight daily and decimal speed options preserve phone zone and selected unit',()=>{
  const x=setup({'#add-schedule-kind':'DAILY','#add-daily-start':'23:00','#add-daily-end':'06:30','#add-limit-value':'0.5','#add-limit-unit':'MB/s'});
  const result=JSON.parse(vm.runInContext('JSON.stringify(readSubmissionOptions())',x.context));
  assert.deepEqual(result,{schedule:{kind:'DAILY',startMinute:1380,endMinute:390,zone:'Asia/Tehran'},speedLimit:{value:'0.5',unit:'MB/s'}});
});
