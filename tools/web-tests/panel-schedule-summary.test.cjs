const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
test('schedule preview follows edited time and phone policy without applying a command',()=>{
 const fields={'#schedule-summary':{innerHTML:''},'#schedule-date':{value:'2026-10-07'},'#schedule-hour':{value:'22'},'#schedule-minute':{value:'5'},'#daily-start':{value:'01:00'},'#daily-end':{value:'07:00'}};
 let kind='ONCE';
 const context=vm.createContext({AbortSignal,document:{querySelector:s=>s.includes('schedule-kind')?{value:kind}:fields[s],addEventListener(){}},localStorage:{getItem:()=>null},navigator:{},crypto:require('node:crypto').webcrypto,setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0],context);
 vm.runInContext("state.snapshot={device:'QA phone',settings:{wifiOnly:true}};updateScheduleSummary()",context);
 assert.match(fields['#schedule-summary'].innerHTML,/2026-10-07 at 22:05/);assert.match(fields['#schedule-summary'].innerHTML,/Wi-Fi only/);
 fields['#schedule-hour'].value='24';vm.runInContext('updateScheduleSummary()',context);assert.match(fields['#schedule-summary'].innerHTML,/Choose a valid date and time/);
 kind='DAILY';vm.runInContext('updateScheduleSummary()',context);assert.match(fields['#schedule-summary'].innerHTML,/01:00–07:00/);
 kind='NONE';vm.runInContext('state.snapshot.settings.wifiOnly=false;updateScheduleSummary()',context);assert.match(fields['#schedule-summary'].innerHTML,/normal download queue/);assert.match(fields['#schedule-summary'].innerHTML,/Phone network policy applies/);
});
