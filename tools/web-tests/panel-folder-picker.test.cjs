const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(){
 const requests=[];
 const c=vm.createContext({AbortSignal,document:{querySelector(selector){return selector==='#new-folder-name'?{value:'Owned new folder'}:{disabled:false};},querySelectorAll:()=>[],addEventListener(){}},localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){},request:(path,body)=>new Promise((resolve,reject)=>requests.push({path,body,resolve,reject}))});
 vm.runInContext(source,c);
 vm.runInContext(`renderMovePicker=()=>{};api=request;state.movePicker={files:[],listing:{current:'old',folders:[]},selected:'old',loading:false};`,c);
 return {c,requests};
}
test('folder browsing clears the old destination and ignores responses arriving out of order',async()=>{
 const {c,requests}=setup();
 const first=vm.runInContext("loadMoveFolders('first')",c);
 assert.equal(vm.runInContext('state.movePicker.selected',c),null);
 assert.equal(vm.runInContext('state.movePicker.loading',c),true);
 const second=vm.runInContext("loadMoveFolders('second')",c);
 requests[1].resolve({current:'second',folders:[]});await second;
 requests[0].resolve({current:'first',folders:[]});await first;
 assert.equal(vm.runInContext('state.movePicker.selected',c),'second');
 assert.equal(vm.runInContext('state.movePicker.loading',c),false);
});
test('failed folder browse cannot submit the previously selected destination',async()=>{
 const {c,requests}=setup();const pending=vm.runInContext("loadMoveFolders('unavailable')",c);
 await vm.runInContext("act('confirm-file-move',{})",c);
 assert.equal(requests.length,1);
 requests[0].reject(new Error('Folder access removed'));await pending;
 assert.equal(vm.runInContext('state.movePicker.selected',c),null);
 await vm.runInContext("act('confirm-file-move',{})",c);
 assert.equal(requests.length,1);
 assert.equal(vm.runInContext('state.movePicker.error',c),'Folder access removed');
});
test('closing or replacing the picker prevents a late folder response from altering the new dialog',async()=>{
 const {c,requests}=setup();const pending=vm.runInContext("loadMoveFolders('first')",c);
 vm.runInContext("state.movePicker={selected:'new-choice',loading:false}",c);
 requests[0].resolve({current:'first',folders:[]});await pending;
 assert.equal(vm.runInContext('state.movePicker.selected',c),'new-choice');
});

test('tree retains parent and siblings when a branch expands and marks only its selected destination',()=>{
 const {c}=setup();
 vm.runInContext(`state.movePicker.folderListings=new Map([['phone',{folders:[{id:'downloads',name:'Downloads'},{id:'books',name:'Books'}]}],['downloads',{folders:[{id:'sdm',name:'SDM & files'}]}]]);state.movePicker.expandedFolders=new Set(['downloads']);state.movePicker.selected='sdm';`,c);
 const tree=vm.runInContext('moveFolderTree(state.movePicker)',c);
 assert.match(tree,/Collapse Downloads/);assert.match(tree,/Books/);assert.match(tree,/SDM &amp; files/);
 assert.match(tree,/data-move-select="sdm" aria-pressed="true"/);
 assert.match(tree,/data-move-select="downloads" aria-pressed="false"/);
 vm.runInContext("state.movePicker.expandedFolders.clear()",c);
 assert.doesNotMatch(vm.runInContext('moveFolderTree(state.movePicker)',c),/SDM &amp; files/);
});

test('new folder uses the selected tree destination rather than the last expanded branch',async()=>{
 const {c,requests}=setup();
 vm.runInContext("state.movePicker.selected='chosen';state.movePicker.listing={current:'last-expanded',folders:[]}",c);
 const pending=vm.runInContext("act('move-create-folder',{})",c);
 assert.equal(requests[0].path,'folders/create');
 assert.equal(requests[0].body.parent,'chosen');
 requests[0].resolve({id:'created',name:'Owned new folder'});
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(requests[1].path,'folders/chosen');
 requests[1].resolve({current:'chosen',folders:[{id:'created',name:'Owned new folder'}]});
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(requests[2].path,'folders/created');
 requests[2].resolve({current:'created',folders:[]});await pending;
 assert.equal(vm.runInContext('state.movePicker.selected',c),'created');
});
