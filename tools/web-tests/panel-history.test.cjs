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

test('completed transfers keep a fixed final chart instead of collecting idle samples',()=>{
 vm.runInContext("state.history.clear();state.uploadHistory.clear();recordHistory({id:'a',state:'DOWNLOADING',speed:500},99000);recordHistory({id:'a',state:'COMPLETED',speed:500},100000)",context);
 const before=vm.runInContext("JSON.stringify(state.history.get('a'))",context);
 vm.runInContext("recordHistory({id:'a',state:'COMPLETED',speed:0},170000)",context);
 assert.equal(vm.runInContext("JSON.stringify(state.history.get('a'))",context),before);
 assert.equal(vm.runInContext("historyTime({id:'a',state:'COMPLETED'},170000)",context),100000);
 assert.equal(vm.runInContext("state.history.get('a').at(-1).value",context),0);
});
test('seeding continues sampling and a restarted transfer resumes the live clock',()=>{
 vm.runInContext("recordHistory({id:'a',state:'COMPLETED',seeding:true,speed:0,uploadRate:250},171000)",context);
 assert.equal(vm.runInContext("state.uploadHistory.get('a').at(-1).value",context),250);
 assert.equal(vm.runInContext("historyTime({id:'a',state:'COMPLETED',seeding:true},172000)",context),172000);
 vm.runInContext("recordHistory({id:'a',state:'DOWNLOADING',speed:100},173000)",context);
 assert.equal(vm.runInContext("historyTime({id:'a',state:'DOWNLOADING'},174000)",context),174000);
});
