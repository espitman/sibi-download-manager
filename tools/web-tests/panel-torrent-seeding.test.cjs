const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/web/panel.js','utf8').split('(async()=>{try{const info=await api')[0];
function render(seeding){
 const app={innerHTML:''};
 const c=vm.createContext({AbortSignal,document:{querySelector:()=>app,querySelectorAll:()=>[],addEventListener(){}},localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,c);
 c.fixture={id:'completed',name:'fixture',state:'COMPLETED',seeding,speed:0,uploadRate:10000,peers:1,seeds:0,downloaded:100,total:100,ratio:1,uploaded:100};
 vm.runInContext(`shell=(_title,content)=>content;renderTorrentDetails(fixture)`,c);
 return app.innerHTML;
}
test('completed seeding torrent exposes the native pause command as Stop seeding',()=>{
 const html=render(true);assert.match(html,/data-action="detail-pause"/);assert.match(html,/Stop seeding/);assert.doesNotMatch(html,/data-action="detail-resume"/);
});
test('completed nonseeding torrent does not offer transfer actions',()=>{
 const html=render(false);assert.doesNotMatch(html,/data-action="detail-pause"|data-action="detail-resume"|Stop seeding/);
});

function renderList(seeding,offline=false){
 const app={innerHTML:'',querySelectorAll:()=>[]};
 const c=vm.createContext({AbortSignal,document:{querySelector:()=>app,querySelectorAll:()=>[],addEventListener(){}},localStorage:{getItem:()=>null},navigator:{},setTimeout,clearTimeout,setInterval(){}});
 vm.runInContext(source,c);
 c.fixture={id:'seed-fixture',name:'fixture',torrent:true,state:'COMPLETED',seeding,speed:0,peers:1,seeds:0,downloaded:100,total:100,priority:0,order:0};
 vm.runInContext(`state.snapshot={time:0,downloads:[fixture]};state.offline=${offline};renderDownloads()`,c);
 return app.innerHTML.match(/<div class="row-actions">(.*?)<\/div>/s)[1];
}
test('completed seeding torrent can be stopped from the Downloads table',()=>{
 const html=renderList(true);assert.match(html,/data-command="pause"/);assert.match(html,/aria-label="Stop seeding"/);assert.doesNotMatch(html.split('</button>')[0],/disabled/);
});
test('completed nonseeding torrent has no enabled resume action in the table',()=>{
 const html=renderList(false);assert.match(html.split('</button>')[0],/disabled/);
});
test('offline seeding torrent cannot dispatch a table pause',()=>{
 const html=renderList(true,true);assert.match(html.split('</button>')[0],/disabled/);
});
