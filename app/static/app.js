const $=id=>document.getElementById(id), canvas=$('stage'), ctx=canvas.getContext('2d');
const projector=new URLSearchParams(location.search).has('projector');
if(projector) document.body.classList.add('projector');
let prefs={game:'rings',color:'#62ffe0',radius:40,simulate:true,targets:false};
try{prefs={...prefs,...JSON.parse(localStorage.getItem('projectorgames')||'{}')}}catch{}
let contacts=[], pointers=new Map(), points=[], size=null, previewReady=false, bubbles=[], score=0;
function sync(){for(const id of ['game','color','radius','simulate','targets']){if($(id).type==='checkbox')$(id).checked=!!prefs[id];else $(id).value=prefs[id]}$('mode').textContent=prefs.simulate?'SIMULATION':'DEPTH CAMERA'}
sync();
for(const id of ['game','color','radius','simulate','targets'])$(id).oninput=()=>{prefs[id]=$(id).type==='checkbox'?$(id).checked:$(id).value;localStorage.setItem('projectorgames',JSON.stringify(prefs));sync()};
window.addEventListener('storage',e=>{if(e.key==='projectorgames'){try{prefs={...prefs,...JSON.parse(e.newValue)};sync()}catch{}}});
$('project').onclick=()=>window.open('/?projector=1','projector','popup,width=1280,height=720');
$('fullscreen').onclick=()=>canvas.requestFullscreen().catch(e=>notice(e.message));
canvas.ondblclick=()=>{if(projector)canvas.requestFullscreen().catch(e=>notice(e.message))};
function position(e){const r=canvas.getBoundingClientRect();return{x:(e.clientX-r.left)/r.width,y:(e.clientY-r.top)/r.height}}
canvas.style.touchAction='none';
canvas.onpointermove=e=>{if(e.pointerType==='mouse'||e.buttons)pointers.set(e.pointerId,position(e))};
canvas.onpointerdown=e=>{canvas.setPointerCapture(e.pointerId);pointers.set(e.pointerId,position(e))};
for(const name of ['pointerleave','pointerup','pointercancel'])canvas.addEventListener(name,e=>pointers.delete(e.pointerId));
function notice(s){$('notice').textContent=s}
async function api(path,body){const r=await fetch('/api/'+path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw Error(typeof data.detail==='string'?data.detail:JSON.stringify(data.detail));return data}
let depthSize=null, frozen=false, aligning=false, colorPairs=[], depthPairs=[], depthReady=false;
$('depth').onload=()=>{previewReady=true;$('depth').style.display='block';$('noCamera').style.display='none';$('preview').style.aspectRatio=$('depth').naturalWidth+'/'+$('depth').naturalHeight};
$('depth').onerror=()=>{previewReady=false;$('depth').style.display='none';$('noCamera').style.display='block';$('noCamera').textContent='Color unavailable. Check camera index and close other camera apps.'};
$('depthImage').onload=()=>{depthReady=true};
$('depthImage').onerror=()=>{depthReady=false};
function imagePoint(e,element,dimensions){const r=element.getBoundingClientRect();return [(e.clientX-r.left)/r.width*dimensions[0],(e.clientY-r.top)/r.height*dimensions[1]]}
$('preview').onclick=e=>{if(!previewReady||!size)return;if(aligning){if(!frozen)return notice('Freeze both images before selecting matching points.');if(colorPairs.length>=4||colorPairs.length>depthPairs.length)return notice('Select the matching depth point below.');colorPairs.push(imagePoint(e,$('preview'),size));pairStatus();return}if(points.length>=4)return;points.push(imagePoint(e,$('preview'),size));markers()};
$('depthPreview').onclick=e=>{if(!aligning||!frozen||!depthReady||!depthSize||depthPairs.length>=colorPairs.length)return;depthPairs.push(imagePoint(e,$('depthPreview'),depthSize));pairStatus();if(depthPairs.length===4){aligning=false;notice('Four pairs selected. Save mapping to apply alignment.')}};
function pairStatus(){$('alignStatus').textContent=`Color: ${colorPairs.length}/4 · Depth: ${depthPairs.length}/4. `+(colorPairs.length>depthPairs.length?'Click matching fingertip in depth.':'Resume, move hand, then freeze for the next pair.');drawMarkers($('depthMarkers'),depthPairs,depthSize);markers()}
function drawMarkers(container,list,dimensions){container.replaceChildren();if(!dimensions)return;list.forEach((p,i)=>{const el=document.createElement('span');el.className='marker';el.textContent=i+1;el.style.left=100*p[0]/dimensions[0]+'%';el.style.top=100*p[1]/dimensions[1]+'%';container.append(el)})}
function markers(){drawMarkers($('markers'),aligning?colorPairs:points,size);$('corners').textContent=points.length+' / 4 projected corners selected'}
$('align').onclick=()=>{aligning=true;colorPairs=[];depthPairs=[];pairStatus();notice('Hold your hand near the wall, freeze, then click the same fingertip in color and depth.')};
$('freeze').onclick=()=>{frozen=!frozen;$('freeze').textContent=frozen?'Resume images':'Freeze images'};
$('reset').onclick=()=>{points=[];aligning=false;markers()};
$('save').onclick=async()=>{try{if(points.length!==4)throw Error('Select all four projected corners in the color image.');if(colorPairs.length!==4||depthPairs.length!==4)throw Error('Complete the four color/depth alignment pairs first.');await api('calibration',{space:'color',points,color_points:colorPairs,depth_points:depthPairs,color_size:size,depth_size:depthSize,near_mm:Number($('near').value),far_mm:Number($('far').value),min_area:Number($('area').value)});notice('Color mapping and depth alignment saved. Clear the surface and capture its depth.')}catch(e){notice(e.message)}};
$('capture').onclick=async()=>{try{await api('background',{});notice('Empty surface captured. Disable simulation to use depth contacts.')}catch(e){notice(e.message)}};
let initialized=false;
async function pollStatus(){try{const s=await api('status');const newSize=s.color_size;depthSize=s.size;
if(size&&newSize&&String(size)!==String(newSize)){points=[];colorPairs=[];depthPairs=[];notice('Color resolution changed. Recalibrate and align again.')}size=newSize;
$('status').textContent=s.camera?`Depth connected · ${s.calibrated?'mapped':'needs mapping'} · ${s.background?'surface captured':'capture surface'}`:s.message;
$('colorStatus').textContent=s.color_message||(s.aligned?'Color connected · alignment saved':'Color connected · needs depth/color alignment');
if(!initialized){if(s.settings.space==='color'){points=s.settings.points||[];colorPairs=s.settings.color_points||[];depthPairs=s.settings.depth_points||[]}else if(s.calibrated)notice('Existing depth calibration must be replaced with color calibration.');for(const [id,key] of [['near','near_mm'],['far','far_mm'],['area','min_area']])$(id).value=s.settings[key];initialized=true;markers();pairStatus()}
if(!frozen&&!projector){$('depth').src='/api/color.jpg?t='+Date.now();$('depthImage').src='/api/depth.jpg?t='+Date.now();if(depthSize)$('depthPreview').style.aspectRatio=depthSize[0]+'/'+depthSize[1]}
}catch(e){$('status').textContent='Server unavailable';contacts=[]}setTimeout(pollStatus,500)}pollStatus();
async function poll(){try{contacts=(await api('contacts')).contacts}catch{contacts=[]}setTimeout(poll,50)}poll();
for(let i=0;i<12;i++)bubbles.push({x:.08+Math.random()*.84,y:.08+Math.random()*.84,r:.025+Math.random()*.025});
function draw(t){const d=Math.min(devicePixelRatio||1,2),r=canvas.getBoundingClientRect(),w=Math.round(r.width*d),h=Math.round(r.height*d);if(canvas.width!==w||canvas.height!==h){canvas.width=w;canvas.height=h}ctx.fillStyle='#060a12';ctx.fillRect(0,0,w,h);const hits=prefs.simulate?[...pointers.values()]:contacts;
if(prefs.game==='bubbles'){for(const b of bubbles){if(hits.some(p=>Math.hypot((p.x-b.x)*w,(p.y-b.y)*h)<b.r*w+Number(prefs.radius)*d)){b.x=.08+Math.random()*.84;b.y=.08+Math.random()*.84;score++}ctx.beginPath();ctx.arc(b.x*w,b.y*h,b.r*w,0,Math.PI*2);ctx.fillStyle='#153d48';ctx.fill();ctx.strokeStyle=prefs.color;ctx.lineWidth=2*d;ctx.stroke()}ctx.fillStyle='#aebbd0';ctx.font=18*d+'px system-ui';ctx.fillText('Bubbles popped: '+score,24*d,36*d)}
for(const p of hits){ctx.beginPath();ctx.arc(p.x*w,p.y*h,Number(prefs.radius)*d,0,Math.PI*2);ctx.strokeStyle=prefs.color;ctx.shadowColor=prefs.color;ctx.shadowBlur=20*d;ctx.lineWidth=4*d;ctx.stroke();ctx.shadowBlur=0;ctx.beginPath();ctx.arc(p.x*w,p.y*h,4*d,0,Math.PI*2);ctx.fillStyle='#fff';ctx.fill()}
if(!hits.length&&prefs.game==='rings'&&!projector){ctx.fillStyle='#6e809b';ctx.textAlign='center';ctx.font=18*d+'px system-ui';ctx.fillText(prefs.simulate?'Move here to test interaction':'Waiting for surface contacts',w/2,h/2);ctx.textAlign='left'}
if(prefs.targets){[[0,0],[1,0],[1,1],[0,1]].forEach(([x,y],i)=>{ctx.fillStyle='#fff';ctx.fillRect(x*w-12*d,y*h-12*d,24*d,24*d);ctx.fillStyle='#ffdc93';ctx.font=28*d+'px system-ui';ctx.fillText(String(i+1),x?(w-40*d):20*d,y?h-25*d:42*d)})}requestAnimationFrame(draw)}requestAnimationFrame(draw);
