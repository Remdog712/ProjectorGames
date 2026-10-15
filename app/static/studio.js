const $=id=>document.getElementById(id),projector=new URLSearchParams(location.search).has('projector');
if(projector)document.body.classList.add('projector');
const renderer=new StageRenderer($('gameCanvas'));
$('renderer').textContent=renderer.label;
let state=null,packet={contacts:[],regions:[]},lastReceived=0,socket,reconnectTimer,initialized=false;
let frozenId=null,seenCapture=null,reachSelection=false,previewPending=false,lastPreviewKey='',tab='setup';
let prefs={game:'rings',color:'#62ffe0',radius:34,labels:true};
try{const saved=JSON.parse(localStorage.getItem('studioGame')||'{}');Object.keys(prefs).forEach(k=>{if(k in saved)prefs[k]=saved[k]})}catch{}
function syncGame(){for(const k of Object.keys(prefs))if($(k).type==='checkbox')$(k).checked=prefs[k];else $(k).value=prefs[k]}
syncGame();
for(const k of Object.keys(prefs))$(k).oninput=()=>{prefs[k]=$(k).type==='checkbox'?$(k).checked:$(k).value;localStorage.setItem('studioGame',JSON.stringify(prefs))};
window.addEventListener('storage',e=>{if(e.key==='studioGame'){try{prefs={...prefs,...JSON.parse(e.newValue)};syncGame()}catch{}}});
function note(s){$('notice').textContent=s}
async function api(path,body,method='POST'){
 const r=await fetch('/api/'+path,{method,headers:{'Content-Type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})});
 const data=await r.json();if(!r.ok)throw Error(typeof data.detail==='string'?data.detail:JSON.stringify(data.detail));return data;
}
function action(id,fn){$(id).onclick=async()=>{$(id).disabled=true;try{await fn()}catch(e){note(e.message)}finally{$(id).disabled=false}}}
function selectTab(name){tab=name;document.querySelectorAll('[data-panel]').forEach(e=>e.hidden=e.dataset.panel!==name);document.querySelectorAll('[data-tab]').forEach(e=>e.classList.toggle('selected',e.dataset.tab===name));$('previewEnabled').checked=name!=='play'}
document.querySelectorAll('[data-tab]').forEach(e=>e.onclick=()=>selectTab(e.dataset.tab));
$('project').onclick=()=>window.open('/?projector=1','projectorgames-output','popup,width=1280,height=720');
action('fullscreen',()=>document.querySelector('.stage').requestFullscreen());
document.querySelector('.stage').ondblclick=()=>{if(projector)document.querySelector('.stage').requestFullscreen().catch(e=>note(e.message))};
const tuningFields={gap:'surface_gap_mm',reach:'reach_mm',minArea:'min_area',stable:'stable_frames',smoothing:'smoothing',median:'median',opening:'opening',closing:'closing',width:'processing_width',fps:'fps'};
function showTuning(t){for(const [id,key]of Object.entries(tuningFields))$(id).value=t[key];updateUnits()}
function updateUnits(){$('gapValue').textContent=(Number($('gap').value)/10).toFixed(1);$('reachValue').textContent=(Number($('reach').value)/10).toFixed(0)}
$('gap').oninput=$('reach').oninput=updateUnits;
function tuning(){const t={...state.tuning};for(const [id,key]of Object.entries(tuningFields))t[key]=Number($(id).value);return t}
action('applyTuning',async()=>{await api('studio/tuning',tuning());note('Tracking settings saved. Inspect active regions while you move.');lastPreviewKey=''});
$('preset').onchange=()=>{
 const presets={balanced:{processing_width:320,stable_frames:2,median:3,opening:1,closing:1,min_area:500,smoothing:.65,surface_gap_mm:25,reach_mm:1500},fast:{processing_width:320,stable_frames:1,median:0,opening:1,closing:0,min_area:400,smoothing:1,surface_gap_mm:25,reach_mm:1500},stable:{processing_width:320,stable_frames:3,median:5,opening:2,closing:2,min_area:1000,smoothing:.4,surface_gap_mm:40,reach_mm:1500},touch:{processing_width:640,stable_frames:2,median:3,opening:1,closing:1,min_area:100,smoothing:.75,surface_gap_mm:15,reach_mm:100}};
 showTuning({...state.tuning,...presets[$('preset').value]});note('Preset loaded. Click Apply & save.');
};
$('showActive').onclick=()=>{$('previewMode').value='active';$('previewEnabled').checked=true;frozenId=null;lastPreviewKey=''};
action('connect',async()=>{
 const result=await api('camera',{enabled:true,runtime:$('runtime').value,color_index:Number($('cameraIndex').value),color_backend:$('backend').value,mirror_color:$('mirrorColor').checked,mirror_depth:$('mirrorDepth').checked});
 frozenId=null;note(result.message);
});
action('retryColor',async()=>{const result=await api('camera/color',{color_index:Number($('cameraIndex').value),color_backend:$('backend').value});frozenId=null;note(result.message)});
action('autoSurface',async()=>{frozenId=null;note('Showing calibration pattern. Keep the surface clear and the projector window visible.');await api('studio/calibrate',{});note('Projector mapped and surface measured. Capture a body pose next.');});
async function capture(kind){frozenId=null;reachSelection=false;await api('capture',{kind,delay:Number($('delay').value)});note(kind==='pose'?'Stand near the surface with one hand higher than the other, feet apart. Hold still, then return after capture.':'Stand at your closest playable position. Return after capture and click your chest once.');}
action('capturePose',()=>capture('pose'));
action('captureReach',()=>capture('range'));
action('autoAlign',async()=>{const r=await api('studio/align',{});note('Automatic alignment saved: '+Math.round(r.alignment.silhouette_overlap*100)+'% overlap. Inspect Registered overlay.');frozenId=null;$('previewMode').value='overlay';lastPreviewKey=''});
$('selectReach').onclick=()=>{if(!frozenId)return note('Capture closest body position first.');reachSelection=true;$('previewMode').value='depth';lastPreviewKey='';note('Click your chest ONCE in the frozen depth image.')};
$('preview').onclick=async e=>{
 if(!reachSelection||!frozenId||$('previewMode').value!=='depth')return;
 const rect=$('preview').getBoundingClientRect(),size=state.capture.depth_size;
 if(!size)return;
 try{const r=await api('studio/reach',{x:(e.clientX-rect.left)/rect.width*size[0],y:(e.clientY-rect.top)/rect.height*size[1],capture_id:frozenId});$('reach').value=r.reach_mm;updateUnits();reachSelection=false;note('Maximum reach saved: '+(r.reach_mm/10).toFixed(1)+' cm from the fitted surface.')}catch(error){note(error.message)}
};
action('live',async()=>{await api('capture',undefined,'DELETE');frozenId=null;reachSelection=false;lastPreviewKey='';selectTab('play');note('Body input active. Camera preview paused for play.')});
action('enablePose',async()=>{const r=await api('studio/pose',{path:$('poseModel').value});note(r.message)});
action('disablePose',async()=>{await api('studio/pose',{path:''});note('Using depth-region tracking.')});
$('previewMode').onchange=()=>{lastPreviewKey=''};
function updateState(s){
 state=s;
 if(!initialized){$('runtime').value=s.camera_settings.runtime;$('cameraIndex').value=s.camera_settings.color_index;$('backend').value=s.camera_settings.color_backend||'auto';$('mirrorColor').checked=s.camera_settings.mirror_color;$('mirrorDepth').checked=s.camera_settings.mirror_depth;showTuning(s.tuning);initialized=true}
 const depth=s.camera.depth_size,color=s.camera.color_size;
 $('connection').textContent=(depth?'Depth connected':s.depth_error)+' · '+(color?'Color connected':s.color_error);
 $('cameraMessage').textContent=s.depth_error+'\n'+s.color_error;
 const stats=s.stats;
 $('metrics').textContent=`${stats.tracking_fps} tracking FPS · ${stats.processing_ms} ms processing\n${stats.frame_age_ms??'—'} ms frame age`;
 $('surfaceResult').textContent=s.surface?`Surface fit: ${s.surface.rms_mm.toFixed(1)} mm RMS · ${Math.round(s.surface.inlier_fraction*100)}% inliers`:'Not measured.';
 $('alignmentResult').textContent=s.alignment?`${Math.round(s.alignment.silhouette_overlap*100)}% silhouette overlap · ${s.alignment.reflection_detected?'reflection corrected':'same orientation'}`:'No automatic body alignment yet.';
 $('quality').textContent=`Projector: ${s.mapping?s.mapping.reprojection_px.toFixed(2)+' px fit error':'not mapped'}. Surface: ${s.surface?s.surface.rms_mm.toFixed(1)+' mm RMS':'not measured'}. Alignment: ${s.alignment?Math.round(s.alignment.silhouette_overlap*100)+'% overlap':'not aligned'}.`;
 $('poseStatus').textContent=s.pose.status+(s.pose.inference_ms?' · '+s.pose.inference_ms+' ms':'');
 $('autoSurface').disabled=s.calibrating;
 if(s.error)note('Tracking error: '+s.error);
 const c=s.capture;
 if(c.phase==='idle'&&frozenId){frozenId=null;lastPreviewKey=''}
 $('captureMessage').textContent=c.phase==='countdown'?`Capture in ${c.remaining}s · hold position`:frozenId?'Frozen capture · return to PC and continue setup':'Live camera';
 if(c.phase==='failed')note(c.error);
 if(c.phase==='ready'&&c.id!==seenCapture){seenCapture=c.id;frozenId=c.id;$('previewMode').value=c.kind==='range'?'depth':'color';lastPreviewKey='';if(c.kind==='range'){reachSelection=true;note('Click your chest once in the frozen depth image.')}else note('Body captured. Click Auto align body—no manual point pairs.');}
 if(projector)drawPattern(s);
}
function connectStream(){
 socket=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/api/live');
 socket.onmessage=e=>{const message=JSON.parse(e.data);packet=message;lastReceived=performance.now();if(message.status)updateState(message.status)};
 socket.onclose=()=>{$('connection').textContent='Disconnected · reconnecting…';packet.contacts=[];clearTimeout(reconnectTimer);reconnectTimer=setTimeout(connectStream,1000)};
 socket.onerror=()=>socket.close();
}
connectStream();
let previewVersion=0;
async function previewLoop(){
 if(!projector&&state&&$('previewEnabled').checked&&document.visibilityState==='visible'&&!previewPending){
  previewPending=true;const kind=$('previewMode').value;
  const key=(frozenId||'live')+kind;
  try{
   if(!frozenId||key!==lastPreviewKey){
    const path=frozenId?'/api/'+(kind==='color'||kind==='overlay'?'color':'depth')+'.jpg?capture_id='+frozenId:'/api/studio/preview/'+kind+'.jpg?t='+Date.now();
    const r=await fetch(path);if(!r.ok)throw Error('Waiting for fresh '+kind+' frame');
    const blob=await r.blob(),url=URL.createObjectURL(blob),version=++previewVersion;
    const image=new Image();image.src=url;await image.decode();
    if(version===previewVersion){const old=$('preview').dataset.url;$('preview').src=url;$('preview').dataset.url=url;$('preview').style.display='block';$('previewHint').hidden=true;if(old)URL.revokeObjectURL(old);lastPreviewKey=key}else URL.revokeObjectURL(url);
   }
  }catch(error){$('previewHint').hidden=false;$('previewHint').textContent=error.message;$('preview').style.display='none'}finally{previewPending=false}
 }
 setTimeout(previewLoop,125);
}
previewLoop();
const markers={};let patternAck=0,lastSize='';
Promise.all([10,11,12,13].map(async id=>{const image=new Image();image.src='/api/studio/marker/'+id+'.png';await image.decode();markers[id]=image})).then(()=>{if(projector)reportProjector()}).catch(e=>note(e.message));
function reportProjector(pattern_id=0){if(!projector)return;const rect=document.querySelector('.stage').getBoundingClientRect();return api('studio/projector',{width:Math.round(rect.width),height:Math.round(rect.height),pattern_id}).catch(e=>note(e.message))}
window.addEventListener('resize',()=>{lastSize='';if(projector)reportProjector(patternAck)});
function drawPattern(s){
 const canvas=$('patternCanvas');canvas.hidden=!s.pattern;
 if(!s.pattern){patternAck=0;return}
 const rect=document.querySelector('.stage').getBoundingClientRect(),w=Math.round(rect.width),h=Math.round(rect.height);
 if(!markers[13])return;
 canvas.width=w;canvas.height=h;const c=canvas.getContext('2d');c.imageSmoothingEnabled=false;c.fillStyle='#fff';c.fillRect(0,0,w,h);
 const side=Math.floor(Math.min(w,h)*.18),pad=Math.floor(Math.min(w,h)*.10),positions=[[pad,pad],[w-pad-side,pad],[w-pad-side,h-pad-side],[pad,h-pad-side]];
 positions.forEach(([x,y],i)=>c.drawImage(markers[10+i],x,y,side,side));
 if(patternAck!==s.pattern_id){patternAck=s.pattern_id;reportProjector(patternAck)}
}
let bubbles=Array.from({length:12},()=>({x:.08+Math.random()*.84,y:.08+Math.random()*.84,r:.025})),renderLast=performance.now(),renderRate=0;
function draw(now){
 const live=now-lastReceived<250,contacts=live?packet.contacts:[];
 const regions=live?packet.regions:[];
 if(prefs.game==='bubbles')for(const b of bubbles)if(contacts.some(p=>Math.hypot(p.x-b.x,p.y-b.y)<.06)){b.x=.08+Math.random()*.84;b.y=.08+Math.random()*.84}
 if(!state?.pattern)renderer.draw(contacts,prefs.color,Number(prefs.radius),prefs.game==='bubbles'?bubbles:[]);
 const elapsed=now-renderLast;renderLast=now;if(elapsed>0)renderRate=.9*renderRate+.1*1000/elapsed;
 $('trackingStatus').textContent=`${regions.length} tracked region${regions.length===1?'':'s'} · ${Math.round(renderRate)} render FPS`;
 const c=state?.capture;
 $('stageText').textContent=c?.phase==='countdown'?`${c.remaining}\n${c.kind==='pose'?'One hand higher · feet apart · hold still':'Stand at your closest position · hold still'}`:prefs.labels&&regions.length?'Bodies '+regions.map(r=>'#'+r.id).join(' · '):'';
 if(projector){const rect=document.querySelector('.stage').getBoundingClientRect(),size=rect.width+':'+rect.height;if(size!==lastSize){lastSize=size;reportProjector(patternAck)}}
 requestAnimationFrame(draw);
}
requestAnimationFrame(draw);window.projectorAppStarted=true;
