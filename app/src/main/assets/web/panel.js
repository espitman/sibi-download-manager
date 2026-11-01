'use strict';
const $ = selector => document.querySelector(selector);
const escapeText = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const icon = (name,cls='') => `<svg class="icon${cls?' '+escapeText(cls):''}" aria-hidden="true"><use href="#${name}"></use></svg>`;
const logo = `<div class="logo"><span class="monogram">SD</span>SDM</div>`;
const state = {session: localStorage.getItem('sdm-session'), page:'downloads', tab:'all', query:'', selected:new Set(), snapshot:null, device:'Your phone', offline:false, detail:null, history:new Map(), approval:null, cookieSession:false, files:null, fileQuery:'', fileSort:'name', fileFilter:'All', refreshing:false, lastSample:0, pendingCancel:null, torrent:null, addMode:"links", pollingTorrent:false, pendingRemoval:null, removing:false, torrentDetails:new Map(), torrentTab:"overview", uploadHistory:new Map(), fileSelected:new Set(), fileFolder:null, fileView:"list", fileMenu:null, storage:null, fileLocation:"Downloads", fileWorking:false, movePicker:null};
const authorized = () => Boolean(state.session || state.cookieSession);
const speed = n => n <= 0 ? '0 KB/s' : n < 1000 ? '<1 KB/s' : n < 1000000 ? `${Math.floor(n/1000)} KB/s` : `${(n/1000000).toFixed(1)} MB/s`;
const size = n => n == null ? '—' : n < 1000 ? `${n} B` : n < 1000000 ? `${(n/1000).toFixed(1)} KB` : n < 1000000000 ? `${(n/1000000).toFixed(1)} MB` : `${(n/1000000000).toFixed(1)} GB`;
const remainingTime = row => {
  const remaining=(row.total??0)-row.downloaded;
  if(state.offline||!(row.speed>0)||!(remaining>0))return '—';
  const seconds=Math.ceil(remaining/row.speed);
  const hours=Math.floor(seconds/3600),minutes=Math.floor(seconds%3600/60),rest=seconds%60;
  const two=n=>String(n).padStart(2,'0');
  return hours>0?`${two(hours)}:${two(minutes)}:${two(rest)}`:`${two(minutes)}:${two(rest)}`;
};
const liveSpeed = (n,id='',field='speed') => `<span data-live-speed="${Number(n)||0}" data-live-speed-row="${escapeText(id)}" data-live-speed-field="${field}">${state.offline?'—':speed(n)}</span>`;
const liveRemaining = row => `<span data-live-eta="${escapeText(row.id)}">${remainingTime(row)}</span>`;
const percent = row => row.total > 0 ? Math.min(100, row.downloaded/row.total*100) : 0;
const pauseAction = row => ['CONNECTING','DOWNLOADING'].includes(row.state)||row.seeding||row.retryAt!=null;
const transferLabel = row => row.seeding?'Stop seeding':pauseAction(row)?'Pause':row.state==='QUEUED'?'Start':row.state==='FAILED'?'Retry':'Resume';
const button = (text, action, glyph, cls='') => `<button class="${cls}" data-action="${action}">${glyph ? icon(glyph) : ''}${text}</button>`;
async function copyText(text) {
  if(navigator.clipboard?.writeText)return navigator.clipboard.writeText(text);
  const field=document.createElement('textarea');field.value=text;field.style.position='fixed';field.style.opacity='0';document.body.append(field);field.select();
  try{if(!document.execCommand('copy'))throw new Error('Your browser could not copy the link');}finally{field.remove();}
}
function toast(text) { $('#toast').textContent = text; $('#toast').style.display='block'; clearTimeout(toast.timer); toast.timer=setTimeout(()=>$('#toast').style.display='none',3500); }
async function api(path, body, options={}) {
  const headers = {'Accept':'application/json'};
  const sessionAtSend=state.session;
  const cookieAtSend=state.cookieSession;
  const wasAuthenticated=authorized();
  const publicRoute=['request','pair','approval'].includes(path);
  if(body!=null&&!publicRoute&&state.offline)throw new Error('Reconnect before changing your phone');
  if(sessionAtSend&&!publicRoute) headers.Authorization=`Bearer ${sessionAtSend}`;
  if(body != null) {
    headers['Content-Type']=options.contentType||'application/json';
    if(wasAuthenticated&&!publicRoute) headers['Idempotency-Key']=options.commandKey || Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');
  }
  const response=await fetch(`/api/${path}`,{method:body == null ? 'GET':'POST',headers,body:body == null ? undefined:options.contentType?body:JSON.stringify(body),signal:options.signal});
  const value=await response.json();
  if(response.status===401 && !publicRoute && wasAuthenticated && state.session===sessionAtSend && state.cookieSession===cookieAtSend) {state.cookieSession=false;state.session=null;localStorage.removeItem('sdm-session');state.snapshot=null;state.files=null;state.detail=null;state.torrent=null;state.movePicker=null;state.fileRename=null;state.fileDelete=null;state.backupReview=null;state.linkReview=null;state.httpSubmission=null;state.settingsDraft=null;state.approvedBrowsers=null;state.bulkLink=null;state.addDestination=null;state.downloadCommandRetry=null;state.linkDownload=null;state.pendingCancel=null;state.pendingRemoval=null;state.folderRequest=null;sessionStorage.removeItem('sdm-folder-request');state.selected.clear();state.history.clear();state.uploadHistory.clear();state.torrentDetails.clear();state.fileSelected.clear();if($('#modal').open)$('#modal').close();$('#modal').innerHTML='';renderPairing('Access revoked on your phone');}
  if(!response.ok) throw Object.assign(new Error(value.error || 'Could not complete this action'),{status:response.status});
  return value;
}
function setSession(secret) {state.cookieSession=true;state.session=secret;localStorage.setItem('sdm-session',secret);state.approval=null;refresh();}
function browserName() {return navigator.userAgent.includes('Firefox')?'Firefox':navigator.userAgent.includes('Edg/')?'Edge':'Chrome / browser';}
function renderPairing(title='Control SDM over your local network') {
  closeFileMenu();
  if(title==='Access revoked on your phone') {
    const nav=['Downloads','Files','Settings'].map(label=>`<button disabled>${icon(label==='Downloads'?'download':label==='Files'?'folder':'settings')}${label}</button>`).join('');
    $('#app').innerHTML=`<aside class="sidebar revoked-sidebar">${logo}<nav aria-label="Main">${nav}</nav><div class="device-status"><span class="dot offline"></span><div>${escapeText(state.device)}<p class="danger">Access revoked</p></div></div></aside><main class="revoked-page"><section class="panel revoked-card" aria-labelledby="revoked-title"><div class="revoked-symbol">${icon('lock')}</div><h1 id="revoked-title">Access revoked on your phone</h1><p>You ended this device’s access in SDM on your phone.<br>Request access again to continue managing downloads on this device.</p>${button('Request access again','request-access','link','primary')}<div id="pair-status" role="status"></div></section></main>`;
    return;
  }
  $('#app').innerHTML=`<main class="pairing"><div class="pair-card">${logo}<h1>${escapeText(title)}</h1><p>Approve this browser on your phone</p><div class="pair-device">${icon('phone')}<h3>${escapeText(state.device)}</h3><p>Downloads and files stay on your phone.</p></div>${button('Request access','request-access','link','primary')}${button('Use pairing code','show-code','lock')}<div id="pair-status" role="status"></div><p>${icon('lock')} Only approved browsers can control this phone.</p></div></main>`;
}
function shell(title, content) {
  const nav=['downloads','files','settings'].map(page=>`<button data-page="${page}" class="${state.page===page?'active':''}">${icon(page==='downloads'?'download':page==='files'?'folder':'settings')}${page[0].toUpperCase()+page.slice(1)}</button>`).join('');
  return `<aside class="sidebar">${logo}<nav aria-label="Main">${nav}</nav><div class="device-status">${icon('phone')}<div>${escapeText(state.device)}<p class="${state.offline?'danger':'green'}"><span class="dot ${state.offline?'offline':''}"></span>${state.offline?'Disconnected':'Connected'}</p></div></div></aside><main class="workspace ${title==='Download details'?'http-details-page':state.page+'-page'}"><header class="topbar">${['downloads','files'].includes(state.page)&&title!=='Download details'?`<div class="mobile-brand" aria-hidden="true">${logo}</div>`:''}${title==='Download details'?button('','back','back','plain details-mobile-back').replace('<button ','<button aria-label="Back to downloads" '):''}<div class="heading"><h1>${title}</h1><p>Downloads run on your phone</p></div><div class="device-chip">${icon('phone')}<div>${escapeText(state.device)}<small class="mobile-device-status ${state.offline?'danger':'green'}">${state.offline?'Disconnected':'Connected'}</small></div></div><label class="search">${icon('search')}<input id="search" type="search" placeholder="Search ${state.page==='files'?'files':'downloads'}…" aria-label="Search ${state.page==='files'?'files':'downloads'}" value="${escapeText(state.query)}"></label>${button('Add download','add','plus','primary')}</header>${state.offline?`<div class="banner connection-banner" role="status">${icon('warning')}<span>Connection lost. Downloads continue on your phone.<br><small>Last sync: ${new Date(state.snapshot?.time||Date.now()).toLocaleTimeString()}</small></span>${button('Retry','refresh')}</div>`:''}${content}</main><nav class="mobile-nav" aria-label="Main">${nav}</nav>`;
}
function rows() {return (state.snapshot?.downloads||[]).filter(row=>state.tab==='all'||state.tab==='queue'&&row.state!=='COMPLETED'||state.tab==='completed'&&row.state==='COMPLETED').filter(row=>row.name.toLowerCase().includes(state.query.toLowerCase())).sort((a,b)=>b.priority-a.priority||a.order-b.order||(a.createdAt||0)-(b.createdAt||0)||a.id.localeCompare(b.id));}
function renderDownloads() {
  const all=state.snapshot?.downloads||[];
  const active=all.filter(row=>['CONNECTING','DOWNLOADING'].includes(row.state));
  const stats=[[active.length,'active','play'],[liveSpeed(active.reduce((n,r)=>n+r.speed,0)),'total speed','speed'],[size(active.reduce((n,r)=>n+Math.max(0,(r.total||0)-r.downloaded),0)),'remaining','file'],[active.reduce((n,r)=>n+(r.torrent?r.peers:r.connections),0),'connections / peers','peers']].map(([value,label,glyph])=>`<div class="stat">${icon(glyph)}<div><strong>${value}</strong><small>${label}</small></div></div>`).join('');
  const tabs=['all','queue','completed'].map(tab=>`<button data-tab="${tab}" class="${state.tab===tab?'active':''}">${tab[0].toUpperCase()+tab.slice(1)}<span class="count">${all.filter(r=>tab==='all'||(tab==='completed'?r.state==='COMPLETED':r.state!=='COMPLETED')).length}</span></button>`).join('');
  const body=rows().map((row,index)=>`<tr class="${state.selected.has(row.id)?'selected':''}" aria-selected="${state.selected.has(row.id)}" data-row-state="${row.state}" data-seeding="${!!row.seeding}" data-id="${escapeText(row.id)}" draggable="${state.tab==='queue'&&!state.offline}"><td><input type="checkbox" data-select="${escapeText(row.id)}" aria-label="Select ${escapeText(row.name)}" ${state.selected.has(row.id)?'checked':''}></td><td><div class="download-identity">${state.tab==='queue'?`<span class="queue-grip" title="Drag to reorder" aria-hidden="true">${icon('grip')}</span><span class="queue-position" aria-label="Queue position ${index+1}">${index+1}</span>`:''}<div class="name-cell" data-details="${escapeText(row.id)}" tabindex="0" role="button">${icon(row.torrent?'link':'file')}<span>${escapeText(row.name)}</span></div></div></td><td><span class="download-status ${row.seeding||row.state==='DOWNLOADING'?'green':row.state==='FAILED'?'danger':'muted'}"><span class="status-dot" aria-hidden="true"></span>${row.seeding?'Seeding':row.retryAt!=null?`Retry in ${Math.max(0,Math.ceil((row.retryAt-state.snapshot.time)/1000))}s`:escapeText(row.state[0]+row.state.slice(1).toLowerCase())}</span>${row.state==='FAILED'&&row.error?`<small class="mobile-download-error">${escapeText(row.error)}</small>`:''}</td><td>${percent(row).toFixed(0)}%<div class="progress"><i style="width:${percent(row)}%"></i></div></td><td>${liveSpeed(row.speed,row.id)}<br><small class="muted">${size(row.downloaded)} / ${size(row.total)}</small></td><td>${liveRemaining(row)}</td><td>${row.torrent?row.peers+' peers':row.connections}</td><td><div class="row-actions"><button data-command="${pauseAction(row)?'pause':row.state==='FAILED'?'retry':'resume'}" data-id="${escapeText(row.id)}" aria-label="${transferLabel(row)}" ${state.offline||(row.state==='COMPLETED'&&!row.seeding)?'disabled':''}>${icon(pauseAction(row)?'pause':'play')}</button><button data-details="${escapeText(row.id)}" aria-label="Download details">${icon('more')}</button></div></td></tr>`).join('');
  const list=body?`<div class="table-wrap downloads-table"><table><colgroup><col class="select-column"><col class="name-column"><col class="status-column"><col class="progress-column"><col class="speed-column"><col class="eta-column"><col class="connections-column"><col class="actions-column"></colgroup><thead><tr><th><input id="select-all" type="checkbox" aria-label="Select all visible downloads"></th><th>Name</th><th>Status</th><th>Progress</th><th>Speed / Size</th><th>ETA</th><th>Connections</th><th>Actions</th></tr></thead><tbody>${body}</tbody></table></div>`:`<div class="panel empty">${icon('download')}<h2>${state.query?'No matching downloads':'No downloads yet'}</h2><p>Add a link or a torrent to start downloading on your phone.</p><div class="actions empty-actions">${button('Add links','add','plus','primary')}${button('Add torrent','add-torrent','file')}</div></div>`;
  const selectedRows=all.filter(row=>state.selected.has(row.id));
  const pauseSelected=selectedRows.some(pauseAction);
  const bulkControl=(text,action,glyph,disabled=false,cls='')=>button(text,action,glyph,cls).replace('<button',disabled?'<button disabled':'<button');
  const bulk=state.selected.size?`<div class="bulk download-bulk" role="group" aria-label="Selected downloads"><button class="plain clear-selection" data-action="clear-download-selection" aria-label="Clear selection">${icon('close')}</button><span class="selection">${state.selected.size} selected</span>${bulkControl(pauseSelected?'Pause':'Resume','bulk-toggle',pauseSelected?'pause':'play',!selectedRows.some(row=>row.state!=='COMPLETED'||row.seeding))}${button('Priority','bulk-priority','priority')}${bulkControl('Refresh links','bulk-refresh-links','link',selectedRows.some(row=>row.torrent||row.state==='COMPLETED'))}${bulkControl('Move to top','bulk-top','download',!selectedRows.some(row=>row.state!=='COMPLETED'))}${button('Remove','bulk-remove','close','danger')}</div>`:'';
  $('#app').innerHTML=shell('Downloads',`<section class="stats">${stats}</section><nav class="tabs" aria-label="Download filters">${tabs}</nav>${list}${bulk}${state.tab==='queue'&&body?`<div class="queue-drop-top" data-queue-drop-top><span>${icon('arrow-up')}Drop here to reorder</span>${button('Move to top','bulk-top',null,'primary').replace('<button',state.selected.size?'<button':'<button disabled')}</div>`:''}`);
  $('#app').querySelectorAll('.download-bulk>button:not(.clear-selection)').forEach(control=>{control.setAttribute('aria-label',control.textContent.trim());control.title=control.textContent.trim();});
  const selectAll=$('#select-all'),visible=rows();
  if(selectAll){const count=visible.filter(row=>state.selected.has(row.id)).length;selectAll.checked=visible.length>0&&count===visible.length;selectAll.indeterminate=count>0&&count<visible.length;}
  if(state.offline) $('#app').querySelectorAll('[data-command],[data-action^="bulk"],[data-action="add"],[data-action="add-torrent"]').forEach(b=>b.disabled=true);
}
function recentHistory(samples,now=Date.now()) {
  return samples.filter(sample=>Number.isFinite(sample.time)&&Number.isFinite(sample.value)&&sample.time>=now-60000&&sample.time<=now);
}
function historyTrace(samples,peak,now=Date.now(),cls='') {
  const visible=recentHistory(samples,now);let previous=null;
  const path=visible.map(sample=>{
    const x=(sample.time-(now-60000))/60,y=150-Math.max(0,sample.value)/Math.max(1,peak)*140;
    const command=previous==null||sample.time-previous>2500?'M':'L';previous=sample.time;
    return `${command}${x.toFixed(2)} ${y.toFixed(2)}`;
  }).join(' ');
  const last=visible.at(-1);
  return `<path class="history-line ${cls}" d="${path}"/>${last?`<circle class="history-point ${cls}" cx="${((last.time-(now-60000))/60).toFixed(2)}" cy="${(150-Math.max(0,last.value)/Math.max(1,peak)*140).toFixed(2)}" r="3"/>`:''}`;
}
function renderDetails() {
  const row=state.snapshot.downloads.find(r=>r.id===state.detail);
  if(!row) {state.detail=null;return renderDownloads();}
  if(row.torrent)return renderTorrentDetails(row);
  const now=Date.now(),rates=recentHistory(state.history.get(row.id)||[],now);
  const peak=Math.max(1000,...rates.map(sample=>sample.value));
  const trace=historyTrace(rates,peak,now);
  const transferActions=`${row.state==='COMPLETED'?'':button(transferLabel(row),pauseAction(row)?'detail-pause':'detail-resume',pauseAction(row)?'pause':'play')}${row.state==='COMPLETED'?'':button('Cancel','detail-cancel','close','danger')}`;
  const fileActions=`${button('Priority','detail-priority','priority','details-mobile-aux')}${button('Copy link','copy-http-link','link','details-mobile-aux')}${button('Rename','detail-rename','rename')}${button('Verify checksum','verify-checksum','lock')}${button('Open folder','open-download-folder','folder')}${row.state==='COMPLETED'?'':button('Refresh link','show-link-refresh','link')}${button('Remove','detail-remove','close','danger')}`;
  const metric=(v,label)=>`<div class="panel"><strong>${v}</strong><small>${label}</small></div>`;
  $('#app').innerHTML=shell('Download details',`<div class="details-title">${button('','back','back','plain').replace('<button ','<button aria-label="Back to downloads" ')}<h2>${escapeText(row.name)}</h2></div><section class="panel hero http-hero"><div class="http-file-identity">${icon('file')}</div><div class="ring" style="--percent:${percent(row)}"><strong>${percent(row).toFixed(0)}%</strong></div><div class="http-identity"><h2>${escapeText(row.name)}</h2><p class="http-source muted">${escapeText(row.technical?.source||'—')}</p><p class="http-destination muted">${escapeText(row.technical?.savePath||'—')}</p><div class="actions details-primary-desktop">${transferActions}${button('Priority','detail-priority','priority')}${button('Copy link','copy-http-link','link')}</div></div><div class="http-mobile-summary"><span>${icon('download')}${size(row.downloaded)} / ${size(row.total)}</span><span>${icon('clock')}${liveRemaining(row)}</span><span class="gold">${icon('speed')}${liveSpeed(row.speed,row.id)}</span></div></section><section class="details-metrics http-metrics">${metric(liveSpeed(row.speed,row.id),'Download speed')}${metric(size(row.downloaded)+' / '+size(row.total),'Downloaded')}${metric(liveRemaining(row),'Time remaining')}${metric(row.connections,'Connections')}</section><section class="panel http-history"><div class="form-row" style="margin-top:0"><h3>Speed history</h3><span class="muted">Last 60 sec · ${liveSpeed(row.speed,row.id)}</span></div><div class="http-chart-body"><div class="http-chart-axis" aria-hidden="true"><span>${speed(peak)}</span><span>${speed(peak/2)}</span><span>0</span></div><svg class="chart" role="img" aria-label="Download speed history" viewBox="0 0 1000 160" preserveAspectRatio="none"><path class="grid" d="M0 20h1000M0 65h1000M0 110h1000M0 150h1000"/>${trace}</svg></div><div class="chart-times"><span>60 sec ago</span><span>Now</span></div></section><div class="actions details-mobile-transfer">${transferActions}</div><details class="http-technical" ${state.httpTechnicalOpen?.[row.id]??(window.innerWidth>760)?'open':''} data-http-technical="${escapeText(row.id)}"><summary>Technical information<span aria-hidden="true">⌄</span></summary><section class="details-grid http-options"><div class="panel detail-option">${icon('clock')}<div><h3>Schedule</h3><p>${escapeText(scheduleLabel(row.schedule))}</p></div>${button('Edit','show-schedule')}</div><div class="panel detail-option">${icon('speed')}<div><h3>Speed limit</h3><p>${row.speedLimit?speed(row.speedLimit):'Unlimited'}</p></div>${button('Edit','show-limit')}</div></section><section class="details-grid"><div class="panel detail-option">${icon('folder')}<div><h3>Destination (on phone)</h3><p>${escapeText(row.technical?.savePath||'—')}</p></div></div><div class="panel"><h3>Technical information</h3><dl class="technical-list">${Object.entries({Host:row.technical?.source,Security:row.technical?.security,Resume:row.technical?.resume}).map(([label,value])=>`<div><dt>${escapeText(label)}</dt><dd>${escapeText(value||'—')}</dd></div>`).join('')}</dl></div><div class="panel"><h3>Request headers</h3><p class="muted">Unavailable</p></div><div class="panel"><h3>Segment breakdown</h3><p class="muted">Unavailable</p></div></section><div class="actions details-file-actions">${fileActions}</div></details>${row.error?`<section class="panel"><p class="error">${escapeText(row.error)}</p></section>`:''}`);
  if(state.verifyingChecksum===row.id){const control=$('[data-action="verify-checksum"]');control.disabled=true;control.textContent='Verifying…';}
}
function torrentChart(row) {
  const now=Date.now(),down=recentHistory(state.history.get(row.id)||[],now),up=recentHistory(state.uploadHistory.get(row.id)||[],now);
  const peak=Math.max(1000,...down.map(sample=>sample.value),...up.map(sample=>sample.value));
  return `<section class="panel torrent-chart"><div class="form-row"><h3>Speed history</h3><span class="chart-legend"><span>● Download</span><span>● Upload</span></span></div><div class="torrent-chart-plot"><div class="chart-axis" aria-hidden="true">${[peak,peak*2/3,peak/3,0].map((rate,index)=>`<span style="top:${(10+index*140/3)/160*100}%">${rate===0?'0':speed(rate)}</span>`).join('')}</div><svg class="chart" viewBox="0 0 1000 160" preserveAspectRatio="none" aria-label="Torrent download and upload speed over the last 60 seconds"><path class="grid" d="M0 10h1000M0 56.67h1000M0 103.33h1000M0 150h1000"/>${historyTrace(down,peak,now)}${historyTrace(up,peak,now,'upload-line')}</svg></div><div class="chart-times"><span>60 sec ago</span><span>Now</span></div></section>`;
}
function torrentDetailsFiles(row,details) {
  return `<section class="panel"><h3>Selected files (${details.files.filter(file=>file.selected).length} of ${details.files.length})</h3><div class="torrent-detail-files">${details.files.map(file=>{
    const downloaded=row.state==='COMPLETED'&&file.selected?file.size:row.fileProgress?.[file.index];
    const progress=downloaded==null?null:file.size===0?100:Math.min(100,downloaded/file.size*100);
    return `<div class="torrent-detail-file"><input type="checkbox" ${file.selected?'checked':''} disabled aria-label="${escapeText(file.path)} ${file.selected?'selected':'not selected'}"><span class="torrent-file-name">${icon('file')}${escapeText(file.path)}</span><span>${size(file.size)}</span><div class="progress"><span style="width:${progress??0}%"></span></div><small>${progress==null?'—':progress.toFixed(0)+'%'}</small></div>`;
  }).join('')}</div></section>`;
}
function renderTorrentDetails(row) {
  const details=state.torrentDetails.get(row.id);
  const action=pauseAction(row)?'detail-pause':'detail-resume';
  const metrics=[[liveSpeed(row.speed,row.id),'Download speed','arrow-down','green'],[liveSpeed(row.uploadRate,row.id,'uploadRate'),'Upload speed','arrow-up','gold'],[row.peers,'Peers','peers',''],[row.seeds,'Seeds','peers',''],[liveRemaining(row),'Estimated time','clock','']].map(([value,label,glyph,color])=>`<div class="panel torrent-metric">${icon(glyph,color)}<div><strong>${value}</strong><small>${label}</small></div></div>`).join('');
  const heading=`<section class="panel torrent-hero">${button('Back to downloads','back','back','plain')}<div class="torrent-title"><div><h2>${escapeText(row.name)}</h2><p class="${row.state==='DOWNLOADING'?'green':'muted'}"><span class="dot ${row.state==='FAILED'?'offline':row.state==='DOWNLOADING'||row.seeding?'':'neutral'}"></span>${row.checking?'Checking files':row.seeding?'Seeding':escapeText(row.state[0]+row.state.slice(1).toLowerCase())}</p></div><div class="actions">${row.state==='COMPLETED'?(row.seeding?button('Stop seeding','detail-pause','pause','danger'):''):button(transferLabel(row),action,pauseAction(row)?'pause':'play')}${row.state==='COMPLETED'?'':button('Cancel','detail-cancel','close','danger')}${button('Priority','detail-priority','priority')}${button('Copy magnet','copy-magnet','link')}${button('Open folder','open-download-folder','folder')}</div></div><div class="torrent-total"><strong>${percent(row).toFixed(0)}%</strong><div class="progress"><span style="width:${percent(row)}%"></span></div><span>${size(row.downloaded)} / ${size(row.total)}</span></div><section class="details-metrics torrent-metrics">${metrics}</section><nav class="tabs torrent-detail-tabs" aria-label="Torrent details">${['overview','files','peers'].map(tab=>`<button data-torrent-tab="${tab}" class="${state.torrentTab===tab?'active':''}">${tab[0].toUpperCase()+tab.slice(1)}</button>`).join('')}</nav></section>`;
  let content=details?.error?`<section class="panel"><p class="error">${escapeText(details.error)}</p>${button('Retry','reload-torrent-details')}</section>`:!details?'<section class="panel" role="status">Loading torrent information…</section>':'';
  if(details&&!details.error) {
    const files=torrentDetailsFiles(row,details);
    const trackers=(row.trackers?.length?row.trackers:details.trackers.map(url=>({url,verified:false}))).map(tracker=>`<li><span class="dot ${tracker.verified?'':'offline'}"></span><span>${escapeText(tracker.url)}</span><small>${tracker.verified?'Verified':'Not verified'}</small></li>`).join('');
    const info=`<section class="panel torrent-info"><h3>Torrent info</h3><dl class="technical-list">${Object.entries({Name:details.name,Hash:details.hash,'Total size':size(details.total),'Info files':details.files.length,Ratio:Number(row.ratio||0).toFixed(2),Uploaded:size(row.uploaded),Destination:row.technical?.savePath}).map(([label,value])=>`<div><dt>${label}</dt><dd ${label==='Hash'?`title="${escapeText(value)}"`: ''}>${escapeText(label==='Hash'?String(value).slice(0,8)+'…'+String(value).slice(-8):value)}</dd></div>`).join('')}</dl><h3>Trackers</h3>${trackers?`<ul class="torrent-trackers">${trackers}</ul>`:'<p class="muted">No trackers · peer discovery uses the torrent settings</p>'}<label class="switch-row"><span><strong>Seed after completion</strong><small>Keep sharing until paused</small></span><input id="torrent-detail-seed" type="checkbox" ${details.seed?'checked':''} ${row.state==='COMPLETED'||state.offline?'disabled':''}></label></section>`;
    content=state.torrentTab==='overview'?`<div class="torrent-overview"><div>${torrentChart(row)}${files}</div>${info}</div>`:state.torrentTab==='files'?files:`<section class="panel"><h3>Peers (${row.peers}) · Seeds (${row.seeds})</h3>${row.peerDetails?.length?`<div class="table-wrap"><table><thead><tr><th>Address</th><th>Client</th><th>Download</th><th>Upload</th><th>Progress</th></tr></thead><tbody>${row.peerDetails.map(peer=>`<tr><td>${escapeText(peer.address)}</td><td>${escapeText(peer.client)}</td><td>${speed(peer.downloadRate)}</td><td>${speed(peer.uploadRate)}</td><td>${Math.min(100,Math.max(0,peer.progress*100)).toFixed(0)}%</td></tr>`).join('')}</tbody></table></div>`:'<p class="muted">No connected peers</p>'}</section>`;
  }
  $('#app').innerHTML=shell('Torrent details',heading+content+`<section class="details-grid"><div class="panel"><h3>Speed limit</h3><p>${row.speedLimit?speed(row.speedLimit):'Unlimited'}</p>${button('Change','show-limit')}</div><div class="panel"><h3>Schedule</h3><p>${escapeText(scheduleLabel(row.schedule))}</p>${button('Change','show-schedule')}</div></section>${row.error?`<section class="panel"><p class="error">${escapeText(row.error)}</p></section>`:''}`);
}
async function loadTorrentDetails(id,force=false) {
  if(!force&&state.torrentDetails.has(id))return;
  try {const details=await api('torrent/details/'+encodeURIComponent(id));if(authorized())state.torrentDetails.set(id,details);}
  catch(error){if(authorized())state.torrentDetails.set(id,{error:error.message});}
  if(state.detail===id&&!$('#modal').open)render();
}
function openDetails(id) {state.detail=id;state.torrentTab='overview';render();window.scrollTo(0,0);if(state.snapshot.downloads.find(row=>row.id===id)?.torrent)loadTorrentDetails(id);}
function fileKey(file) {return file.id+':'+file.index;}
function visibleFiles() {
  const files=state.files||[];
  const folders=new Map();
  for(const file of files.filter(file=>file.torrent)) {
    if(!folders.has(file.id))folders.set(file.id,{id:file.id,index:'folder',name:file.folder,folderEntry:true,size:0,available:false,completedAt:file.completedAt,modifiedAt:null,mime:'Folder'});
    const folder=folders.get(file.id);folder.size+=file.size;folder.available||=file.available;
    if(file.modifiedAt>0)folder.modifiedAt=Math.max(folder.modifiedAt||0,file.modifiedAt);
  }
  const items=state.fileFolder?files.filter(file=>file.id===state.fileFolder):[...folders.values(),...files.filter(file=>!file.torrent)];
  return items.filter(file=>(state.fileFilter==='All'||file.category===state.fileFilter)&&file.name.toLowerCase().includes(state.query.toLowerCase())).sort((a,b)=>Number(Boolean(b.folderEntry))-Number(Boolean(a.folderEntry))||(state.fileSort==='newest'?(b.modifiedAt??b.completedAt??0)-(a.modifiedAt??a.completedAt??0):state.fileSort==='oldest'?(a.modifiedAt??a.completedAt??0)-(b.modifiedAt??b.completedAt??0):state.fileSort==='size'?b.size-a.size:a.name.localeCompare(b.name))||fileKey(a).localeCompare(fileKey(b)));
}
function selectedFiles() {return visibleFiles().filter(file=>state.fileSelected.has(fileKey(file)));}
function fileActions(file,compact=false) {
  const key=escapeText(fileKey(file));
  const label=(text,symbol)=>`${icon(symbol)}${compact?'':escapeText(text)}`;
  const accessible=text=>`aria-label="${escapeText(text)}" title="${escapeText(text)}"`;
  if(file.folderEntry)return `<button data-file-folder="${escapeText(file.id)}" ${accessible('Open folder')}>${label('Open folder','folder')}</button><button data-file-move="${key}" ${accessible('Move folder')} ${file.available?'':'disabled'}>${label('Move folder','move')}</button><button class="danger" data-file-delete="${key}" ${accessible('Delete folder')}>${label('Delete folder','trash')}</button>`;
  const url='/api/files/'+encodeURIComponent(file.id)+'/'+encodeURIComponent(file.index);
  return `${!compact&&file.available?`<button data-file-phone="${key}" data-phone-action="open" ${accessible('Open on phone')}>${label('Open on phone','phone')}</button><button data-file-phone="${key}" data-phone-action="share" ${accessible('Share on phone')}>${label('Share on phone','link')}</button>`:''}${file.preview&&file.available?`<button data-file-preview="${key}" ${accessible('Preview')}>${label('Preview','preview')}</button>`:''}${file.available?`<a class="file-action" href="${url}/download" ${accessible('Download to computer')}>${label('Download to computer','download')}</a>`:''}${file.renameAllowed?`<button data-file-rename="${key}" ${accessible('Rename')} ${file.available?'':'disabled'}>${label('Rename','rename')}</button><button data-file-move="${key}" ${accessible('Move')}>${label('Move','move')}</button><button class="danger" data-file-delete="${key}" ${accessible('Delete')}>${label('Delete','trash')}</button>`:''}`;
}
function fileTypeLabel(file) {
  if(file.folderEntry)return 'Folder';
  const extension=file.name.split('/').pop().match(/\.([a-z0-9]{1,10})$/i)?.[1];
  if(extension)return extension.toLowerCase()==='txt'?'Text':extension.toUpperCase();
  return file.category&&file.category!=='Other'?file.category:'File';
}
function fileDateLabel(file) {
  if(!file.available)return 'Unavailable';
  if(!(file.modifiedAt>0))return '—';
  const date=new Date(file.modifiedAt);
  if(!Number.isFinite(date.getTime()))return '—';
  return new Intl.DateTimeFormat(undefined,{month:'short',day:'numeric',year:'numeric',hour:'2-digit',minute:'2-digit',hour12:false}).format(date);
}
function renderFiles() {
  document.querySelectorAll('.file-menu').forEach(menu=>menu.remove());
  const files=visibleFiles();
  const capacity=state.storage;
  const storage=capacity?`<div class="panel storage-card">${icon('storage')}<div><div class="form-row"><strong>Phone storage</strong><span>${capacity.available==null?'Available space unknown':size(capacity.available)+' available'}</span></div><div class="progress"><span style="width:${capacity.total&&capacity.used!=null?Math.min(100,capacity.used/capacity.total*100):0}%"></span></div><small>${capacity.used==null||capacity.total==null?'Storage figures are unavailable':size(capacity.used)+' used of '+size(capacity.total)}</small></div></div>`:'';
  const columns=files.map(file=>{
    const key=escapeText(fileKey(file));
    return `<tr class="${state.fileSelected.has(fileKey(file))?'selected':''}"><td><input type="checkbox" data-file-select="${key}" aria-label="Select ${escapeText(file.name)}" ${state.fileSelected.has(fileKey(file))?'checked':''}></td><td><div class="name-cell ${file.folderEntry?'folder-name':''}">${icon(file.folderEntry?'folder':'file')}${file.folderEntry?`<button class="plain" data-file-folder="${escapeText(file.id)}">${escapeText(file.name)}</button>`:`<span>${escapeText(file.name)}</span>`}</div></td><td>${escapeText(fileTypeLabel(file))}</td><td>${size(file.size)}</td><td>${escapeText(fileDateLabel(file))}</td><td><button class="plain" data-file-menu="${key}" aria-label="Actions for ${escapeText(file.name)}" aria-expanded="${state.fileMenu===fileKey(file)}">${icon('more')}</button></td></tr>`;
  }).join('');
  const selected=selectedFiles();
  const selection=selected.length?`<div class="bulk file-bulk"><span>${selected.length} selected (${size(selected.reduce((sum,file)=>sum+file.size,0))})</span>${selected.length===1?fileActions(selected[0])+(selected[0].folderEntry?'':`<button class="file-bulk-more" data-file-menu="${escapeText(fileKey(selected[0]))}" aria-label="More file actions" title="More file actions" aria-expanded="${state.fileMenu===fileKey(selected[0])}">${icon('more')}</button>`):selected.every(file=>file.renameAllowed||file.folderEntry)?button('Move','move-selected-files','folder')+button('Delete','delete-selected-files','close','danger'):''}</div>`:'';
  const folderName=state.files?.find(file=>file.id===state.fileFolder)?.folder;
  const toolbar=`<section class="panel file-toolbar"><div class="file-breadcrumb">${button('Phone','files-root','home','plain')}<span>›</span><span>${escapeText(state.fileLocation)}</span>${folderName?`<span>›</span><strong>${escapeText(folderName)}</strong>`:''}</div><div class="actions"><button class="${state.fileView==='list'?'active':''}" data-file-view="list" aria-label="List view">${icon('list')}</button><button class="${state.fileView==='grid'?'active':''}" data-file-view="grid" aria-label="Grid view">${icon('grid')}</button><label>Type <select id="file-filter">${['All','Video','Audio','Documents','Apk','Archives'].map(value=>`<option value="${value}" ${state.fileFilter===value?'selected':''}>${value==='Apk'?'APK':value}</option>`).join('')}</select></label><label>Sort <select id="file-sort"><option value="name" ${state.fileSort==='name'?'selected':''}>Name (A–Z)</option><option value="newest" ${state.fileSort==='newest'?'selected':''}>Newest first</option><option value="oldest" ${state.fileSort==='oldest'?'selected':''}>Oldest first</option><option value="size" ${state.fileSort==='size'?'selected':''}>Size</option></select></label></div></section>`;
  const listing=state.fileView==='grid'?`<div class="file-grid">${files.map(file=>`<section class="panel file-tile"><label><input type="checkbox" data-file-select="${escapeText(fileKey(file))}" aria-label="Select ${escapeText(file.name)}" ${state.fileSelected.has(fileKey(file))?'checked':''}>${icon(file.folderEntry?'folder':'file')}</label><strong>${escapeText(file.name)}</strong><small>${size(file.size)}</small><div class="actions file-icon-actions">${fileActions(file,true)}</div></section>`).join('')}</div>`:`<div class="table-wrap files-table"><table><colgroup><col class="file-select-column"><col class="file-name-column"><col class="file-type-column"><col class="file-size-column"><col class="file-date-column"><col class="file-actions-column"></colgroup><thead><tr><th><input type="checkbox" id="file-select-all" aria-label="Select all visible files" ${files.length&&files.every(file=>state.fileSelected.has(fileKey(file)))?'checked':''}></th><th>Name</th><th>Type</th><th>Size</th><th>Date modified</th><th>Actions</th></tr></thead><tbody>${columns}</tbody></table></div>`;
  $('#app').innerHTML=shell('Files',`<div class="files-heading"><p class="muted">Browse and manage files on your phone.</p>${storage}</div>${toolbar}${files.length?listing:`<div class="panel empty">${icon('folder')}<h2>${state.files===null?'Loading phone files…':state.query||state.fileFilter!=='All'?'No matching files':'No completed files yet'}</h2><p>Completed downloads stay on your phone.</p></div>`}${selection}`);
  mountFileMenu(files);
}
function fileMenuPlacement(anchor,width,height,viewportWidth,viewportHeight) {
  const margin=12,gap=8;
  const below=viewportHeight-anchor.bottom-margin-gap;
  const top=height<=below?anchor.bottom+gap:Math.max(margin,anchor.top-gap-height);
  return {left:Math.max(margin,Math.min(anchor.right-width,viewportWidth-width-margin)),top:Math.min(top,Math.max(margin,viewportHeight-height-margin))};
}
function closeFileMenu(restoreFocus=false) {
  const key=state.fileMenu;if(!key)return;state.fileMenu=null;
  document.querySelectorAll('.file-menu').forEach(menu=>menu.remove());
  document.querySelectorAll('[data-file-menu]').forEach(trigger=>{trigger.setAttribute('aria-expanded','false');if(restoreFocus&&trigger.dataset.fileMenu===key&&trigger.classList.contains('file-bulk-more')===Boolean(state.fileMenuFromBulk))trigger.focus({preventScroll:true});});
}
function mountFileMenu(files) {
  const file=files.find(file=>fileKey(file)===state.fileMenu);
  if(!file){state.fileMenu=null;return;}
  const trigger=[...document.querySelectorAll('[data-file-menu]')].find(control=>control.dataset.fileMenu===state.fileMenu&&control.classList.contains('file-bulk-more')===Boolean(state.fileMenuFromBulk));
  if(!trigger)return;
  const menu=document.createElement('div');menu.className='file-menu';menu.setAttribute('popover','auto');menu.setAttribute('role','group');menu.setAttribute('aria-label',`Actions for ${file.name}`);menu.innerHTML=fileActions(file);menu.style.visibility='hidden';document.body.append(menu);
  if(typeof menu.showPopover==='function')menu.showPopover();
  else menu.removeAttribute('popover');
  const bounds=menu.getBoundingClientRect(),position=fileMenuPlacement(trigger.getBoundingClientRect(),bounds.width,bounds.height,window.innerWidth,window.innerHeight);
  menu.style.left=position.left+'px';menu.style.top=position.top+'px';menu.style.visibility='';
  menu.addEventListener('toggle',event=>{if(event.newState==='closed'&&menu.isConnected)closeFileMenu();});
  menu.querySelector('button,a')?.focus({preventScroll:true});
}
document.defaultView?.addEventListener('resize',()=>closeFileMenu());
document.addEventListener('scroll',event=>{if(state.fileMenu&&!event.target.closest?.('.file-menu'))closeFileMenu();},{capture:true});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&state.fileMenu){event.preventDefault();closeFileMenu(true);}});
function findFile(key) {return visibleFiles().find(file=>fileKey(file)===key);}
async function showFileMove(files) {
  if(!files.length||files.some(file=>!file.renameAllowed&&!file.folderEntry))throw new Error('Select completed files or torrent folders to move');
  const picker={files,listing:null,loading:false,error:'',selected:null};state.movePicker=picker;
  renderMovePicker();await loadMoveFolders(null,picker);
}
function moveFolderTree(picker) {
  const cache=picker.folderListings;
  const roots=cache?.get('phone');
  if(!roots)return '<p role="status">Loading phone folders…</p>';
  const branch=(folder,depth,ancestors)=>{
    if(ancestors.has(folder.id))return '';
    const expanded=picker.expandedFolders?.has(folder.id);
    const children=cache.get(folder.id);
    const next=new Set(ancestors);next.add(folder.id);
    return `<div class="folder-tree-row ${picker.selected===folder.id?'selected':''}" style="--depth:${depth}"><button class="plain folder-expander" data-move-${expanded?'collapse':'folder'}="${escapeText(folder.id)}" aria-label="${expanded?'Collapse':'Expand'} ${escapeText(folder.name)}" aria-expanded="${Boolean(expanded)}">${icon(expanded?'chevron-down':'chevron-right')}</button><button class="plain folder-select" data-move-select="${escapeText(folder.id)}" aria-pressed="${picker.selected===folder.id}">${icon('folder')}${escapeText(folder.name)}</button></div>${expanded&&children?children.folders.map(child=>branch(child,depth+1,next)).join(''):''}`;
  };
  return `<div class="folder-tree-root">${icon('phone')}Phone</div>${roots.folders.map(folder=>branch(folder,1,new Set())).join('')}`;
}
function renderMovePicker() {
  const picker=state.movePicker;if(!picker)return;
  const listing=picker.listing;
  showModal(picker.purpose==='storage'?'Choose download folder':'Move on phone',`<p>${picker.purpose==='storage'?'Save new downloads to:':`Move ${picker.files.length} ${picker.files.length===1?(picker.files[0].folderEntry?'folder':'file'):'items'} to:`}</p><div class="folder-picker folder-tree">${moveFolderTree(picker)}</div>${picker.selected&&picker.creating?`<div class="folder-create"><label for="new-folder-name">New folder</label><div class="actions"><input id="new-folder-name" placeholder="Folder name" maxlength="120">${button('Create','move-create-folder','plus')}</div></div>`:''}<p id="move-error" class="error" role="alert">${escapeText(picker.error)}</p>`,button('New folder','move-new-folder','plus')+(picker.purpose==='storage'?button('Choose on phone','storage-request-folder','phone'):'')+button(picker.purpose==='storage'?'Use folder':'Move','confirm-file-move',null,'primary'));
  $('#modal').classList.add('folder-move-dialog');
  $('[data-action="move-new-folder"]').disabled=!picker.selected||picker.loading;
  $('[data-action="confirm-file-move"]').disabled=!picker.selected||picker.loading;
  $('#modal').querySelectorAll('.folder-picker button,.folder-picker input,.folder-create button,.folder-create input').forEach(control=>control.disabled=picker.loading);
  $('.folder-picker').setAttribute('aria-busy',String(picker.loading));
}
async function loadMoveFolders(id,picker=state.movePicker) {
  if(!picker)return;
  const request=picker.request=(picker.request||0)+1;
  picker.loading=true;picker.selected=null;picker.creating=false;picker.error='';renderMovePicker();
  try{const listing=await api('folders'+(id?'/'+encodeURIComponent(id):''));if(state.movePicker!==picker||picker.request!==request)return;picker.listing=listing;picker.selected=listing.current;picker.folderListings??=new Map();picker.expandedFolders??=new Set();picker.folderListings.set(id||'phone',listing);if(id)picker.expandedFolders.add(id);}
  catch(error){if(state.movePicker!==picker||picker.request!==request)return;picker.error=error.message;}
  finally{if(state.movePicker===picker&&picker.request===request){picker.loading=false;renderMovePicker();}}
}
function showFileRename(file) {
  if(!file?.renameAllowed)throw new Error('This file cannot be renamed');
  state.fileRename=file;
  showModal('Rename file',`<p class="muted">${escapeText(file.name)}</p><label for="file-new-name">New name</label><input id="file-new-name" value="${escapeText(file.name)}" maxlength="240"><p id="file-operation-error" class="error" role="alert"></p>`,button('Rename','confirm-file-rename',null,'primary'));
}
function showFileDelete(files) {
  if(!files.length)return;
  if(files.some(file=>!file.renameAllowed&&!file.folderEntry))throw new Error('Select the torrent folder to delete its downloaded files');
  state.fileDelete=files;
  showModal('Delete files from phone?',`<p class="error">This permanently deletes the selected files from your phone.</p><p>${files.length} selected</p><div class="file-delete-list">${files.map(file=>`<p>${icon(file.folderEntry?'folder':'file')}${escapeText(file.name)} <span class="muted">${size(file.size)}</span></p>`).join('')}</div><p class="muted">${files.some(file=>file.folderEntry)?'Deleting a torrent folder removes every downloaded file in that torrent. ':''}Removing a download from the queue is a separate action.</p><p id="file-operation-error" class="error" role="alert"></p>`,button('Delete files','confirm-file-delete',null,'danger'));
}
function showFilePreview(file) {
  if(!file?.preview||!file.available)throw new Error('Preview is unavailable');
  const url='/api/files/'+encodeURIComponent(file.id)+'/'+encodeURIComponent(file.index);
  const source=url+'/preview';
  const media=file.mime==='application/pdf'?`<iframe src="${source}" title="${escapeText(file.name)}"></iframe>`:file.mime.startsWith('image/')?`<img src="${source}" alt="${escapeText(file.name)}">`:file.mime.startsWith('audio/')?`<audio src="${source}" controls></audio>`:`<video src="${source}" controls></video>`;
  showModal(escapeText(file.name),`<div class="file-preview">${media}</div><p class="preview-status muted" role="status">Loading preview…</p>`,`<a class="file-action primary" href="${url}/download">${icon('download')}Download to computer</a>`);
  const modal=$('#modal'),preview=modal.querySelector('.file-preview'),element=preview.firstElementChild,status=modal.querySelector('.preview-status');
  modal.classList.add('preview-dialog');
  const ready=()=>{if(status.isConnected){status.hidden=true;}};
  const failed=()=>{if(status.isConnected){preview.replaceChildren();status.hidden=false;status.className='preview-status error';status.textContent='This file cannot be previewed in this browser. Download it to your computer to open it.';}};
  element.addEventListener(file.mime.startsWith('audio/')||file.mime.startsWith('video/')?'loadedmetadata':'load',ready,{once:true});
  element.addEventListener('error',failed,{once:true});
  modal.addEventListener('close',()=>{if(typeof element.pause==='function')element.pause();element.removeAttribute('src');if(typeof element.load==='function')element.load();preview.replaceChildren();},{once:true});
}
function scheduleLabel(schedule) {
  if(!schedule)return 'Any time';
  if(schedule.kind==='DAILY')return `Daily ${minuteLabel(schedule.startMinute)}–${minuteLabel(schedule.endMinute)}`;
  return `Starts ${new Date(schedule.start).toLocaleString(undefined,{timeZone:state.snapshot.zone})}`;
}
function timeControl(attributes,value,label) {
  const [hour,minute]=value.split(':').map(Number);
  return `<span class="time-control"><input type="hidden" ${attributes} value="${value}"><button type="button" data-time-open aria-expanded="false" aria-label="${escapeText(label)}">${String(hour%12||12).padStart(2,'0')}:${String(minute).padStart(2,'0')} ${hour>=12?'PM':'AM'}${icon('clock')}</button></span>`;
}
function openTimeControl(wrapper) {
  document.querySelectorAll('.time-picker').forEach(p=>{p.parentElement.querySelector('[data-time-open]').setAttribute('aria-expanded','false');p.remove();});
  const input=wrapper.querySelector('input');const [hour,minute]=input.value.split(':').map(Number);
  const picker=document.createElement('span');picker.className='time-picker';picker.setAttribute('role','group');picker.setAttribute('aria-label','Choose time');
  const column=(part,values,selected)=>`<span class="time-column" role="group" aria-label="${part}">${values.map(v=>`<button type="button" data-time-part="${part}" data-time-value="${v}" aria-pressed="${v===selected}">${typeof v==='number'?String(v).padStart(2,'0'):v}</button>`).join('')}</span>`;
  picker.innerHTML=`<span class="time-columns">${column('Hour',Array.from({length:12},(_,i)=>i+1),hour%12||12)}${column('Minute',Array.from({length:60},(_,i)=>i),minute)}${column('Period',['AM','PM'],hour>=12?'PM':'AM')}</span><button type="button" class="time-done primary" data-time-done>Done</button>`;
  wrapper.append(picker);
  picker.querySelectorAll('[aria-pressed="true"]').forEach(b=>b.parentElement.scrollTop=b.offsetTop-b.parentElement.offsetTop);
  const bounds=picker.getBoundingClientRect();
  const container=wrapper.closest('.dialog-body')?.getBoundingClientRect();
  const left=Math.max(12,container?.left??12),right=Math.min(innerWidth-12,container?.right??innerWidth-12);
  if(bounds.width>right-left)picker.style.width=`${right-left}px`;
  const width=Math.min(bounds.width,right-left);
  const positionedLeft=Math.max(left,Math.min(bounds.left,right-width));
  picker.style.right='auto';picker.style.left=`${positionedLeft-wrapper.getBoundingClientRect().left}px`;
  if(bounds.bottom>innerHeight-12&&wrapper.getBoundingClientRect().top>bounds.height+12)picker.classList.add('time-picker-above');
  wrapper.querySelector('[data-time-open]').setAttribute('aria-expanded','true');
  picker.querySelector('[aria-pressed="true"]').focus({preventScroll:true});
}
function minuteLabel(minute){return `${String(Math.floor(minute/60)).padStart(2,'0')}:${String(minute%60).padStart(2,'0')}`;}
function phoneDateParts(epoch) {
  const parts=new Intl.DateTimeFormat('en-CA',{timeZone:state.snapshot.zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(new Date(epoch));
  const value=Object.fromEntries(parts.map(part=>[part.type,part.value]));
  return {date:`${value.year}-${value.month}-${value.day}`,hour:value.hour,minute:value.minute};
}
function showSchedule(row) {
  const schedule=row.schedule;
  const time=phoneDateParts(schedule?.start||Date.now()+3600000);
  const kind=schedule?.kind||'NONE';
  showModal('Schedule download',`<p class="muted">${escapeText(row.name)}</p><div class="schedule-options"><label><input type="radio" name="schedule-kind" value="NONE" ${kind==='NONE'?'checked':''}>Any time<small>Use the normal download queue</small></label><label><input type="radio" name="schedule-kind" value="ONCE" ${kind==='ONCE'?'checked':''}>Scheduled<small>Start at a specific date and time</small></label><label><input type="radio" name="schedule-kind" value="DAILY" ${kind==='DAILY'?'checked':''}>Daily window<small>Resume and pause each day</small></label></div><div class="schedule-once" ${kind!=='ONCE'?'hidden':''}><div class="date-fields"><label>Date<input id="schedule-date" type="date" value="${time.date}"></label><label>Hour<input id="schedule-hour" type="number" min="0" max="23" value="${time.hour}"></label><label>Minute<input id="schedule-minute" type="number" min="0" max="59" value="${time.minute}"></label></div><label class="switch-row"><span>Stop at a set time (optional)</span><input type="checkbox" id="schedule-has-end" ${schedule?.end?'checked':''}></label><input id="schedule-end" type="datetime-local" value="${schedule?.end?(()=>{const p=phoneDateParts(schedule.end);return `${p.date}T${p.hour}:${p.minute}`;})():''}" ${!schedule?.end?'hidden':''}></div><div class="schedule-daily" ${kind!=='DAILY'?'hidden':''}><div class="date-fields daily"><label>Resume at${timeControl('id="daily-start"',minuteLabel(schedule?.startMinute??60),'Resume at')}</label><label>Pause at${timeControl('id="daily-end"',minuteLabel(schedule?.endMinute??420),'Pause at')}</label></div></div><div id="schedule-summary" class="schedule-summary" role="status" aria-live="polite"></div><p class="muted schedule-zone">Phone time zone: ${escapeText(state.snapshot.zone)}<br>${state.snapshot.settings.wifiOnly?'Downloads only run while your phone is connected to Wi-Fi.':'The phone network policy applies.'}</p><p id="schedule-error" class="error" role="alert"></p>`,button('Apply','apply-schedule',null,'primary'));
  $('#modal').classList.add('schedule-dialog');
  updateScheduleSummary();
}
function updateScheduleSummary() {
  const output=$('#schedule-summary');if(!output)return;
  const kind=document.querySelector('[name="schedule-kind"]:checked')?.value;
  let title='Use the normal download queue';
  if(kind==='ONCE') {
    const date=$('#schedule-date').value,hour=$('#schedule-hour').value,minute=$('#schedule-minute').value;
    if(!/^\d{4}-\d{2}-\d{2}$/.test(date)||hour.trim()===''||minute.trim()===''||!Number.isInteger(Number(hour))||!Number.isInteger(Number(minute))||Number(hour)<0||Number(hour)>23||Number(minute)<0||Number(minute)>59)title='Choose a valid date and time';
    else title=`Scheduled for ${date} at ${String(Number(hour)).padStart(2,'0')}:${String(Number(minute)).padStart(2,'0')}`;
  } else if(kind==='DAILY') title=`Daily window · ${$('#daily-start').value}–${$('#daily-end').value}`;
  const policy=state.snapshot.settings.wifiOnly?'Wi-Fi only':'Phone network policy applies';
  output.innerHTML=`${icon('clock')}<div><strong>${escapeText(title)}</strong><small>On ${escapeText(state.snapshot.device)} · ${escapeText(policy)}</small></div>`;
}
function showBulkLinkRefresh() {
  const rows=state.snapshot.downloads.filter(row=>state.selected.has(row.id));
  if(!rows.length||rows.some(row=>row.torrent||row.state==='COMPLETED'))return toast('Select incomplete HTTP downloads to refresh their links');
  state.bulkLink={ids:rows.map(row=>row.id),targets:new Map(),restarts:new Set(),review:null};
  showModal('Refresh selected links',`<p class="muted">${rows.length} downloads selected. Active downloads pause while checking.</p><label for="bulk-link-urls">New download links (one per line)</label><textarea id="bulk-link-urls" placeholder="https://example.com/file.zip"></textarea><div id="bulk-link-review" role="status"></div><p id="bulk-link-error" class="error" role="alert"></p>`,button('Check links','check-bulk-links','link','primary'));
}
function bulkLinkMappings() {
  const bulk=state.bulkLink;if(!bulk?.review)return [];
  const mappings=bulk.review.candidates.filter(row=>bulk.targets.get(row.index)).map(row=>({index:row.index,id:bulk.targets.get(row.index),restartConfirmed:bulk.restarts.has(row.index)}));
  if(new Set(mappings.map(row=>row.id)).size!==mappings.length)throw new Error('Match each download only once');
  for(const row of mappings){const candidate=bulk.review.candidates.find(item=>item.index===row.index);if(!candidate.preserve[row.id]&&!row.restartConfirmed)throw new Error('Confirm restart from zero for each changed file');}
  return mappings;
}
function setBulkLinkBusy(busy) {
  state.linkWorking=busy;
  for(const control of document.querySelectorAll('#bulk-link-urls, [data-bulk-link-target], [data-bulk-link-restart], [data-action="check-bulk-links"], [data-action="apply-bulk-links"]'))control.disabled=busy;
  if(!busy)renderBulkLinkReview();
}
function renderBulkLinkReview() {
  const bulk=state.bulkLink,output=$('#bulk-link-review');if(!bulk?.review||!output)return;
  const focused=document.activeElement?.dataset.bulkLinkTarget;
  const focusedRestart=document.activeElement?.dataset.bulkLinkRestart;
  output.innerHTML=bulk.review.candidates.map(candidate=>{
    const target=bulk.targets.get(candidate.index),download=bulk.review.downloads.find(row=>row.id===target),preserve=target&&candidate.preserve[target];
    return `<section class="panel bulk-link-candidate"><strong>${escapeText(candidate.name)}</strong><p class="muted">${size(candidate.size)}</p><label for="bulk-target-${candidate.index}">Matching download</label><select id="bulk-target-${candidate.index}" data-bulk-link-target="${candidate.index}"><option value="">Skip this link</option>${bulk.review.downloads.map(row=>`<option value="${escapeText(row.id)}" ${row.id===target?'selected':''}>${escapeText(row.name)}</option>`).join('')}</select>${target?`<p class="${preserve?'green':'gold'}">${preserve?'Same file · Progress will be preserved':'Cannot safely reuse downloaded bytes'} · ${size(download?.downloaded)} downloaded</p>${preserve?'':`<label class="switch-row"><span>Delete partial data and restart from zero</span><input type="checkbox" data-bulk-link-restart="${candidate.index}" ${bulk.restarts.has(candidate.index)?'checked':''}></label>`}`:''}</section>`;
  }).join('');
  let enabled=false,message=bulk.resultMessage||'';try{enabled=bulkLinkMappings().length>0;}catch(error){message=error.message;}$('#bulk-link-error').textContent=message;
  const apply=$('[data-action="apply-bulk-links"]');if(apply)apply.disabled=state.linkWorking||!enabled;
  for(const control of output.querySelectorAll('select,input'))control.disabled=state.linkWorking;
  if(focused!==undefined)$(`[data-bulk-link-target="${focused}"]`)?.focus();
  if(focusedRestart!==undefined)$(`[data-bulk-link-restart="${focusedRestart}"]`)?.focus();
}
function invalidateBulkLinkReview() {
  const bulk=state.bulkLink;if(!bulk||state.linkWorking)return;
  const token=bulk.review?.review;bulk.review=null;bulk.targets.clear();bulk.restarts.clear();bulk.resultMessage='';bulk.applySignature=null;
  $('#bulk-link-review').textContent='';$('#bulk-link-error').textContent='';
  $('[data-action="apply-bulk-links"]')?.remove();
  if(token&&authorized())api('download/link/bulk/cancel',{review:token}).catch(error=>{if(!state.offline)toast(error.message);});
}
async function cancelBulkLinkReview() {
  const bulk=state.bulkLink;state.bulkLink=null;
  if(bulk?.review&&authorized())try{await api('download/link/bulk/cancel',{review:bulk.review.review});}catch(error){if(!state.offline)toast(error.message);}
}
function invalidateSingleLinkReview() {
  if(state.linkWorking)return;
  const token=state.linkReview?.review;state.linkReview=null;
  const review=$('#link-review'),error=$('#link-error');if(review)review.textContent='';if(error)error.textContent='';
  $('[data-action="apply-link"]')?.remove();
  if(token&&authorized())api('download/link/cancel',{review:token}).catch(error=>{if(!state.offline)toast(error.message);});
}
function setSingleLinkBusy(busy) {
  state.linkWorking=busy;
  for(const input of document.querySelectorAll('#refresh-url, #link-restart, [data-action="check-link"], [data-action="apply-link"]'))input.disabled=busy;
  const apply=$('[data-action="apply-link"]');if(apply)apply.disabled=busy||!(state.linkReview?.preserve||$('#link-restart')?.checked);
}
function showLinkRefresh(row) {
  state.linkReview=null;state.linkDownload=row.id;
  showModal('Refresh download link',`<p class="muted">Enter a new download link for this file. Keep downloaded progress when the new file matches the existing one.</p><label>Current file</label><div class="link-current-file">${icon('file')}<div><strong>${escapeText(row.name)}</strong><small>${size(row.total)} (${size(row.downloaded)} downloaded, ${percent(row).toFixed(0)}%)</small></div></div><label for="refresh-url">New download link</label><div class="link-input-row"><input id="refresh-url" type="text" placeholder="https://example.com/file.zip">${button('Check link','check-link')}</div><p class="link-pause-note muted">Checking pauses this download. Verified progress is kept when the new link identifies the same file.</p><div id="link-review" role="status"></div><p id="link-error" class="error" role="alert"></p>`);
  $('#modal').classList.add('link-refresh-dialog');
}

async function cancelLinkReview() {
  const review=state.linkReview;state.linkReview=null;state.linkDownload=null;
  if(review&&authorized())try{await api('download/link/cancel',{review:review.review});}catch(error){if(!state.offline)toast(error.message);}
}
function updateLimitPresets() {
  const unlimited=$('#limit-unlimited');
  if(!unlimited)return;
  const bytes=Number($('#limit-value').value)*($('#limit-unit').value==='MB/s'?1000000:1000);
  for(const [action,value] of [['limit-100',100000],['limit-300',300000],['limit-1mb',1000000]]) {
    const control=$(`[data-action="${action}"]`);
    const selected=!unlimited.checked&&bytes===value;
    control.classList.toggle('selected',selected);
    control.setAttribute('aria-pressed',String(selected));
  }
}
function showLimit(row) {
  const unlimited=row.speedLimit==null;
  const unit=row.speedLimit>=1000000&&row.speedLimit%1000000===0?'MB/s':'KB/s';
  const amount=unlimited?'300':String(row.speedLimit/(unit==='MB/s'?1000000:1000));
  showModal('Individual speed limit',`<p class="muted">Set a download speed limit for this item.</p><section class="panel limit-unlimited"><label class="switch-row"><span><strong>Unlimited</strong><small>No per-download limit (uses global limit)</small></span><input type="checkbox" id="limit-unlimited" ${unlimited?'checked':''}></label></section><label for="limit-value">Limit to</label><div class="limit-inputs"><input id="limit-value" type="number" min="0.001" step="any" value="${amount}" ${unlimited?'disabled':''}><select id="limit-unit" aria-label="Speed limit unit" ${unlimited?'disabled':''}><option ${unit==='KB/s'?'selected':''}>KB/s</option><option ${unit==='MB/s'?'selected':''}>MB/s</option></select></div><label>Presets</label><div class="presets">${button('100 KB/s','limit-100')}${button('300 KB/s','limit-300')}${button('1 MB/s','limit-1mb')}</div><p class="muted schedule-zone">This limit only applies to this download.</p><p id="limit-error" class="error" role="alert"></p>`,button('Apply','apply-limit',null,'primary'));
  updateLimitPresets();
}
function showRemoval(ids) {
  const records=state.snapshot.downloads.filter(row=>ids.includes(row.id));
  if(!records.length)return toast('Downloads no longer exist');
  state.pendingRemoval=records.map(row=>row.id);
  const incomplete=records.filter(row=>row.state!=='COMPLETED').length;
  const completed=records.length-incomplete;
  const message=incomplete?`${incomplete} incomplete download${incomplete===1?'':'s'} and their partial files will be removed. They cannot be resumed afterward.`:'Choose whether to keep the downloaded files in Files.';
  showModal(completed&& !incomplete?'Remove from Completed?':'Delete selected downloads?',`<p>${escapeText(message)}</p>${completed?`<label class="switch-row"><span><strong>Keep downloaded files</strong><small>${completed} completed file${completed===1?'':'s'} will remain in Files</small></span><input id="remove-keep-files" type="checkbox" checked></label>`:''}<p id="remove-error" class="error" role="alert"></p>`,button(completed?'Remove only':'Delete','confirm-remove',null,completed?'primary':'danger'),'Keep');
}
function addTabs(active) {return `<nav class="tabs add-tabs">${[['links','Links'],['magnet','Magnet'],['torrent-file','Torrent file']].map(([mode,label])=>`<button data-action="add-${mode}" class="${active===mode?'active':''}">${label}</button>`).join('')}</nav>`;}
function addOptions(httpOptions=false) {return `<div class="add-destination"><span class="muted">Destination folder on phone</span><div class="actions"><strong id="destination-name">${escapeText(state.addDestination?.name||state.snapshot.saveLocation||'Selected save folder')}</strong>${button('Choose folder','choose-destination','folder')}${button('Use default','default-destination')}</div><div id="destination-folders" hidden></div><p id="destination-error" class="error" role="alert"></p></div><div class="add-start"><label><input type="radio" name="add-start" value="queue">Queue (download later)</label><label><input type="radio" name="add-start" value="now" checked>Start now</label></div>${httpOptions?`<div class="add-submit-options"><label for="add-schedule-kind">Schedule (optional)<select id="add-schedule-kind"><option value="NONE">No schedule</option><option value="ONCE">Scheduled</option><option value="DAILY">Daily window</option></select></label><div id="add-schedule-once" hidden><label for="add-schedule-start">Start at<input id="add-schedule-start" type="datetime-local"></label><label for="add-schedule-end">Stop at (optional)<input id="add-schedule-end" type="datetime-local"></label></div><div id="add-schedule-daily" hidden><label>Resume at${timeControl('id="add-daily-start"','01:00','Resume at')}</label><label>Pause at${timeControl('id="add-daily-end"','07:00','Pause at')}</label></div><label>Individual speed limit (optional)<div class="limit-inputs"><input id="add-limit-value" type="text" inputmode="decimal" placeholder="Unlimited" aria-label="Individual speed limit"><select id="add-limit-unit" aria-label="Speed limit unit"><option>MB/s</option><option>KB/s</option></select></div></label></div>`:''}`;}
async function loadDestinationFolders(id=null) {
  const area=$('#destination-folders');if(!area)return;area.hidden=false;
  area.innerHTML='<p role="status">Loading phone folders…</p>';$('#destination-error').textContent='';
  try {
    const listing=await api('folders'+(id?'/'+encodeURIComponent(id):''));
    if(!area.isConnected)return;
    state.destinationListing=listing;
    area.innerHTML=`<div class="folder-picker-heading">${button('Phone','destination-roots','phone','plain')}${listing.current?button('Up','destination-up','back','plain'):''}<strong>${escapeText(listing.name)}</strong></div>${listing.current?button('Use this folder','destination-current','folder'):''}${listing.folders.map(folder=>`<div class="folder-choice"><button class="plain" data-destination-open="${escapeText(folder.id)}">${icon('folder')}${escapeText(folder.name)}</button><button data-destination-select="${escapeText(folder.id)}">Select</button></div>`).join('')}${listing.truncated?'<p class="muted">First 500 folders shown. Open a folder to browse further.</p>':''}`;
  } catch(error) {if(area.isConnected){area.innerHTML='';$('#destination-error').textContent=error.message;}}
}
function selectDestination(id,name) {
  state.addDestination={id,name};$('#destination-name').textContent=name;
  $('#destination-folders').hidden=true;$('#destination-error').textContent='';
}
async function pasteLinks() {
  const input=$('#links');if(!input||state.httpSubmitting)return;
  input.focus();
  if(!navigator.clipboard?.readText){toast('Paste links here with Ctrl+V or ⌘V');return;}
  const previous=input.value,start=input.selectionStart,end=input.selectionEnd;
  try {
    const text=await navigator.clipboard.readText();
    if(!input.isConnected||!$('#modal').open||state.httpSubmitting)return;
    if(input.value!==previous){toast('Links changed. Paste again when ready.');return;}
    if(!text.trim()){toast('The clipboard is empty');return;}
    input.setRangeText(text,start,end,'end');
    const output=$('#http-review');if(output)output.textContent='';
    scheduleLinkReview();
  } catch(error){if(input.isConnected&&$('#modal').open)toast('Paste links here with Ctrl+V or ⌘V');}
}
function scheduleLinkReview() {
  clearTimeout(state.linkReviewTimer);
  state.linkReviewTimer=setTimeout(()=>reviewLinks(),700);
}
async function reviewLinks() {
  const input=$('#links'),output=$('#http-review');
  if(!input?.isConnected||!output?.isConnected||!$('#modal').open||state.httpSubmitting)return;
  const text=input.value,links=text.split(/\r?\n/).map(value=>value.trim()).filter(Boolean);
  if(!links.length){output.textContent='';return;}
  if(links.length>100){output.textContent='Add between 1 and 100 links';return;}
  if(state.reviewingLinks){state.reviewAgain=true;return;}
  state.reviewingLinks=true;state.reviewAgain=false;output.textContent='Checking links…';
  try {
    const result=await api('downloads/review',{links});
    if(!input.isConnected||input.value!==text||!output.isConnected||!$('#modal').open)return;
    output.innerHTML=result.results.map(row=>`<div class="http-review-row ${row.ready?'':'error'}"><span>${icon(row.ready?'check':'close')}</span><span>${row.ready?escapeText(row.name):`Line ${row.line}: ${escapeText(row.error)}`}</span><span>${row.ready?size(row.size):'Unavailable'}</span></div>`).join('');
  } catch(error){if(output.isConnected&&input.value===text&&$('#modal').open)output.textContent=error.message;}
  finally{state.reviewingLinks=false;if(state.reviewAgain){state.reviewAgain=false;scheduleLinkReview();}}
}
function readSubmissionOptions() {
  const kind=$('#add-schedule-kind').value;
  let schedule=null;
  if(kind==='ONCE') {
    const start=$('#add-schedule-start').value,end=$('#add-schedule-end').value;
    if(!start)throw new Error('Choose a start date and time');
    if(end&&end<=start)throw new Error('Stop time must be after the start time');
    schedule={kind,startLocal:start,zone:state.snapshot.zone};
    if(end)schedule.endLocal=end;
  } else if(kind==='DAILY') {
    const minute=value=>{
      if(!/^\d{2}:\d{2}$/.test(value))throw new Error('Choose both daily times');
      const [h,m]=value.split(':').map(Number);
      if(h>23||m>59)throw new Error('Choose valid daily times');
      return h*60+m;
    };
    schedule={kind,startMinute:minute($('#add-daily-start').value),endMinute:minute($('#add-daily-end').value),zone:state.snapshot.zone};
    if(schedule.startMinute===schedule.endMinute)throw new Error('Daily start and stop times must differ');
  } else if(kind!=='NONE')throw new Error('Choose a valid schedule');
  const limit=$('#add-limit-value').value.trim(),unit=$('#add-limit-unit').value;
  if(limit) {
    const bytes=Number(limit)*(unit==='MB/s'?1000000:1000);
    if(!['KB/s','MB/s'].includes(unit)||!Number.isFinite(bytes)||bytes<1||bytes>9223372036854775807)throw new Error('Enter a positive speed limit');
  }
  return {schedule,speedLimit:limit?{value:limit,unit}:null};
}
function showAdd(mode='links') {
  state.addMode=mode;state.addDestination=null;state.destinationListing=null;
  const form=mode==='links'?'<div class="links-label-row"><label for="links">Enter download links (one per line)</label>'+button('Paste','paste-links','clipboard')+'</div><textarea id="links" placeholder="https://example.com/file.zip"></textarea>'+addOptions(true)+'<div class="http-review-heading"><strong>Detected downloads</strong></div><div id="http-review" role="status"></div><div id="submit-results" role="status"></div>':`${mode==='magnet'?'<label for="torrent-link">Enter magnet link or torrent URL</label><textarea id="torrent-link" placeholder="magnet:?xt=urn:btih:…"></textarea>':''}<div class="torrent-drop">${icon('file')}<strong>Drag and drop a .torrent file here</strong><span>or click to choose a file</span>${button('Choose .torrent file','choose-torrent')}<input id="torrent-upload" type="file" accept=".torrent,application/x-bittorrent" hidden></div><div class="banner"><span>Torrent files are processed on your phone.<br><small>Select content before downloading.</small></span></div><p id="torrent-error" class="error" role="alert"></p><div id="torrent-progress" role="status"></div>`;
  showModal('Add downloads',addTabs(mode)+form,mode==='links'?button('Add downloads','submit-links','plus','primary'):mode==='magnet'?button('Resolve metadata','resolve-torrent','link','primary'):'');
}
async function cancelTorrentDraft() {
  const draft=state.torrent;state.torrent=null;
  if(draft?.id&&authorized())try{await api('torrent/cancel',{id:draft.id});}catch(error){if(!state.offline)toast(error.message);}
}
async function resolveTorrent(file=null) {
  if(state.torrent)return;
  const draft={id:null,status:'resolving'};state.torrent=draft;
  const progress=$('#torrent-progress');progress.innerHTML=`<div class="banner"><span class="spinner"></span><span>Resolving metadata…<br><small>Contacting trackers to fetch torrent information.</small></span>${button('Cancel','cancel-torrent')}</div>`;
  $('#torrent-error').textContent='';
  $('#modal').querySelectorAll('[data-action^="add-"],[data-action="resolve-torrent"],[data-action="choose-torrent"],input,textarea').forEach(control=>control.disabled=true);
  try {
    if(file&&(!file.size||file.size>8*1024*1024))throw new Error('Choose a torrent file up to 8 MB');
    const result=file?await api('torrent/upload',await file.arrayBuffer(),{contentType:'application/x-bittorrent'}):await api('torrent/resolve',{link:$('#torrent-link').value.trim()});
    if(state.torrent!==draft){await api('torrent/cancel',{id:result.id});return;}
    draft.id=result.id;
  }catch(error){if(state.torrent===draft){state.torrent=null;$('#torrent-error').textContent=error.message;progress.innerHTML='';$('#modal').querySelectorAll('button,input,textarea').forEach(control=>control.disabled=false);}}
}
function torrentFileRows(draft) {
  const root={children:new Map(),files:[],key:''};draft.groups=new Map();
  const rootPrefix=draft.files.every(file=>file.path.startsWith(draft.name+'/'))?draft.name+'/':'';
  for(const file of draft.files){
    const parts=file.path.slice(rootPrefix.length).split('/');let node=root;
    for(const name of parts.slice(0,-1)){
      const key=node.key?node.key+'/'+name:name;
      if(!node.children.has(name)){const child={children:new Map(),files:[],key,name};node.children.set(name,child);draft.groups.set(key,child);}
      node=node.children.get(name);node.files.push(file);
    }
    node.children.set('file:'+file.index,{file,name:parts.at(-1)});
  }
  const render=(node,depth)=>Array.from(node.children.values()).map(child=>{
    const indent=Math.min(depth,6)*18;
    if(child.file){const file=child.file;return `<tr><td><input type="checkbox" data-torrent-select="${file.index}" ${draft.selected.has(file.index)?'checked':''} aria-label="Select ${escapeText(file.path)}"></td><td><span class="torrent-path" style="padding-left:${indent}px">${icon('file')}${escapeText(child.name)}</span></td><td>${size(file.size)}</td></tr>`;}
    const collapsed=draft.closedFolders?.has(child.key);
    return `<tr class="torrent-group"><td><input type="checkbox" data-torrent-group="${escapeText(child.key)}" aria-label="Select folder ${escapeText(child.key)}"></td><td><button class="plain torrent-path" style="padding-left:${indent}px" data-torrent-collapse="${escapeText(child.key)}" aria-expanded="${!collapsed}">${icon('folder')}${escapeText(child.name)} (${child.files.length})</button></td><td>${size(child.files.reduce((n,file)=>n+file.size,0))}</td></tr>${collapsed?'':render(child,depth+1)}`;
  }).join('');
  return render(root,0);
}
function showTorrentContents(draft) {
  const total=draft.files.reduce((n,file)=>n+file.size,0);
  draft.selected=new Set(draft.files.map(file=>file.index));
  showModal('Select files to download',`<div class="torrent-heading">${icon('folder')}<div><strong>${escapeText(draft.name)}</strong><p class="muted">${draft.files.length} ${draft.files.length===1?'file':'files'} · ${size(total)}</p></div></div><div class="torrent-files"><table><thead><tr><th><input id="torrent-select-all" type="checkbox" checked aria-label="Select all torrent files"></th><th>Name</th><th>Size</th></tr></thead><tbody>${torrentFileRows(draft)}</tbody></table></div><div class="panel torrent-selected"><span>Selected</span><strong id="torrent-selected-total">${draft.files.length} ${draft.files.length===1?'file':'files'} · ${size(total)}</strong></div>${addOptions()}<label class="switch-row"><span><strong>Seed after completion</strong><small>Continue seeding this torrent after the download finishes</small></span><input id="torrent-seed" type="checkbox"></label><p id="torrent-error" class="error" role="alert"></p>`,button('Add selected files','submit-torrent',null,'primary'));
  updateTorrentSelected();
}
function updateTorrentSelected() {
  const draft=state.torrent;if(!draft?.selected)return;
  $('#torrent-selected-total').textContent=`${draft.selected.size} ${draft.selected.size===1?'file':'files'} · ${size(draft.files.filter(file=>draft.selected.has(file.index)).reduce((n,file)=>n+file.size,0))}`;
  $('#modal').querySelectorAll('[data-torrent-group]').forEach(input=>{const files=draft.groups.get(input.dataset.torrentGroup).files;const count=files.filter(file=>draft.selected.has(file.index)).length;input.checked=count===files.length;input.indeterminate=count>0&&count<files.length;});
  $('#torrent-select-all').checked=draft.selected.size===draft.files.length;
  $('#torrent-select-all').indeterminate=draft.selected.size>0&&draft.selected.size<draft.files.length;
  $('[data-action="submit-torrent"]').disabled=draft.selected.size===0;
}
async function pollTorrent() {
  const draft=state.torrent;
  if(!draft?.id||draft.status!=='resolving'||state.pollingTorrent)return;
  state.pollingTorrent=true;
  try {
    const result=await api(`torrent/draft/${encodeURIComponent(draft.id)}`);
    if(state.torrent!==draft)return;
    if(result.status==='ready'){Object.assign(draft,result);showTorrentContents(draft);}
    if(result.status==='failed'){state.torrent=null;await api('torrent/cancel',{id:draft.id});$('#torrent-error').textContent=result.error;$('#torrent-progress').innerHTML='';$('#modal').querySelectorAll('button,input,textarea').forEach(control=>control.disabled=false);}
  }catch(error){if(state.torrent===draft&&$('#torrent-error'))$('#torrent-error').textContent=error.message;}
  finally{state.pollingTorrent=false;}
}
function showBackupExport() {
  showModal('Export backup',`<p class="muted">Keep your download list and app settings safe.</p><section class="panel"><label class="switch-row"><span><strong>Download list</strong><small>Links, queue order, schedules, limits and torrent selections</small></span><input id="backup-downloads" type="checkbox" checked></label><label class="switch-row"><span><strong>App settings</strong><small>Network, speed, appearance and preferences</small></span><input id="backup-settings" type="checkbox" checked></label></section><p class="muted backup-note">Downloaded files and browser sessions are not included.</p><p id="backup-error" class="error" role="alert"></p>`,button('Export backup','export-backup','download','primary'));
}
function showBackupRestore(review=null,name='') {
  state.backupReview=review;
  const preview=review?`<section class="panel backup-review"><h3>Restore preview</h3><p>${escapeText(name)}</p><p>${review.downloads} download${review.downloads===1?'':'s'} · ${review.torrents} torrent${review.torrents===1?'':'s'} · ${review.duplicates} duplicate${review.duplicates===1?'':'s'}</p><p class="gold">Keep existing downloads; skip duplicates.</p><p class="muted">Restored downloads start paused at 0%. Files and folder permissions are not restored. Missing folder access uses your current default save location.</p>${review.settings?'<label class="switch-row"><span><strong>Restore app settings</strong><small>Replace current preferences</small></span><input id="restore-settings" type="checkbox" checked></label>':'<p class="muted">No app settings in this backup.</p>'}${review.names.length?`<ul class="backup-names">${review.names.map(n=>`<li>${escapeText(n)}</li>`).join('')}</ul>${review.truncated?'<small class="muted">Showing the first 50 downloads</small>':''}`:''}</section>`:'';
  showModal('Restore backup',`${button('Choose backup file','choose-backup','file')}<input id="backup-upload" type="file" accept=".json,application/json" hidden><p class="muted backup-note">Choose an SDM JSON backup up to 5 MB. Review its contents before restoring.</p>${preview}<p id="backup-error" class="error" role="alert"></p>`,review?button('Review & restore','restore-backup',null,'primary'):'');
}
async function cancelBackupReview() {
  const review=state.backupReview;state.backupReview=null;
  if(review&&authorized())try{await api('backup/cancel',{review:review.review});}catch(error){if(!state.offline)toast(error.message);}
}
async function reviewBackup(file) {
  if(state.backupWorking)return;
  state.backupWorking=true;const control=$('[data-action="choose-backup"]');control.disabled=true;$('#backup-error').textContent='';
  try {
    await cancelBackupReview();
    $('[data-action="restore-backup"]')?.remove();$('.backup-review')?.remove();
    if(file.size<1||file.size>5*1024*1024)throw new Error('Choose an SDM backup up to 5 MB');
    const review=await api('backup/review',await file.arrayBuffer(),{contentType:'application/octet-stream'});
    if(authorized())showBackupRestore(review,file.name);
  }catch(error){if($('#backup-error'))$('#backup-error').textContent=error.message;}
  finally{state.backupWorking=false;if(control.isConnected)control.disabled=false;}
}
function numberSetting(key,value,min,max,label) {
  return `<div class="number-stepper"><button type="button" data-number-step="-1" aria-label="Decrease ${escapeText(label)}">−</button><input aria-label="${escapeText(label)}" data-setting="${key}" type="number" min="${min}" max="${max}" step="1" value="${value}"><button type="button" data-number-step="1" aria-label="Increase ${escapeText(label)}">+</button></div>`;
}
function renderSettings() {
  const s={...state.snapshot.settings,...state.settingsDraft};
  const field=(title,subtitle,control)=>`<label class="form-row"><span><strong>${title}</strong><p>${subtitle}</p></span>${control}</label>`;
  const toggle=(key,title,subtitle)=>field(title,subtitle,`<input data-setting="${key}" type="checkbox" ${s[key]?'checked':''}>`);
  const select=(key,values)=>`<select data-setting="${key}">${values.map(v=>`<option value="${v}" ${v===s[key]?'selected':''}>${v}</option>`).join('')}</select>`;
  $('#app').innerHTML=shell('Settings',`<nav class="tabs settings-tabs" aria-label="Settings sections">${["General","Downloads","Storage","Web panel","Backup"].map(label=>`<button data-settings-tab="${label}" class="${(state.settingsTab||"Downloads")===label?"active":""}">${label}</button>`).join('')}</nav><section class="settings-grid"><div class="panel"><h3>Download concurrency</h3>${field('Connections per download','Maximum parallel streams',select('connections',[8,16,24,32]))}${field('Simultaneous downloads','Maximum active downloads',numberSetting('simultaneous',s.simultaneous,1,10,'Simultaneous downloads'))}${toggle('autoResume','Auto resume','Resume interrupted downloads when SDM starts')}</div><div class="panel"><h3>Automatic retry</h3>${field('Retry count','Only temporary failures are retried',numberSetting('retryCount',s.retryCount,0,10,'Retry count'))}${field('Retry delay','Maximum 3 minutes',`<select data-setting="retryDelaySeconds">${[1,2,3,4,5,6,7,8,9,...Array.from({length:18},(_,i)=>(i+1)*10)].map(v=>`<option value="${v}" ${v===s.retryDelaySeconds?'selected':''}>${v}s</option>`).join('')}</select>`)}</div><div class="panel"><h3>Network</h3>${toggle('wifiOnly','Wi-Fi only','Pause downloads on mobile data')}</div><div class="panel"><h3>Daily schedule</h3>${toggle('dailyBulkScheduleEnabled','Daily resume and pause','Phone time zone: '+escapeText(state.snapshot.zone))}${field('Resume at','Start the daily download window',timeControl('data-setting="dailyResumeMinute" data-time-minute',minuteLabel(s.dailyResumeMinute),'Resume at'))}${field('Pause at','End the daily download window',timeControl('data-setting="dailyPauseMinute" data-time-minute',minuteLabel(s.dailyPauseMinute),'Pause at'))}</div><div class="panel"><h3>Speed limit</h3>${toggle('unlimitedSpeed','Unlimited','No global download speed limit')}${field('Limit to','MB/s · shared by active downloads',numberSetting('speedLimitMbps',s.speedLimitMbps,1,30,'Global speed limit'))}${toggle('speedLimitWifiOnly','Apply limit on Wi-Fi only','The global limit applies only on Wi-Fi')}</div><div class="panel"><h3>Notifications</h3>${toggle('downloadComplete','Download complete','Notify when a download finishes')}${toggle('speedAlerts','Speed alerts','Notify when download speed changes')}</div><div class="panel"><h3>Appearance</h3>${field('Theme','Phone app appearance',select('theme',['dark','light']))}${field('Language','English','<span class="muted">English</span>')}</div><div class="panel"><h3>About SDM</h3>${field('SDM version','Sibi Download Manager',`<span class="muted">${escapeText(state.snapshot.appVersion)}</span>`)}</div><div class="panel"><h3>Reset settings</h3><p class="muted" style="margin:12px 0 20px">Restore SDM defaults. Downloads and saved files are kept.</p><div class="actions">${button('Reset settings','show-reset-settings',null,'danger')}</div></div><div class="panel"><h3>Backup & restore</h3><p class="muted">Download list and app settings</p><div class="actions backup-actions">${button('Export backup','show-backup-export','download')}${button('Restore backup','show-backup-restore','folder')}</div></div><div class="panel"><h3>Storage</h3><div id="storage-settings" role="status">Loading storage settings…</div></div><div class="panel"><h3>Local network access</h3><div id="panel-controls" role="status">Loading panel controls…</div></div><div class="panel"><h3>Pairing and security</h3><p class="muted">Approved browsers can manage downloads on your phone.</p><div id="approved-browsers" role="status">Loading approved browsers…</div><p id="browser-error" class="error" role="alert"></p></div></section><footer class="actions" style="margin-top:20px">${button('Save changes','save-settings',null,'primary')}</footer>`);
  const groups={'Download concurrency':'Downloads','Automatic retry':'Downloads','Network':'Downloads','Daily schedule':'Downloads','Speed limit':'Downloads','About SDM':'General','Reset settings':'General','Notifications':'General','Appearance':'General','Backup & restore':'Backup','Storage':'Storage','Local network access':'Web panel','Pairing and security':'Web panel'};
  document.querySelectorAll('.settings-grid>.panel').forEach(panel=>panel.dataset.settingsSection=groups[panel.querySelector('h3').textContent]);
  selectSettingsTab(state.settingsTab||'Downloads');
}
function selectSettingsTab(name) {
  state.settingsTab=name;
  document.querySelectorAll('[data-settings-tab]').forEach(button=>button.classList.toggle('active',button.dataset.settingsTab===name));
  document.querySelectorAll('[data-settings-section]').forEach(panel=>panel.hidden=panel.dataset.settingsSection!==name);
  if(name==='Web panel'){loadApprovedBrowsers();loadPanelControls();}
  if(name==='Storage')loadStorageSettings();
}
async function loadStorageSettings() {
  const area=$('#storage-settings');if(!area)return;
  try{const storage=await api('settings/storage');if(!area.isConnected)return;state.storageSettings=storage;const signature=JSON.stringify(storage);if(area.dataset.signature===signature)return;area.dataset.signature=signature;
    area.innerHTML=`<div class="form-row"><div><strong>Default download folder</strong><p>${escapeText(storage.label)}</p></div>${button('Change folder','storage-default','folder')}</div><div class="form-row"><div><strong>Rename save folder</strong><p>Keep files, queue and download progress</p></div>${button('Rename','storage-rename','rename')}</div>${storage.renamePending?'<p class="notice">Folder access is pending. Open SDM Settings → Rename save folder on your phone to finish.</p>':''}<label class="form-row"><span><strong>Category folders</strong><p>Organize new downloads by file type</p></span><input id="storage-categories-enabled" type="checkbox" ${storage.enabled?'checked':''}></label><div class="storage-category-grid">${storage.categories.map(category=>`<div class="form-row"><div><strong>${escapeText(category.name)}</strong><p>${escapeText(category.label)}</p>${category.unavailable?'<small class="error">Folder access unavailable. Choose the folder on your phone.</small>':''}</div><div class="actions"><button data-action="storage-category" data-category="${escapeText(category.id)}">Change</button>${category.custom?`<button class="plain" data-action="storage-clear" data-category="${escapeText(category.id)}">Use default</button>`:''}</div></div>`).join('')}</div><p class="muted">New folder access requires approval on your phone. Existing downloads keep their destinations.</p><p id="storage-error" class="error" role="alert"></p>`;
  }catch(error){if(area.isConnected)area.textContent=error.message;}
}
async function showStorageFolder(category=null) {
  const picker={files:[],purpose:'storage',category,listing:null,loading:false,error:'',selected:null};state.movePicker=picker;
  renderMovePicker();await loadMoveFolders(null,picker);
}
function showFolderAccessWaiting(request) {
  state.folderRequest=request;sessionStorage.setItem('sdm-folder-request',request.id);
  showModal(`${icon('phone')}Choose folder on your phone`,`<div class="phone-folder-wait"><div class="spinner" aria-hidden="true"></div><div><strong>Open SDM on your phone</strong><p>Choose a folder and confirm Android access. This request expires after 2 minutes.</p></div></div><p id="folder-access-error" class="error" role="alert"></p>`);$('#modal').classList.add('phone-folder-dialog');
}
async function cancelFolderAccessRequest() {
  const request=state.folderRequest;if(!request)return;
  await api('settings/storage/request/cancel',{id:request.id});
  if(state.folderRequest===request){state.folderRequest=null;sessionStorage.removeItem('sdm-folder-request');}
}
let checkingFolderAccess=false;
async function pollFolderAccess() {
  const request=state.folderRequest;if(!request||!authorized()||checkingFolderAccess)return;
  checkingFolderAccess=true;
  try{const result=await api('settings/storage/request/'+encodeURIComponent(request.id));if(state.folderRequest!==request)return;
    if(result.state!=='pending'){state.folderRequest=null;sessionStorage.removeItem('sdm-folder-request');$('#modal').close();toast(result.message);if(state.page==='settings')await loadStorageSettings();await refresh();}
  }catch(error){if(state.folderRequest===request){if(error.status===400||error.status===409){state.folderRequest=null;sessionStorage.removeItem('sdm-folder-request');$('#modal').close();toast('Folder request expired. Choose the folder again.');}else if($('#folder-access-error'))$('#folder-access-error').textContent=error.message;}}
  finally{checkingFolderAccess=false;}
}
async function loadPanelControls(){const area=$('#panel-controls');if(!area)return;try{const panel=await api('panel');if(!area.isConnected)return;state.panelStatus=panel;area.innerHTML=`<p class="muted">Manage access on your local network.</p><div class="form-row panel-address-row"><span>LAN address</span><div class="actions"><span class="gold">${escapeText(panel.address||location.origin)}</span>${button('Copy address','copy-panel-address','link')}</div></div><label class="form-row"><span><strong>Port</strong><p>1024–65535</p></span><div class="number-stepper"><button type="button" data-number-step="-1" aria-label="Decrease web panel port">−</button><input id="panel-port" type="number" min="1024" max="65535" step="1" value="${panel.port}" aria-label="Web panel port"><button type="button" data-number-step="1" aria-label="Increase web panel port">+</button></div></label><div class="actions" style="margin-top:20px">${button('Change port','panel-change-port',null,'primary')}${button('Stop web panel','panel-stop',null,'danger')}</div><p id="panel-error" class="error" role="alert">${escapeText(panel.error||'')}</p>`;}catch(error){if(area.isConnected)area.textContent=error.message;}}
async function loadApprovedBrowsers() {
  const area=$('#approved-browsers');if(!area)return;
  try {
    const listing=await api('browsers');if(!area.isConnected)return;
    state.approvedBrowsers=listing;
    area.innerHTML=listing.browsers.map(browser=>`<div class="approved-browser"><div><strong>${escapeText(browser.browser)}</strong><p class="muted">${escapeText(browser.address)}${browser.id===listing.current?' · Current browser':''}</p></div><button class="danger" data-revoke-browser="${escapeText(browser.id)}">Revoke</button></div>`).join('')+button('Revoke all','show-revoke-all',null,'danger');
  } catch(error) {if(area.isConnected){area.innerHTML='';$('#browser-error').textContent=error.message;}}
}
function showBrowserRevocation(id) {
  state.revokeBrowser=id;
  showModal(id==='all'?'Revoke all browsers?':'Revoke browser access?',`<p>${id==='all'?'All approved browsers, including this one, will lose access.':'This browser will need approval on your phone to reconnect.'}</p><p class="muted">Downloads continue on your phone.</p>`,button('Revoke','confirm-browser-revoke',null,'danger'));
}

function updateConnectionUI() {
  const workspace=$('.workspace');if(!workspace)return;
  let banner=workspace.querySelector('.connection-banner');
  if(state.offline&&!banner){banner=document.createElement('div');banner.className='banner connection-banner';banner.setAttribute('role','status');workspace.querySelector('.topbar').after(banner);}
  if(banner){if(state.offline)banner.innerHTML=`${icon('lock')}<span>Connection lost. Downloads continue on your phone.<br><small>Last sync: ${new Date(state.snapshot?.time||Date.now()).toLocaleTimeString()}</small></span>${button('Retry','refresh')}`;else banner.remove();}
  const status=$('.device-status p');if(status){status.className=state.offline?'danger':'green';status.innerHTML=`<span class="dot ${state.offline?'offline':''}"></span>${state.offline?'Disconnected':'Connected'}`;}
  document.querySelectorAll('[data-live-speed]').forEach(value=>{const id=value.dataset.liveSpeedRow,row=id?state.snapshot?.downloads.find(row=>row.id===id):null;const current=id?(row?.[value.dataset.liveSpeedField]||0):(state.snapshot?.downloads||[]).filter(row=>['CONNECTING','DOWNLOADING'].includes(row.state)).reduce((sum,row)=>sum+row.speed,0);value.textContent=state.offline?'—':speed(current);});
  document.querySelectorAll('[data-live-eta]').forEach(value=>{const row=state.snapshot?.downloads.find(row=>row.id===value.dataset.liveEta);value.textContent=row?remainingTime(row):'—';});
  document.querySelectorAll('.mobile-device-status').forEach(value=>{value.className='mobile-device-status '+(state.offline?'danger':'green');value.textContent=state.offline?'Disconnected':'Connected';});
  const localActions=new Set(['refresh','close-modal','back','files-root','copy-url','copy-magnet','copy-panel-address']);
  document.querySelectorAll('[data-command],[data-action],[data-file-phone]').forEach(control=>{
    if(localActions.has(control.dataset.action))return;
    if(state.offline&&!control.disabled){control.dataset.offlineDisabled='true';control.disabled=true;}
    else if(!state.offline&&control.dataset.offlineDisabled){delete control.dataset.offlineDisabled;control.disabled=false;}
  });
  updateDownloadCommandUI();
}
function render() {
  if(!authorized()) return renderPairing();
  if(!state.snapshot) return;
  if(dragId&&state.page==='downloads'&&state.tab==='queue'&&!state.detail&&!state.offline)return;
  if(state.detail||state.page!=='files')closeFileMenu();
  if(state.detail) renderDetails();
  else if(state.page==='settings') renderSettings();
  else if(state.page==='files') renderFiles();
  else renderDownloads();
  updateConnectionUI();
}
async function refresh() {
  if(!authorized() || state.refreshing) return;
  state.refreshing=true;
  const sessionAtSend=state.session;
  try {
    const snapshot=await api('state',null,{signal:AbortSignal.timeout(8000)});
    if(state.session!==sessionAtSend)return;
    let changed=JSON.stringify([snapshot.downloads,snapshot.settings])!==JSON.stringify([state.snapshot?.downloads,state.snapshot?.settings]);
    const wasOffline=state.offline;
    state.device=snapshot.device;state.snapshot=snapshot;state.offline=false;
    if(state.page==='files') {const listing=await api('files'),files=listing.files;changed=changed||JSON.stringify(files)!==JSON.stringify(state.files)||JSON.stringify(listing.storage)!==JSON.stringify(state.storage);state.files=files;state.storage=listing.storage;state.fileLocation=listing.location;const keys=new Set(files.map(fileKey));for(const key of state.fileSelected)if(!keys.has(key)&&!files.some(file=>file.torrent&&key===file.id+':folder'))state.fileSelected.delete(key);if(state.fileFolder&&!files.some(file=>file.id===state.fileFolder))state.fileFolder=null;}
    const ids=new Set(snapshot.downloads.map(r=>r.id));
    for(const id of state.selected) if(!ids.has(id)) state.selected.delete(id);
    const sampleTime=Date.now();
    if(sampleTime-state.lastSample>=950) {
      for(const row of snapshot.downloads) {
        const samples=recentHistory(state.history.get(row.id)||[],sampleTime);samples.push({time:sampleTime,value:row.speed});state.history.set(row.id,samples);
        const uploads=recentHistory(state.uploadHistory.get(row.id)||[],sampleTime);uploads.push({time:sampleTime,value:row.uploadRate||0});state.uploadHistory.set(row.id,uploads);
      }
      state.lastSample=sampleTime;
    }
    for(const map of [state.history,state.uploadHistory])for(const id of map.keys())if(!ids.has(id))map.delete(id);
    updateConnectionUI();
    const focused=document.activeElement;
    if((changed||wasOffline||state.detail||snapshot.downloads.some(row=>row.retryAt!=null))&&!$('#modal').open && !focused?.matches('input,select,textarea')&&!focused?.closest?.('.file-menu')) render();
  } catch(error) {if(authorized()){state.offline=true;if(!$('.workspace'))render();else updateConnectionUI();}} finally {state.refreshing=false;}
}
function updateDownloadCommandUI() {
  if(!state.downloadCommandPending)return;
  document.querySelectorAll('[data-command],[data-action^="bulk-"]').forEach(control=>control.disabled=true);
}
async function command(action,ids,confirmed=false) {
  if(state.downloadCommandPending)return;
  ids=[...new Set(ids)].sort();
  if(action==='cancel'&&!confirmed){state.pendingCancel=[...ids];return showModal('Cancel download?', '<p class="muted">The partial file will be kept so you can resume later.</p>',button('Cancel download','confirm-cancel',null,'danger'),'Keep downloading');}
  if(!ids.length)return toast('No downloads need this action');
  if(state.offline)return toast('Reconnect before changing downloads');
  const signature=JSON.stringify([state.session,action,[...ids].sort()]);
  const attempt=state.downloadCommandRetry?.signature===signature?state.downloadCommandRetry:{signature,key:Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('')};
  const names=new Map((state.snapshot?.downloads||[]).map(row=>[row.id,row.name]));
  state.downloadCommandPending=true;updateDownloadCommandUI();
  try {
    const result=await api('download/action',{action,ids},{commandKey:attempt.key,signal:AbortSignal.timeout(15000)});
    state.downloadCommandRetry=null;
    if(['pause','resume','retry'].includes(action))result.results.filter(row=>row.accepted).forEach(row=>state.selected.delete(row.id));
    const errors=result.results.filter(row=>row.error).map(row=>ids.length>1?`${names.get(row.id)||'Unknown download'}: ${row.error}`:row.error);
    await refresh();if(errors.length)toast([...new Set(errors)].join(' · '));
  }catch(error){state.downloadCommandRetry=authorized()&&error.status!==401?attempt:null;toast(error.message);}
  finally{state.downloadCommandPending=false;render();}
}

function showModal(title,content,footer='',dismissLabel='Cancel') {closeFileMenu();$('#modal').classList.remove('preview-dialog','rename-folder-dialog','phone-folder-dialog','settings-reset-dialog','folder-move-dialog','schedule-dialog','link-refresh-dialog','checksum-dialog');$('#modal').innerHTML=`<header><h2>${title==='Schedule download'?icon('clock'):title==='Individual speed limit'?icon('speed'):title==='Refresh download link'?icon('link'):''}${title}</h2><button class="plain" data-action="close-modal" aria-label="Close dialog">${icon('close')}</button></header><div class="dialog-body">${content}</div><footer>${button(dismissLabel,'close-modal')}${footer}</footer>`;$('#modal').showModal();}
async function act(action,control=null) {
  if(action==='copy-panel-address'){await copyText(location.origin);return toast('Panel address copied');}
  if(action==='show-revoke-all')return showBrowserRevocation('all');
  if(action==='confirm-browser-revoke'){const id=state.revokeBrowser;await api('revoke',{id});$('#modal').close();await refresh();if(authorized())await loadApprovedBrowsers();return;}
  if(action==='close-modal'){if(state.removing||state.fileWorking||state.linkWorking||state.backupWorking||state.checksumWorking)return;await cancelBackupReview();await cancelLinkReview();await cancelBulkLinkReview();await cancelFolderAccessRequest();state.pendingRemoval=null;state.pendingCancel=null;state.movePicker=null;$('#modal').close();return cancelTorrentDraft();}
  if(action==='choose-destination'||action==='destination-roots')return loadDestinationFolders();
  if(action==='destination-up')return loadDestinationFolders(state.destinationListing?.parent);
  if(action==='destination-current'){const listing=state.destinationListing;if(listing?.current)selectDestination(listing.current,listing.name);return;}
  if(action==='default-destination'){state.addDestination=null;$('#destination-name').textContent=state.snapshot.saveLocation||'Selected save folder';$('#destination-folders').hidden=true;return;}
  if(action.startsWith('add-')){await cancelTorrentDraft();return showAdd(action.slice(4));}
  if(action==='move-selected-files')return showFileMove(selectedFiles());
  if(action==='panel-change-port'||action==='panel-stop'){const panel=state.panelStatus;if(!panel)return;const port=Number($('#panel-port').value);if(action==='panel-change-port'&&(!Number.isInteger(port)||port<1024||port>65535)){return $('#panel-error').textContent='Choose a whole port number from 1024 to 65535';}state.panelChange={action:action==='panel-stop'?'stop':'port',port,expectedPort:panel.port};showModal(action==='panel-stop'?'Stop web panel?':'Change web panel port?',`<p>${action==='panel-stop'?'This browser will disconnect. Start the panel again from SDM on your phone.':`The panel will move to port ${port}. Open the new address after the change.`}</p><p class="muted">Downloads continue on your phone.</p><p id="panel-change-error" class="error" role="alert"></p>`,button('Confirm','panel-confirm',null,action==='panel-stop'?'danger':'primary'));return;}
  if(action==='panel-confirm'){control.disabled=true;try{const result=await api('panel/control',{...state.panelChange,confirmed:true});showModal(result.action==='stop'?'Stopping web panel':'Port change requested',`<p>${result.action==='stop'?'Start the panel again from SDM on your phone.':`<a class="gold" href="${escapeText(result.address)}">${escapeText(result.address)}</a>`}</p><p class="muted">Downloads continue on your phone.</p>`);state.offline=true;}catch(error){$('#panel-change-error').textContent=error.message;control.disabled=false;}return;}
  if(action==='storage-rename'){
    const name=state.storageSettings?.name||'';
    showModal(`${icon('rename')}Rename save folder`,`<p class="rename-intro">Keep your files and download progress.</p><label for="storage-folder-name">Folder name</label><input id="storage-folder-name" value="${escapeText(name)}" maxlength="120" autocomplete="off" spellcheck="false"><div class="rename-access-note">${icon('phone')}<span>Confirm folder access on your phone if requested.</span></div><p id="storage-rename-error" class="error" role="alert"></p>`,button('Rename folder','storage-rename-confirm',null,'primary'));
    $('#modal').classList.add('rename-folder-dialog');$('[data-action="storage-rename-confirm"]').disabled=true;
    $('#storage-folder-name').focus();$('#storage-folder-name').select();return;
  }
  if(action==='storage-rename-confirm'){if(state.fileWorking)return;state.fileWorking=true;$('#modal').querySelectorAll('button,input').forEach(e=>e.disabled=true);try{const result=await api('settings/storage/rename',{name:$('#storage-folder-name').value,confirmed:true});$('#modal').close();await loadStorageSettings();toast(result.message);}catch(error){$('#storage-rename-error').textContent=error.message;}finally{state.fileWorking=false;$('#modal').querySelectorAll('button,input').forEach(e=>e.disabled=false);}return;}
  if(action==='storage-request-folder'){const picker=state.movePicker;if(picker?.purpose!=='storage'||state.fileWorking)return;state.fileWorking=true;control.disabled=true;try{const request=await api('settings/storage/request',{category:picker.category||''});state.movePicker=null;showFolderAccessWaiting(request);}catch(error){if(state.movePicker===picker)$('#move-error').textContent=error.message;}finally{state.fileWorking=false;control.disabled=false;}return;}
  if(action==='storage-default')return showStorageFolder();
  if(action==='storage-category')return showStorageFolder(control.dataset.category);
  if(action==='storage-clear'){await api('settings/storage',{action:'clear',category:control.dataset.category});await loadStorageSettings();return;}
  if(action==='move-roots')return loadMoveFolders(null);
  if(action==='move-up')return loadMoveFolders(state.movePicker?.listing?.parent);
  if(action==='move-new-folder') {
    const picker=state.movePicker;if(!picker?.selected||picker.loading||state.fileWorking)return;
    picker.creating=!picker.creating;renderMovePicker();if(picker.creating)$('#new-folder-name').focus();return;
  }
  if(action==='move-create-folder') {
    const picker=state.movePicker;if(!picker?.selected||picker.loading||state.fileWorking)return;
    const control=$('[data-action="move-create-folder"]');control.disabled=true;
    const parent=picker.selected;
    try{const folder=await api('folders/create',{parent,name:$('#new-folder-name').value});if(state.movePicker===picker){await loadMoveFolders(parent,picker);if(state.movePicker===picker&&!picker.error)await loadMoveFolders(folder.id,picker);picker.creating=false;renderMovePicker();}}
    catch(error){if(state.movePicker===picker){$('#move-error').textContent=error.message;control.disabled=false;}}return;
  }
  if(action==='confirm-file-move') {
    const picker=state.movePicker;if(!picker?.selected||picker.loading||state.fileWorking)return;
    if(picker.purpose==='storage'){state.fileWorking=true;control.disabled=true;try{await api('settings/storage',{action:picker.category?'category':'default',category:picker.category,folder:picker.selected});state.movePicker=null;$('#modal').close();await loadStorageSettings();toast('Download folder updated');}catch(error){if(state.movePicker===picker)$('#move-error').textContent=error.message;}finally{state.fileWorking=false;control.disabled=false;}return;}
    state.fileWorking=true;$('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=true);$('#move-error').textContent='Moving files on your phone…';
    try{const result=await api('files/move',{ids:picker.files.map(file=>file.id),folder:picker.selected});const errors=result.results.filter(row=>!row.moved).map(row=>row.error);if(errors.length)throw new Error([...new Set(errors)].join(' · '));state.movePicker=null;state.fileSelected.clear();state.fileMenu=null;$('#modal').close();const warnings=result.results.map(row=>row.warning).filter(Boolean);toast(warnings.length?[...new Set(warnings)].join(' · '):'Files moved on your phone');await refresh();}
    catch(error){if(state.movePicker===picker){$('#move-error').textContent=error.message;$('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=false);await refresh();}}
    finally{state.fileWorking=false;}return;
  }
  if(action==='files-root'){state.fileFolder=null;state.fileSelected.clear();state.query='';return render();}
  if(action==='delete-selected-files')return showFileDelete(selectedFiles());
  if(action==='confirm-file-rename'||action==='confirm-file-delete') {
    if(state.fileWorking)return;state.fileWorking=true;$('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=true);
    try {
      if(action==='confirm-file-rename'){await api(state.fileRename.downloadRename?'downloads/rename':'files/rename',{id:state.fileRename.id,name:$('#file-new-name').value});toast('File renamed on your phone');}
      else{const result=await api('files/delete',{ids:state.fileDelete.map(file=>file.id),confirmed:true});const errors=result.results.filter(row=>!row.deleted).map(row=>row.error);if(errors.length)throw new Error([...new Set(errors)].join(' · '));toast('Files deleted from your phone');}
      state.fileSelected.clear();state.fileMenu=null;$('#modal').close();await refresh();
    }catch(error){$('#file-operation-error').textContent=error.message;$('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=false);await refresh();}
    finally{state.fileWorking=false;}return;
  }
  if(action==='detail-rename'){const row=state.snapshot.downloads.find(row=>row.id===state.detail);if(!row)return;return showFileRename({id:row.id,name:row.name,renameAllowed:true,downloadRename:true});}
  if(action==='copy-http-link') {
    const id=state.detail;if(!id)return;
    const result=await api('download/link/'+encodeURIComponent(id));
    if(!authorized())return;
    await copyText(result.url);return toast('Download link copied');
  }
  if(action==='copy-magnet'){const details=state.torrentDetails.get(state.detail);if(!details?.magnet)throw new Error('Torrent information is unavailable');await copyText(details.magnet);return toast('Magnet link copied');}
  if(action==='reload-torrent-details')return loadTorrentDetails(state.detail,true);
  if(action==='choose-torrent'){if(!state.torrent)$('#torrent-upload').click();return;}
  if(action==='resolve-torrent')return resolveTorrent();
  if(action==='cancel-torrent'){await cancelTorrentDraft();return showAdd(state.addMode);}
  if(action==='submit-torrent'){const draft=state.torrent;if(!draft?.selected?.size)return;const control=$('[data-action="submit-torrent"]');control.disabled=true;try{const result=await api('torrent/submit',{id:draft.id,selected:[...draft.selected],seed:$('#torrent-seed').checked,folder:state.addDestination?.id||'',startNow:document.querySelector('[name="add-start"]:checked').value==='now'});state.torrent=null;$('#modal').close();toast(`Added ${result.name} on your phone`);await refresh();}catch(error){$('#torrent-error').textContent=error.message;control.disabled=false;}return;}
  if(action==='confirm-cancel'){const ids=state.pendingCancel;state.pendingCancel=null;$('#modal').close();return command('cancel',ids||[],true);}
  if(action==='request-access') {
    if(state.approval||state.requestingAccess)return;
    state.requestingAccess=true;
    const control=$('[data-action="request-access"]');if(control)control.disabled=true;
    try {
      const request=await api('request',{browser:browserName()});state.approval=request;
      const status=$('#pair-status');if(status)status.textContent='Waiting for approval on your phone…';
    } finally {
      state.requestingAccess=false;
      if(!state.approval){const current=$('[data-action="request-access"]');if(current)current.disabled=false;}
    }
    return;
  }
  if(action==='show-code') return showModal('Pair with your phone','<label for="pair-code">One-time pairing code</label><input id="pair-code" type="text" inputmode="numeric" maxlength="6" autocomplete="one-time-code" placeholder="000000"><p id="pair-code-error" class="error" role="alert"></p><p class="muted">Generate the code in SDM Settings → Web panel on your phone.</p>',button('Connect','pair',null,'primary'));
  if(action==='pair') {try {const value=await api('pair',{code:$('#pair-code').value.trim(),browser:browserName()});$('#modal').close();return setSession(value.session);}catch(error){$('#pair-code-error').textContent=error.status===401?'The pairing code is incorrect or expired. Generate a new code on your phone.':error.message;}return;}
  if(action==='show-limit')return showLimit(state.snapshot.downloads.find(row=>row.id===state.detail));
  if(action==='show-schedule')return showSchedule(state.snapshot.downloads.find(row=>row.id===state.detail));
  if(action.startsWith('limit-')) {
    $('#limit-unlimited').checked=false;$('#limit-value').disabled=false;$('#limit-unit').disabled=false;
    $('#limit-value').value=action==='limit-100'?'100':action==='limit-300'?'300':'1';
    $('#limit-unit').value=action==='limit-1mb'?'MB/s':'KB/s';updateLimitPresets();return;
  }
  if(action==='apply-limit') {
    try {await api('download/limit',{id:state.detail,unlimited:$('#limit-unlimited').checked,value:$('#limit-value').value,unit:$('#limit-unit').value});$('#modal').close();toast('Speed limit updated on your phone');await refresh();render();}
    catch(error){$('#limit-error').textContent=error.message;}return;
  }
  if(action==='apply-schedule') {
    try {
      const kind=document.querySelector('[name="schedule-kind"]:checked').value;
      let schedule=null;
      if(kind==='ONCE') {
        const hour=Number($('#schedule-hour').value),minute=Number($('#schedule-minute').value),date=$('#schedule-date').value;
        if(!date||!$('#schedule-hour').value.trim()||!$('#schedule-minute').value.trim()||!Number.isInteger(hour)||hour<0||hour>23||!Number.isInteger(minute)||minute<0||minute>59)throw new Error('Choose a valid date and time');
        schedule={kind,startLocal:`${date}T${String(hour).padStart(2,'0')}:${String(minute).padStart(2,'0')}`,zone:state.snapshot.zone};
        if($('#schedule-has-end').checked){if(!$('#schedule-end').value)throw new Error('Choose a stop time');schedule.endLocal=$('#schedule-end').value;}
      }
      if(kind==='DAILY') {
        const minutes=value=>{if(!value)throw new Error('Choose both daily times');const [h,m]=value.split(':').map(Number);return h*60+m;};
        schedule={kind,startMinute:minutes($('#daily-start').value),endMinute:minutes($('#daily-end').value),zone:state.snapshot.zone};
      }
      await api('download/schedule',{id:state.detail,schedule});$('#modal').close();toast('Schedule updated on your phone');await refresh();render();
    }catch(error){$('#schedule-error').textContent=error.message;}return;
  }
  if(action==='refresh') return refresh();
  if(action==='back') {state.detail=null;render();window.scrollTo(0,0);return;}
  if(action==='detail-remove')return showRemoval([state.detail]);
  if(action==='bulk-remove')return showRemoval([...state.selected]);
  if(action==='confirm-remove'){
    if(state.removing||!state.pendingRemoval)return;state.removing=true;const ids=[...state.pendingRemoval];
    $('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=true);
    try{const result=await api('downloads/remove',{ids,deleteFiles:$('#remove-keep-files')?!$('#remove-keep-files').checked:false,confirmed:true});const failed=result.results.filter(row=>!row.removed);result.results.filter(row=>row.removed).forEach(row=>state.selected.delete(row.id));if(failed.length){$('#remove-error').textContent=failed.map(row=>row.error).join(' ');}else{state.pendingRemoval=null;$('#modal').close();toast('Downloads removed on your phone');}await refresh();}
    catch(error){if($('#remove-error'))$('#remove-error').textContent=error.message;}finally{state.removing=false;$('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=false);}return;
  }
  if(action==='clear-download-selection'){state.selected.clear();return render();}
  if(action==='bulk-toggle') {
    const selected=(state.snapshot?.downloads||[]).filter(row=>state.selected.has(row.id));
    const active=selected.filter(pauseAction);
    return active.length?command('pause',active.map(row=>row.id)):command('resume',selected.filter(row=>row.state!=='COMPLETED').map(row=>row.id));
  }
  if(action==='bulk-refresh-links')return showBulkLinkRefresh();
  if(action.startsWith('bulk-')) return command(action.slice(5),[...state.selected]);
  if(action.startsWith('detail-')) return command(action.slice(7),[state.detail]);
  if(action==='add') return showAdd();
  if(action==='add-torrent') return showAdd('torrent');
  if(action==='show-backup-export')return showBackupExport();
  if(action==='show-backup-restore')return showBackupRestore();
  if(action==='choose-backup')return $('#backup-upload').click();
  if(action==='export-backup') {
    if(state.backupWorking)return;state.backupWorking=true;const control=$('[data-action="export-backup"]');control.disabled=true;
    try{const result=await api('backup/export',{downloads:$('#backup-downloads').checked,settings:$('#backup-settings').checked});
      const link=document.createElement('a');link.href=result.download;link.download='';document.body.append(link);link.click();link.remove();$('#modal').close();toast('Backup ready to download');}
    catch(error){if($('#backup-error'))$('#backup-error').textContent=error.message;}
    finally{state.backupWorking=false;if(control.isConnected)control.disabled=false;}return;
  }
  if(action==='restore-backup') {
    if(state.backupWorking||!state.backupReview)return;state.backupWorking=true;const control=$('[data-action="restore-backup"]');control.disabled=true;
    const review=state.backupReview,body={review:review.review,settings:$('#restore-settings')?.checked===true,confirmed:true};
    const signature=JSON.stringify(body);if(review.applySignature!==signature){review.applySignature=signature;review.applyKey=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');}
    try{const result=await api('backup/restore',body,{commandKey:review.applyKey});state.backupReview=null;$('#modal').close();toast(result.warning||`${result.restored} download${result.restored===1?'':'s'} restored · ${result.skipped} skipped`);await refresh();}
    catch(error){if($('#backup-error'))$('#backup-error').textContent=error.message;}
    finally{state.backupWorking=false;if(control.isConnected)control.disabled=false;}return;
  }
  if(action==='check-bulk-links') {
    const bulk=state.bulkLink;if(!bulk||state.linkWorking)return;
    const links=$('#bulk-link-urls').value.split(/\r?\n/).map(value=>value.trim()).filter(Boolean);
    if(!links.length||links.length>100)throw new Error('Enter between 1 and 100 links');
    setBulkLinkBusy(true);$('#bulk-link-error').textContent='';
    try {
      if(bulk.review)await api('download/link/bulk/cancel',{review:bulk.review.review});
      bulk.review=null;bulk.resultMessage='';bulk.targets.clear();bulk.restarts.clear();$('[data-action="apply-bulk-links"]')?.setAttribute('disabled','');$('#bulk-link-review').textContent='Checking links…';
      const review=await api('download/link/bulk/check',{ids:bulk.ids,links});
      if(state.bulkLink!==bulk){await api('download/link/bulk/cancel',{review:review.review});return;}
      bulk.review=review;review.candidates.forEach(row=>{if(row.suggested)bulk.targets.set(row.index,row.suggested);});
      let apply=$('[data-action="apply-bulk-links"]');if(!apply){apply=document.createElement('button');apply.className='primary';apply.dataset.action='apply-bulk-links';apply.textContent='Replace matched links and resume';$('#modal footer').append(apply);}
      renderBulkLinkReview();
    } catch(error){if($('#bulk-link-review'))$('#bulk-link-review').textContent='';if($('#bulk-link-error'))$('#bulk-link-error').textContent=error.message;}
    finally{setBulkLinkBusy(false);}
    return;
  }
  if(action==='apply-bulk-links') {
    const bulk=state.bulkLink;if(!bulk?.review||state.linkWorking)return;
    const mappings=bulkLinkMappings();if(!mappings.length)return;
    const body={review:bulk.review.review,mappings},signature=JSON.stringify(body);
    if(bulk.applySignature!==signature){bulk.applySignature=signature;bulk.applyKey=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');}
    setBulkLinkBusy(true);
    try {
      const result=await api('download/link/bulk/apply',body,{commandKey:bulk.applyKey});
      const successful=new Set(result.results.filter(row=>row.ok).map(row=>row.index));
      bulk.review.candidates=bulk.review.candidates.filter(row=>!successful.has(row.index));
      const successfulIds=new Set(result.results.filter(row=>row.ok).map(row=>row.id));
      bulk.ids=bulk.ids.filter(id=>!successfulIds.has(id));bulk.review.downloads=bulk.review.downloads.filter(row=>!successfulIds.has(row.id));
      for(const candidate of bulk.review.candidates)if(successfulIds.has(bulk.targets.get(candidate.index))){bulk.targets.delete(candidate.index);bulk.restarts.delete(candidate.index);}
      for(const row of result.results)if(row.ok){state.selected.delete(row.id);bulk.targets.delete(row.index);bulk.restarts.delete(row.index);}
      bulk.applySignature=null;
      bulk.resultMessage=result.results.filter(row=>!row.ok||row.warning).map(row=>row.error||row.warning).join(' · ');renderBulkLinkReview();
      if(!bulk.review.candidates.length){await cancelBulkLinkReview();$('#modal').close();toast('Download links replaced');}
      await refresh();
    } catch(error){bulk.resultMessage=error.message;if($('#bulk-link-error'))$('#bulk-link-error').textContent=error.message;}
    finally{setBulkLinkBusy(false);}
    return;
  }
  if(action==='open-download-folder') {
    const row=state.snapshot.downloads.find(item=>item.id===state.detail);if(!row)return;
    const listing=await api('files');
    state.files=listing.files;state.storage=listing.storage;state.fileLocation=listing.location;
    state.page='files';state.detail=null;state.query='';state.fileFilter='All';state.fileMenu=null;state.fileSelected.clear();
    state.fileFolder=row.torrent&&listing.files.some(file=>file.id===row.id)?row.id:null;
    const file=listing.files.find(item=>item.id===row.id);if(file&&!row.torrent)state.fileSelected.add(fileKey(file));
    render();window.scrollTo(0,0);if(!file)toast('Incomplete downloads appear in Files after completion');return;
  }
  if(action==='show-link-refresh')return showLinkRefresh(state.snapshot.downloads.find(row=>row.id===state.detail));
  if(action==='check-link') {
    if(state.linkWorking)return;const id=state.linkDownload;if(!id)return;
    const input=$('#refresh-url'),control=$('[data-action="check-link"]');setSingleLinkBusy(true);$('#link-error').textContent='';
    try {
      if(state.linkReview){await api('download/link/cancel',{review:state.linkReview.review});state.linkReview=null;}
      const review=await api('download/link/check',{id,url:input.value.trim()});state.linkReview=review;
      $('#link-review').innerHTML=`<section class="link-review-result ${review.preserve?'same-file':'different-file'}"><div class="link-result-heading">${icon(review.preserve?'check-circle':'alert-circle')}<div><strong>${review.preserve?'Link is valid':'The new link points to a different file'}</strong><p>${escapeText(review.name)}</p><small>${size(review.downloaded)} downloaded · New size ${size(review.size)}</small></div></div><p class="link-identity-message">${review.preserve?'Same file · Progress will be preserved':'Cannot safely reuse downloaded bytes'}</p>${review.preserve?'':`<label class="switch-row"><span>Delete partial data and restart from zero</span><input id="link-restart" type="checkbox"></label>`}</section>`;
      const footer=$('#modal footer');const apply=document.createElement('button');apply.dataset.action='apply-link';apply.className='primary';apply.textContent='Replace link and resume';apply.disabled=!review.preserve;footer.querySelector('[data-action="apply-link"]')?.remove();footer.append(apply);
    } catch(error){if($('#link-error'))$('#link-error').textContent=error.message;}
    finally{setSingleLinkBusy(false);}
    return;
  }
  if(action==='apply-link') {
    if(state.linkWorking||!state.linkReview)return;const control=$('[data-action="apply-link"]');setSingleLinkBusy(true);
    const body={review:state.linkReview.review,restartConfirmed:$('#link-restart')?.checked===true};
    const signature=JSON.stringify(body);
    if(state.linkReview.applySignature!==signature){state.linkReview.applySignature=signature;state.linkReview.applyKey=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');}
    try{await api('download/link/apply',body,{commandKey:state.linkReview.applyKey});state.linkReview=null;state.linkDownload=null;$('#modal').close();toast('Download link replaced');await refresh();}
    catch(error){if(/Link review expired/i.test(error.message)){state.linkReview=null;if($('#link-review'))$('#link-review').textContent='';$('[data-action="apply-link"]')?.remove();}if($('#link-error'))$('#link-error').textContent=error.message;}
    finally{setSingleLinkBusy(false);}
    return;
  }
  if(action==='verify-checksum') {
    const row=state.snapshot.downloads.find(row=>row.id===state.detail);if(!row||row.torrent)return;
    showModal(`${icon('lock')}Verify checksum`,`<p class="muted">${escapeText(row.name)}</p><label for="checksum-expected">SHA-256 checksum</label><input id="checksum-expected" value="${escapeText(row.referenceSha256||'')}" placeholder="64 hexadecimal characters" maxlength="64" autocomplete="off" spellcheck="false"><p id="checksum-error" class="error" role="alert"></p><section id="checksum-result" hidden aria-live="polite"></section>`,button('Verify','checksum-verify',null,'primary'));
    $('#modal').classList.add('checksum-dialog');$('[data-action="checksum-verify"]').disabled=!/^[a-f0-9]{64}$/i.test($('#checksum-expected').value.trim());return;
  }
  if(action==='checksum-verify') {
    if(state.checksumWorking)return;
    const id=state.detail,expected=$('#checksum-expected').value.trim();
    if(!/^[a-f0-9]{64}$/i.test(expected)){$('#checksum-error').textContent='Enter a 64-character SHA-256 checksum';return;}
    state.checksumWorking=true;$('#checksum-error').textContent='';$('#checksum-result').hidden=true;
    $('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=true);$('[data-action="checksum-verify"]').textContent='Verifying…';
    try {
      const result=await api('download/checksum',{id,expected});
      if(!authorized()||!$('#checksum-result'))return;
      const success=result.status==='MATCH',output=$('#checksum-result');output.hidden=false;output.className=`checksum-result ${success?'success':'error'}`;
      output.innerHTML=`<h3>${icon(success?'check-circle':'close')}${success?'Verification completed':escapeText(result.message)}</h3><p>${success?'The file matches the provided checksum.':result.status==='MISMATCH'?'The file does not match the provided checksum.':escapeText(result.message)}</p>${result.actual?`<dl class="technical-list"><div><dt>Algorithm</dt><dd>SHA-256</dd></div><div><dt>Expected</dt><dd>${escapeText(result.expected)}</dd></div><div><dt>Actual</dt><dd>${escapeText(result.actual)}</dd></div></dl>`:''}`;
    } catch(error){if($('#checksum-error'))$('#checksum-error').textContent=error.message;}
    finally {state.checksumWorking=false;if($('#checksum-expected')){$('#modal').querySelectorAll('button,input').forEach(control=>control.disabled=false);$('[data-action="checksum-verify"]').textContent='Verify';}}
    return;
  }
  if(action==='paste-links')return pasteLinks();
  if(action==='submit-links') {
    const input=$('#links'),output=$('#submit-results'),control=$('[data-action="submit-links"]');
    if(state.httpSubmitting)return;
    const links=input.value.split(/\r?\n/).map(s=>s.trim()).filter(Boolean);
    if(!links.length)throw new Error('Enter at least one link');
    if(links.length>100)throw new Error('Add between 1 and 100 links');
    const body={links,folder:state.addDestination?.id||'',startNow:document.querySelector('[name="add-start"]:checked').value==='now'};
    Object.assign(body,readSubmissionOptions());
    const signature=JSON.stringify(body);
    if(state.httpSubmission?.signature!==signature)state.httpSubmission={signature,key:Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('')};
    state.httpSubmitting=true;control.disabled=true;input.disabled=true;
    const options=Array.from($('#modal').querySelectorAll('[name="add-start"],[data-action^="add-"],.add-submit-options input,.add-submit-options select,.add-submit-options button,[data-action="paste-links"]'));
    options.forEach(option=>option.disabled=true);
    try {
      const result=await api('downloads/add',body,{commandKey:state.httpSubmission.key});
      if(!Array.isArray(result.results)||result.results.length!==links.length)throw new Error('Incomplete response. Retry to retrieve the submission result.');
      state.httpSubmission=null;
      // Remove only confirmed successes; retain failures in their original order for correction.
      input.value=links.filter((_,index)=>result.results[index].accepted!==true).join('\n');
      output.textContent=result.results.map((r,index)=>r.accepted===true?`Added ${r.name}`:`Line ${index+1}: ${r.error||'Could not add this link'}`).join('\n');
      await refresh();
    } catch(error) {
      if(output.isConnected)output.textContent=error.message;
      throw error;
    } finally {
      state.httpSubmitting=false;
      if(control.isConnected){control.disabled=!input.value.trim();input.disabled=false;options.forEach(option=>option.disabled=false);}
    }
    return;
  }
  if(action==='show-reset-settings') {
    showModal('Reset all settings?','<p class="muted">This restores SDM defaults. Your downloads and saved files will not be removed.</p>',button('Reset settings','confirm-reset-settings',null,'danger'),'Keep settings');
    $('#modal').classList.add('settings-reset-dialog','folder-move-dialog');return;
  }
  if(action==='confirm-reset-settings') {
    await api('settings/reset',{confirmed:true});state.settingsDraft={};$('#modal').close();toast('Settings restored to defaults');await refresh();renderSettings();return;
  }
  if(action==='save-settings') {
    if(state.savingSettings)return;
    const settings={...state.settingsDraft};
    if(!Object.keys(settings).length)return toast('No settings changes to save');
    state.savingSettings=true;
    try {
      await api('settings',settings);
      for(const [key,value] of Object.entries(settings))if(state.settingsDraft?.[key]===value)delete state.settingsDraft[key];
      toast('Settings updated on your phone');await refresh();
    } finally {state.savingSettings=false;}

  }
}
document.addEventListener('click',event=>{
  if(state.fileMenu&&!event.target.closest('.file-menu,[data-file-menu]'))closeFileMenu();
  const timeOpen=event.target.closest('[data-time-open]');
  if(timeOpen){openTimeControl(timeOpen.parentElement);return;}
  const timePart=event.target.closest('[data-time-part]');
  if(timePart){timePart.parentElement.querySelectorAll('button').forEach(b=>b.setAttribute('aria-pressed',String(b===timePart)));return;}
  const timeDone=event.target.closest('[data-time-done]');
  if(timeDone){const wrapper=timeDone.closest('.time-control'),picker=wrapper.querySelector('.time-picker');const selected=part=>picker.querySelector(`[data-time-part="${part}"][aria-pressed="true"]`).dataset.timeValue;const hour=Number(selected('Hour'))%12+(selected('Period')==='PM'?12:0),minute=Number(selected('Minute'));const input=wrapper.querySelector('input');input.value=minuteLabel(hour*60+minute);wrapper.querySelector('[data-time-open]').innerHTML=`${String(hour%12||12).padStart(2,'0')}:${String(minute).padStart(2,'0')} ${hour>=12?'PM':'AM'}${icon('clock')}`;input.dispatchEvent(new Event('change',{bubbles:true}));picker.remove();wrapper.querySelector('[data-time-open]').setAttribute('aria-expanded','false');wrapper.querySelector('[data-time-open]').focus({preventScroll:true});return;}
  if(!event.target.closest('.time-control'))document.querySelectorAll('.time-picker').forEach(p=>{p.parentElement.querySelector('[data-time-open]').setAttribute('aria-expanded','false');p.remove();});
  const step=event.target.closest('[data-number-step]');
  if(step){const input=step.parentElement.querySelector('input');const value=Number(input.value);input.value=String(Math.min(Number(input.max),Math.max(Number(input.min),(Number.isFinite(value)?value:Number(input.min))+Number(step.dataset.numberStep))));input.dispatchEvent(new Event('change',{bubbles:true}));return;}
  const moveSelect=event.target.closest('[data-move-select]');
  if(moveSelect){const picker=state.movePicker;if(!picker||picker.loading||state.fileWorking)return;picker.selected=moveSelect.dataset.moveSelect;renderMovePicker();return;}
  const moveCollapse=event.target.closest('[data-move-collapse]');
  if(moveCollapse){const picker=state.movePicker;if(!picker||picker.loading||state.fileWorking)return;picker.expandedFolders.delete(moveCollapse.dataset.moveCollapse);renderMovePicker();return;}
  const moveFolder=event.target.closest('[data-move-folder]');if(moveFolder)return loadMoveFolders(moveFolder.dataset.moveFolder);
  const filePhone=event.target.closest('[data-file-phone]');
  if(filePhone){const file=findFile(filePhone.dataset.filePhone);if(!file)return;filePhone.disabled=true;api('files/phone',{id:file.id,index:String(file.index),action:filePhone.dataset.phoneAction}).then(result=>toast(result.message)).catch(error=>toast(error.message)).finally(()=>{if(filePhone.isConnected)filePhone.disabled=false;});return;}
  const fileMove=event.target.closest('[data-file-move]');if(fileMove){showFileMove([findFile(fileMove.dataset.fileMove)].filter(Boolean)).catch(error=>toast(error.message));return;}
  const fileFolder=event.target.closest('[data-file-folder]');if(fileFolder){state.fileFolder=fileFolder.dataset.fileFolder;state.query='';state.fileSelected.clear();state.fileMenu=null;return render();}
  const fileView=event.target.closest('[data-file-view]');if(fileView){state.fileView=fileView.dataset.fileView;return render();}
  const fileMenu=event.target.closest('[data-file-menu]');if(fileMenu){state.fileMenu=state.fileMenu===fileMenu.dataset.fileMenu?null:fileMenu.dataset.fileMenu;state.fileMenuFromBulk=fileMenu.classList.contains('file-bulk-more');return render();}
  const destinationOpen=event.target.closest('[data-destination-open]');if(destinationOpen)return loadDestinationFolders(destinationOpen.dataset.destinationOpen);
  const destinationSelect=event.target.closest('[data-destination-select]');if(destinationSelect){const folder=state.destinationListing?.folders.find(folder=>folder.id===destinationSelect.dataset.destinationSelect);if(folder)selectDestination(folder.id,folder.name);return;}
  const settingsTab=event.target.closest('[data-settings-tab]');if(settingsTab)return selectSettingsTab(settingsTab.dataset.settingsTab);
  const revokeBrowser=event.target.closest('[data-revoke-browser]');if(revokeBrowser)return showBrowserRevocation(revokeBrowser.dataset.revokeBrowser);
  const fileRename=event.target.closest('[data-file-rename]');if(fileRename)return showFileRename(findFile(fileRename.dataset.fileRename));
  const fileDelete=event.target.closest('[data-file-delete]');if(fileDelete)return showFileDelete([findFile(fileDelete.dataset.fileDelete)].filter(Boolean));
  const filePreview=event.target.closest('[data-file-preview]');if(filePreview)return showFilePreview(findFile(filePreview.dataset.filePreview));
  const folder=event.target.closest('[data-torrent-collapse]');if(folder){const draft=state.torrent;draft.closedFolders??=new Set();const key=folder.dataset.torrentCollapse;draft.closedFolders.has(key)?draft.closedFolders.delete(key):draft.closedFolders.add(key);$('.torrent-files tbody').innerHTML=torrentFileRows(draft);return updateTorrentSelected();}
  const torrentTab=event.target.closest('[data-torrent-tab]');if(torrentTab){state.torrentTab=torrentTab.dataset.torrentTab;return render();}
  const page=event.target.closest('[data-page]');if(page){state.page=page.dataset.page;state.detail=null;state.query='';render();window.scrollTo(0,0);return refresh();}
  const tab=event.target.closest('[data-tab]');if(tab){state.tab=tab.dataset.tab;return render();}
  const detail=event.target.closest('[data-details]');if(detail){openDetails(detail.dataset.details);return;}
  const commandButton=event.target.closest('[data-command]');if(commandButton)return command(commandButton.dataset.command,[commandButton.dataset.id]);
  const target=event.target.closest('[data-action]');if(target&&!target.disabled)act(target.dataset.action,target).catch(error=>toast(error.message));
});
function rememberSetting(input) {
  state.settingsDraft??={};
  state.settingsDraft[input.dataset.setting]=input.type==='checkbox'?input.checked:input.hasAttribute('data-time-minute')?(()=>{const [h,m]=input.value.split(':').map(Number);return h*60+m;})():input.dataset.setting==='theme'?input.value:Number(input.value);
}
document.addEventListener('change',async event=>{
  const input=event.target;
  if(input.dataset.bulkLinkTarget!==undefined){const bulk=state.bulkLink;if(!bulk||state.linkWorking)return;const index=Number(input.dataset.bulkLinkTarget);bulk.targets.set(index,input.value);bulk.restarts.delete(index);renderBulkLinkReview();return;}
  if(input.dataset.bulkLinkRestart!==undefined){const bulk=state.bulkLink;if(!bulk||state.linkWorking)return;const index=Number(input.dataset.bulkLinkRestart);input.checked?bulk.restarts.add(index):bulk.restarts.delete(index);renderBulkLinkReview();return;}

  if(input.dataset.setting){rememberSetting(input);return;}
  if(input.id==='storage-categories-enabled'){try{await api('settings/storage',{action:'enabled',enabled:input.checked});}catch(error){input.checked=!input.checked;$('#storage-error').textContent=error.message;}return;}
  if(input.name==='move-folder'){
    const picker=state.movePicker;if(!picker||picker.loading||state.fileWorking)return;
    if(input.value!==picker.listing?.current&&!picker.listing?.folders.some(folder=>folder.id===input.value))return;
    picker.selected=input.value;$('[data-action="confirm-file-move"]').disabled=false;return;
  }
  if(input.dataset.fileSelect){input.checked?state.fileSelected.add(input.dataset.fileSelect):state.fileSelected.delete(input.dataset.fileSelect);return render();}
  if(input.id==='file-select-all'){visibleFiles().forEach(file=>input.checked?state.fileSelected.add(fileKey(file)):state.fileSelected.delete(fileKey(file)));return render();}
  if(input.id==='remove-keep-files'){const control=$('[data-action="confirm-remove"]');control.textContent=input.checked?'Remove only':'Remove and delete files';control.className=input.checked?'primary':'danger';return;}
  if(input.id==='torrent-detail-seed'){const id=state.detail,seed=input.checked;input.disabled=true;api('torrent/seed',{id,seed}).then(()=>loadTorrentDetails(id,true)).catch(error=>{input.checked=!seed;input.disabled=false;toast(error.message);});return;}
  if(input.id==='backup-downloads'||input.id==='backup-settings'){$('[data-action="export-backup"]').disabled=state.backupWorking||!($('#backup-downloads').checked||$('#backup-settings').checked);return;}
  if(input.id==='backup-upload'){if(input.files[0]){const file=input.files[0];input.value='';reviewBackup(file);}return;}
  if(input.id==='link-restart'){$('[data-action="apply-link"]').disabled=!input.checked;return;}
  if(input.id==='refresh-url'&&state.linkReview){const review=state.linkReview;state.linkReview=null;$('#link-review').innerHTML='';$('[data-action="apply-link"]')?.remove();api('download/link/cancel',{review:review.review}).catch(error=>toast(error.message));return;}
  if(input.id==='links'){$('[data-action="submit-links"]').disabled=!input.value.trim();return;}
  if(input.id==='torrent-upload'){if(input.files[0]){const file=input.files[0];input.value='';resolveTorrent(file);}return;}
  if(input.dataset.torrentGroup){for(const file of state.torrent.groups.get(input.dataset.torrentGroup).files){input.checked?state.torrent.selected.add(file.index):state.torrent.selected.delete(file.index);}$('#modal').querySelectorAll('[data-torrent-select]').forEach(control=>control.checked=state.torrent.selected.has(Number(control.dataset.torrentSelect)));return updateTorrentSelected();}
  if(input.dataset.torrentSelect){const index=Number(input.dataset.torrentSelect);input.checked?state.torrent.selected.add(index):state.torrent.selected.delete(index);return updateTorrentSelected();}
  if(input.id==='torrent-select-all'){state.torrent.selected=input.checked?new Set(state.torrent.files.map(file=>file.index)):new Set();$('#modal').querySelectorAll('[data-torrent-select]').forEach(control=>control.checked=input.checked);return updateTorrentSelected();}
  if(['schedule-date','schedule-hour','schedule-minute','daily-start','daily-end'].includes(input.id)){updateScheduleSummary();return;}
  if(input.id==='add-schedule-kind'){$('#add-schedule-once').hidden=input.value!=='ONCE';$('#add-schedule-daily').hidden=input.value!=='DAILY';return;}
  if(input.name==='schedule-kind'){$('.schedule-once').hidden=input.value!=='ONCE';$('.schedule-daily').hidden=input.value!=='DAILY';updateScheduleSummary();return;}
  if(input.id==='schedule-has-end'){$('#schedule-end').hidden=!input.checked;return;}
  if(input.id==='limit-unlimited'){$('#limit-value').disabled=input.checked;$('#limit-unit').disabled=input.checked;updateLimitPresets();return;}
  if(input.id==='limit-value'||input.id==='limit-unit'){updateLimitPresets();return;}
  if(input.id==='file-filter'){state.fileFilter=input.value;return render();}
  if(input.id==='file-sort'){state.fileSort=input.value;return render();}
  if(input.dataset.select){input.checked?state.selected.add(input.dataset.select):state.selected.delete(input.dataset.select);return render();}
  if(input.id==='select-all'){rows().forEach(row=>input.checked?state.selected.add(row.id):state.selected.delete(row.id));render();}
});
document.addEventListener('input',event=>{if(event.target.id==='checksum-expected'){$('[data-action="checksum-verify"]').disabled=!/^[a-f0-9]{64}$/i.test(event.target.value.trim());$('#checksum-error').textContent='';$('#checksum-result').hidden=true;return;}if(['schedule-date','schedule-hour','schedule-minute','daily-start','daily-end'].includes(event.target.id)){updateScheduleSummary();return;}if(event.target.id==='limit-value'){updateLimitPresets();return;}if(event.target.id==='refresh-url'){invalidateSingleLinkReview();return;}if(event.target.id==='bulk-link-urls'){invalidateBulkLinkReview();return;}if(event.target.id==='links'){const review=$('#http-review');if(review)review.textContent='';scheduleLinkReview();return;}if(event.target.dataset.setting){rememberSetting(event.target);return;}if(event.target.id==='storage-folder-name'){const name=event.target.value.trim();$('[data-action="storage-rename-confirm"]').disabled=!name||name===state.storageSettings?.name;$('#storage-rename-error').textContent='';return;}if(event.target.id==='search'){state.query=event.target.value;const start=event.target.selectionStart;render();$('#search').focus();$('#search').setSelectionRange?.(start,start);}});
document.addEventListener('keydown',event=>{if(event.target.matches('[data-details]')&&['Enter',' '].includes(event.key)){event.preventDefault();openDetails(event.target.dataset.details);}});
let dragId=null;
function queueDragIds() {
  if(!dragId||state.offline||state.page!=='downloads'||state.tab!=='queue'||!state.snapshot?.downloads.some(row=>row.id===dragId&&row.state!=='COMPLETED'))return [];
  const selected=state.selected.has(dragId)?state.selected:new Set([dragId]);
  return (state.snapshot.downloads||[]).filter(row=>selected.has(row.id)&&row.state!=='COMPLETED').sort((a,b)=>b.priority-a.priority||a.order-b.order||(a.createdAt||0)-(b.createdAt||0)||a.id.localeCompare(b.id)).map(row=>row.id);
}
function clearQueueDrag() {
  dragId=null;
  document.querySelectorAll('.queue-dragging,.queue-drop-target,.queue-drop-top.active').forEach(node=>node.classList.remove('queue-dragging','queue-drop-target','active'));
}
document.addEventListener('dragstart',event=>{
  const row=event.target.closest('.downloads-table tr[data-id]');
  if(!row||event.target.closest('button,input')||state.offline||state.tab!=='queue'){event.preventDefault();return;}
  dragId=row.dataset.id;
  if(!queueDragIds().length){clearQueueDrag();event.preventDefault();return;}
  event.dataTransfer.effectAllowed='move';event.dataTransfer.setData('text/plain',dragId);
  row.classList.add('queue-dragging');
});
document.addEventListener('dragover',event=>{
  const ids=queueDragIds(),row=event.target.closest('.downloads-table tr[data-id]'),top=event.target.closest('[data-queue-drop-top]');
  if(!ids.length||(!top&&(!row||ids.includes(row.dataset.id))))return;
  event.preventDefault();event.dataTransfer.dropEffect='move';
  document.querySelectorAll('.queue-drop-target,.queue-drop-top.active').forEach(node=>node.classList.remove('queue-drop-target','active'));
  (top||row).classList.add(top?'active':'queue-drop-target');
});
document.addEventListener('drop',async event=>{
  const ids=queueDragIds(),top=event.target.closest('[data-queue-drop-top]');
  const target=top?rows().find(row=>!ids.includes(row.id)&&row.state!=='COMPLETED')?.id:event.target.closest('.downloads-table tr[data-id]')?.dataset.id;
  if(!ids.length||!target||ids.includes(target)){clearQueueDrag();return;}
  event.preventDefault();clearQueueDrag();
  try{await api('queue/reorder',{ids,target,after:false});await refresh();}catch(error){toast(error.message);}
  render();
});
document.addEventListener('dragend',()=>{if(dragId){clearQueueDrag();render();}});
let checkingApproval=false;
async function pollApproval() {
  if(checkingApproval||!state.approval)return;
  checkingApproval=true;
  const pending=state.approval;
  try {
    const result=await api('approval',{id:pending.id,ticket:pending.ticket},{signal:AbortSignal.timeout(8000)});
    if(state.approval!==pending)return;
    if(result.session)setSession(result.session);
    else if($('#pair-status'))$('#pair-status').textContent='Waiting for approval on your phone…';
  }catch(error){
    if(state.approval!==pending)return;
    const terminal=error.status===401||error.status===429;
    if(terminal){state.approval=null;const button=$('[data-action="request-access"]');if(button)button.disabled=false;}
    const status=$('#pair-status');
    if(status)status.textContent=error.status===401?'Request expired or was declined. Request access again.':terminal?error.message:'Connection lost. Waiting to reconnect to your phone…';
  }finally{checkingApproval=false;}
}
(async()=>{try{const info=await api('info',null,{signal:AbortSignal.timeout(8000)});state.device=info.device;state.cookieSession=info.authenticated;}catch(error){toast('Could not reach your phone');}if(authorized()){await refresh();const id=sessionStorage.getItem('sdm-folder-request');if(id)showFolderAccessWaiting({id});}else renderPairing();})();
setInterval(async()=>{if(state.approval)await pollApproval();else await refresh();},1000);

$('#modal').addEventListener('cancel',event=>{if(state.folderRequest){event.preventDefault();act('close-modal').catch(error=>toast(error.message));return;}if(state.removing||state.fileWorking||state.linkWorking||state.backupWorking||state.checksumWorking){event.preventDefault();return;}cancelBackupReview();cancelLinkReview();cancelBulkLinkReview();state.pendingRemoval=null;state.movePicker=null;cancelTorrentDraft();});
setInterval(pollTorrent,1000);
document.addEventListener('dragover',event=>{if(event.target.closest('.torrent-drop'))event.preventDefault();});
document.addEventListener('drop',event=>{if(event.target.closest('.torrent-drop')){event.preventDefault();if(event.dataTransfer.files.length===1)resolveTorrent(event.dataTransfer.files[0]);else toast('Choose one torrent file');}});

document.addEventListener('click',event=>{if(event.target.closest('.torrent-drop')&&!event.target.closest('button,input')&&!state.torrent)$('#torrent-upload').click();});

document.addEventListener('keydown',event=>{
  const picker=event.target.closest('.time-picker');if(!picker)return;
  if(event.key==='Escape'){event.preventDefault();event.stopPropagation();const button=picker.parentElement.querySelector('[data-time-open]');picker.remove();button.setAttribute('aria-expanded','false');button.focus({preventScroll:true});return;}
  const option=event.target.closest('[data-time-part]');if(!option)return;
  if(!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;
  event.preventDefault();const options=Array.from(option.parentElement.querySelectorAll('button'));const index=options.indexOf(option);
  const next=event.key==='Home'?0:event.key==='End'?options.length-1:Math.max(0,Math.min(options.length-1,index+(event.key==='ArrowDown'?1:-1)));
  options.forEach((button,i)=>button.setAttribute('aria-pressed',String(i===next)));options[next].focus({preventScroll:true});options[next].parentElement.scrollTop=options[next].offsetTop-options[next].parentElement.offsetTop;
},true);

setInterval(()=>{if(authorized()&&state.page==='settings'&&state.settingsTab==='Storage'&&!$('#modal').open&&!state.fileWorking)loadStorageSettings();},5000);

setInterval(pollFolderAccess,1000);

document.addEventListener('toggle',event=>{const id=event.target.dataset?.httpTechnical;if(id&&event.target.isConnected){state.httpTechnicalOpen??={};state.httpTechnicalOpen[id]=event.target.open;}},true);
