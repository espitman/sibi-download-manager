const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
const context=vm.createContext({AbortSignal,document:{addEventListener(){}},localStorage:{getItem:()=>null},setTimeout,clearTimeout,setInterval(){}});
vm.runInContext(source,context);
function eta(downloaded,total,speed){context.fixture={downloaded,total,speed};return vm.runInContext('remainingTime(fixture)',context);}
test('remaining time retains seconds and rounds incomplete seconds upward like native',()=>{
 assert.equal(eta(5,100,10),'00:10');
 assert.equal(eta(0,432000,1000),'07:12');
 assert.equal(eta(0,3723000,1000),'01:02:03');
});
test('completed, overshot, unknown and stopped downloads do not claim zero minutes remaining',()=>{
 assert.equal(eta(100,100,10),'—');
 assert.equal(eta(101,100,10),'—');
 assert.equal(eta(0,null,10),'—');
 assert.equal(eta(5,100,0),'—');
});

test('disconnected snapshots do not present stale ETA as a live estimate',()=>{
 vm.runInContext('state.offline=true',context);
 assert.equal(eta(5,100,10),'—');
 vm.runInContext('state.offline=false',context);
 assert.equal(eta(5,100,10),'00:10');
});
