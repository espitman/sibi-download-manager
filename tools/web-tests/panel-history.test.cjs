const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
const context=vm.createContext({AbortSignal,document:{addEventListener(){}},localStorage:{getItem:()=>null},setTimeout,clearTimeout,setInterval(){}});
vm.runInContext(source,context);
function trace(samples,now=100000){context.samples=samples;context.now=now;return vm.runInContext('historyTrace(samples,1000,now)',context);}
test('history uses elapsed seconds rather than stretching a short observation to a full minute',()=>{
 const result=trace([{time:99000,value:500},{time:100000,value:1000}]);
 assert.match(result,/M983\.33 80\.00 L1000\.00 10\.00/);
});
test('history leaves a gap across connection loss instead of inventing an interpolated speed',()=>{
 const result=trace([{time:80000,value:1000},{time:81000,value:500},{time:100000,value:250}]);
 assert.match(result,/M666\.67 10\.00 L683\.33 80\.00 M1000\.00 115\.00/);
});
test('history expires old rates and rejects future or invalid observations',()=>{
 const result=trace([{time:39999,value:1000},{time:100001,value:1000},{time:90000,value:NaN}]);
 assert.match(result,/d=""/);assert.doesNotMatch(result,/<circle/);
});
test('a single observation stays at its actual time and remains visible as a point',()=>{
 assert.match(trace([{time:100000,value:0}]),/<circle[^>]+cx="1000\.00" cy="150\.00"/);
});
