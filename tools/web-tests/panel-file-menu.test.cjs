const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
const c=vm.createContext({document:{addEventListener(){}},AbortSignal,localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
vm.runInContext(source,c);
function placement(anchor,w,h,vw,vh){c.args=[anchor,w,h,vw,vh];return JSON.parse(vm.runInContext('JSON.stringify(fileMenuPlacement(...args))',c));}
test('bottom-row actions open above the row and remain inside the viewport',()=>{
 const p=placement({top:730,bottom:774,right:1490},260,420,1512,805);
 assert.ok(p.top+420<=730);assert.ok(p.top>=12);assert.ok(p.left>=12);assert.ok(p.left+260<=1500);
});
test('top-row actions open below when there is room including the gap',()=>{
 const p=placement({top:60,bottom:104,right:500},260,350,1512,805);
 assert.equal(p.top,112);
 const tight=placement({top:430,bottom:474,right:500},260,315,1512,805);
 assert.ok(tight.top+315<=430);
});
test('narrow and short viewports clamp the action surface without page overflow',()=>{
 for(const [vw,vh,w,h] of [[320,740,260,420],[240,300,216,276]]){
  const p=placement({top:vh-50,bottom:vh-6,right:vw-4},w,h,vw,vh);
  assert.ok(p.left>=12);assert.ok(p.left+w<=vw-12);assert.ok(p.top>=12);assert.ok(p.top+h<=vh-12);
 }
});
