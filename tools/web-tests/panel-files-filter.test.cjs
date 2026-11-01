const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
 const c=vm.createContext({AbortSignal,document:{querySelector(){},querySelectorAll:()=>[],addEventListener(){}},localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,c);
 vm.runInContext(`state.files=[{id:'video',index:'main',name:'Film.mkv',category:'Video',completedAt:30},{id:'doc',index:'main',name:'Read.pdf',category:'Documents',completedAt:10},{id:'audio',index:'main',name:'Track.mp3',category:'Audio',completedAt:20}];`,c);
 return c;
}
function ids(c){return JSON.parse(vm.runInContext('JSON.stringify(visibleFiles().map(row=>row.id))',c));}
test('phone category and text filters intersect without changing the file list',()=>{
 const c=setup();vm.runInContext("state.fileFilter='Video'",c);assert.deepEqual(ids(c),['video']);
 vm.runInContext("state.query='read'",c);assert.deepEqual(ids(c),[]);
 vm.runInContext("state.fileFilter='All'",c);assert.deepEqual(ids(c),['doc']);
 assert.equal(vm.runInContext('state.files.length',c),3);
});
test('oldest and newest sorts preserve selection and use deterministic equal-date ordering',()=>{
 const c=setup();vm.runInContext("state.fileSelected.add('doc:main');state.fileSort='oldest'",c);assert.deepEqual(ids(c),['doc','audio','video']);
 vm.runInContext("state.fileSort='newest'",c);assert.deepEqual(ids(c),['video','audio','doc']);
 vm.runInContext("state.files.forEach(row=>row.completedAt=1)",c);assert.deepEqual(ids(c),['audio','doc','video']);
 assert.equal(vm.runInContext("state.fileSelected.has('doc:main')",c),true);
});
