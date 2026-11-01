const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
 const context=vm.createContext({AbortSignal,document:{querySelector(){},querySelectorAll:()=>[],addEventListener(){}},localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,context);
 vm.runInContext("state.bulkLink={review:{candidates:[{index:0,preserve:{a:true,b:false}},{index:1,preserve:{a:false,b:true}}]},targets:new Map(),restarts:new Set()}",context);
 return context;
}
test('bulk refresh refuses duplicate targets and unconfirmed data restart',()=>{
 const c=setup();vm.runInContext("state.bulkLink.targets.set(0,'a');state.bulkLink.targets.set(1,'a')",c);
 assert.throws(()=>vm.runInContext('bulkLinkMappings()',c),/only once/);
 vm.runInContext("state.bulkLink.targets.delete(1);state.bulkLink.targets.set(0,'b')",c);
 assert.throws(()=>vm.runInContext('bulkLinkMappings()',c),/Confirm restart/);
 vm.runInContext('state.bulkLink.restarts.add(0)',c);
 assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(bulkLinkMappings())',c)),[{index:0,id:'b',restartConfirmed:true}]);
});
test('matching preserved files can apply independently while skipped links stay absent',()=>{
 const c=setup();vm.runInContext("state.bulkLink.targets.set(1,'b')",c);
 assert.deepEqual(JSON.parse(vm.runInContext('JSON.stringify(bulkLinkMappings())',c)),[{index:1,id:'b',restartConfirmed:false}]);
});
test('bulk requests lock draft and matching controls until completion',()=>{
 const c=setup();
 vm.runInContext(`
   const controls=[{disabled:false},{disabled:false},{disabled:false}];
   document.querySelectorAll=()=>controls;
   renderBulkLinkReview=()=>{ if(state.linkWorking)throw new Error('rendered before unlock'); };
   setBulkLinkBusy(true);
   if(!state.linkWorking||controls.some(control=>!control.disabled))throw new Error('mutable pending draft');
   setBulkLinkBusy(false);
   if(state.linkWorking||controls.some(control=>control.disabled))throw new Error('draft remained locked');
 `,c);
});
test('editing bulk URLs discards the old checked mapping before apply',()=>{
 const c=setup();
 vm.runInContext(`
   let removed=false;
   const review={textContent:'old review'},error={textContent:'old error'};
   document.querySelector=selector=>selector==='#bulk-link-review'?review:selector==='#bulk-link-error'?error:{remove(){removed=true;}};
   state.bulkLink.review.review='old-token';state.bulkLink.targets.set(0,'a');state.bulkLink.restarts.add(1);state.bulkLink.applySignature='old';
   invalidateBulkLinkReview();
   if(state.bulkLink.review||state.bulkLink.targets.size||state.bulkLink.restarts.size||state.bulkLink.applySignature)throw new Error('stale checked data remains');
   if(review.textContent||error.textContent||!removed)throw new Error('stale apply UI remains');
 `,c);
});
test('pending bulk request retains its immutable review despite an input event',()=>{
 const c=setup();
 vm.runInContext(`state.linkWorking=true;const previous=state.bulkLink.review;invalidateBulkLinkReview();if(state.bulkLink.review!==previous)throw new Error('pending review lost');`,c);
});
