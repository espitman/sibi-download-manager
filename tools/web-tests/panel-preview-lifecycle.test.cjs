const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function setup(mime){
 const events={},closed=[];let paused=0,loaded=0,removed=false,emptied=false;
 const status={isConnected:true,hidden:false,className:'preview-status muted',textContent:'Loading preview…'};
 const media={addEventListener:(type,fn)=>events[type]=fn,removeAttribute:name=>{assert.equal(name,'src');removed=true}};
 if(mime.startsWith('video/')||mime.startsWith('audio/')){media.pause=()=>paused++;media.load=()=>loaded++;}
 const preview={firstElementChild:media,replaceChildren:()=>emptied=true};
 const modal={classList:{add(){}},querySelector:selector=>selector==='.file-preview'?preview:status,addEventListener:(type,fn)=>{assert.equal(type,'close');closed.push(fn)}};
 const document={addEventListener(){},querySelector:()=>modal};
 const c=vm.createContext({document,AbortSignal,localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,c);vm.runInContext('showModal=()=>{}',c);
 c.file={id:'owned',index:'main',name:'owned media',mime,preview:true,available:true};
 vm.runInContext('showFilePreview(file)',c);
 return {events,status,close:()=>closed.forEach(fn=>fn()),get paused(){return paused},get loaded(){return loaded},get removed(){return removed},get emptied(){return emptied}};
}
test('closing audio or video preview stops playback and releases its network source',()=>{
 for(const mime of ['audio/wav','video/mp4']){const x=setup(mime);x.events.loadedmetadata();assert.equal(x.status.hidden,true);x.close();assert.equal(x.paused,1);assert.equal(x.loaded,1);assert.equal(x.removed,true);assert.equal(x.emptied,true);}
});
test('corrupt image gives a visible fallback while late detached events cannot alter another dialog',()=>{
 const x=setup('image/png');x.events.error();assert.equal(x.status.hidden,false);assert.match(x.status.textContent,/cannot be previewed/);assert.match(x.status.textContent,/Download/);
 x.status.isConnected=false;x.status.textContent='new dialog';x.events.load();x.events.error();assert.equal(x.status.textContent,'new dialog');x.close();assert.equal(x.removed,true);assert.equal(x.emptied,true);
});
