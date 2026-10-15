const $ = id => document.getElementById(id);
const canvas = $('stage'), ctx = canvas.getContext('2d');
const projector = new URLSearchParams(location.search).has('projector');
if (projector) document.body.classList.add('projector');
let prefs = {game: 'rings', color: '#62ffe0', radius: 40, targets: false};
try {
  const stored = JSON.parse(localStorage.getItem('projectorgames') || '{}');
  for (const key of Object.keys(prefs)) if (key in stored) prefs[key] = stored[key];
} catch {}
let status = null, contacts = [], points = [], colorPairs = [], depthPairs = [];
let selection = 'corners', frozenId = null, handledCapture = null, imageBusy = false;
let frozenSizes = null, backgroundHandled = null, cameraFormLoaded = false, settingsLoaded = false, history = [];
const frameReady = {color: false, depth: false};
const lastImages = {color: null, depth: null};
const names = ['right hand', 'left hand', 'left foot', 'right foot'];
const cornerNames = ['top-left', 'top-right', 'bottom-right', 'bottom-left'];
let bubbles = Array.from({length: 12}, () => ({x: .08 + Math.random() * .84, y: .08 + Math.random() * .84, r: .025 + Math.random() * .025})), score = 0;

function openStep(id) { $(id).open = true; }
function notice(message) { $('notice').textContent = message; }
async function api(path, body, method) {
  const response = await fetch('/api/' + path, body === undefined && !method ? {} : {
    method: method || 'POST', headers: {'Content-Type': 'application/json'},
    ...(body === undefined ? {} : {body: JSON.stringify(body)})
  });
  const data = await response.json();
  if (!response.ok) throw Error(typeof data.detail === 'string' ? data.detail : JSON.stringify(data.detail));
  return data;
}
function action(id, handler) {
  $(id).onclick = async () => {
    $(id).disabled = true;
    try { await handler(); } catch (error) { notice(error.message); }
    finally { $(id).disabled = false; }
  };
}
function syncPrefs() {
  for (const id of Object.keys(prefs)) {
    if ($(id).type === 'checkbox') $(id).checked = !!prefs[id];
    else $(id).value = prefs[id];
  }
}
syncPrefs();
for (const id of Object.keys(prefs)) $(id).oninput = () => {
  prefs[id] = $(id).type === 'checkbox' ? $(id).checked : $(id).value;
  localStorage.setItem('projectorgames', JSON.stringify(prefs));
};
window.addEventListener('storage', event => {
  if (event.key !== 'projectorgames') return;
  try {
    const stored = JSON.parse(event.newValue || '{}');
    for (const key of Object.keys(prefs)) if (key in stored) prefs[key] = stored[key];
    syncPrefs();
  } catch {}
});
$('project').onclick = () => window.open('/?projector=1', 'projector', 'popup,width=1280,height=720');
action('fullscreen', () => canvas.requestFullscreen());
canvas.ondblclick = () => { if (projector) canvas.requestFullscreen().catch(error => notice(error.message)); };
// Deliberately no mouse/touch handlers on the game: contacts always come from depth.

function sizes() {
  return frozenId && frozenSizes ? frozenSizes : {color: status?.color_size, depth: status?.size};
}
function drawMarkers(kind, list, pair = false) {
  const container = $(kind + 'Markers'), dimensions = sizes()[kind];
  container.replaceChildren();
  if (!dimensions) return;
  list.forEach((point, index) => {
    const el = document.createElement('span');
    el.className = 'marker' + (pair ? ' pair' : '');
    el.textContent = index + 1;
    el.style.left = 100 * point[0] / dimensions[0] + '%';
    el.style.top = 100 * point[1] / dimensions[1] + '%';
    container.append(el);
  });
}
function updateSelection() {
  drawMarkers('color', selection === 'pairs' ? colorPairs : points, selection === 'pairs');
  drawMarkers('depth', selection === 'pairs' ? depthPairs : [], true);
  $('cornerStatus').textContent = points.length + ' / 4 projected corners';
  $('pairStatus').textContent = `Color points: ${colorPairs.length}/4 · Depth points: ${depthPairs.length}/4. ` +
    (depthPairs.length === 4 ? 'Body pairs complete. Save mapping.' : 'Next: ' + names[Math.min(depthPairs.length, 3)] + '.');
  if (selection === 'surface') {
    $('instruction').textContent = 'Click ONE solid wall/floor spot in DEPTH to measure the surface distance.';
    return;
  }
  if (selection === 'chest') {
    $('instruction').textContent = frozenId ? 'Click your chest ONCE in the frozen DEPTH image. This sets the closest playable distance.' : 'Capture body distance after a delay, then select your chest in the frozen depth image.';
    return;
  }
  if (selection === 'corners') $('instruction').textContent = points.length < 4
    ? 'Click the projected ' + cornerNames[points.length] + ' corner in COLOR. You stay at the PC.'
    : 'Projected corners selected. Capture a star pose to align your body with depth.';
  else $('instruction').textContent = !frozenId ? 'Capture a delayed star pose first. No need to touch the wall.'
    : depthPairs.length === 4 ? 'All body pairs selected. Save mapping, then return to live preview.'
    : `Click your ${names[depthPairs.length]} in ${colorPairs.length > depthPairs.length ? 'DEPTH' : 'COLOR'} on the frozen pose.`;
}
function switchPreview() {
  const mode = $('previewMode').value;
  $('colorPanel').hidden = mode === 'depth' || mode === 'active';
  $('depthPanel').hidden = mode === 'color';
  $('previews').classList.toggle('single', mode !== 'both');
  $('depthTitle').textContent = mode === 'active' ? 'Active regions · green pixels are inside your distance bounds' : 'Depth · match your body points here';
}
$('previewMode').onchange = switchPreview;
function showMode(mode) { $('previewMode').value = mode; switchPreview(); }
switchPreview();
for (const kind of ['color', 'depth']) {
  $(kind + 'Frame').onclick = async event => {
    if (!frameReady[kind] || !sizes()[kind]) return;
    const image = $(kind + 'Image');
    const rect = image.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    const point = [(event.clientX - rect.left) / rect.width * sizes()[kind][0], (event.clientY - rect.top) / rect.height * sizes()[kind][1]];
    if (point.some((value, index) => value < 0 || value >= sizes()[kind][index])) return;
    if (selection === 'surface' || selection === 'chest') {
      if (kind !== 'depth' || $('previewMode').value === 'active') return notice('Select this measurement in the plain Depth preview.');
      try {
        const target = selection === 'surface' ? 'surface' : 'body';
        if (target === 'body' && !frozenId) throw Error('Capture your body after a delay first.');
        const result = await api('distance/sample', {target, x: point[0], y: point[1], capture_id: frozenId});
        if (status) status.distance = result.distance;
        updateDistanceReadouts(result.distance);
        notice((target === 'surface' ? 'Surface' : 'Chest') + ' measured at ' + (result.sample_mm/1000).toFixed(3) + ' m from camera. ' + (target === 'surface' ? 'Now select the projected corners and align the cameras.' : 'Playable distance band saved. Return to live preview to play.'));
        if (target === 'surface') openStep('areaStep');
        else showMode('active');
        selection = 'done';
        $('instruction').textContent = target === 'surface' ? 'Surface distance saved. Select projected corners next.' : 'Both distances saved. Green shows the playable depth band.';
      } catch (error) { notice(error.message); }
      return;
    }
    if (selection === 'done') return;
    if (selection === 'corners') {
      if (kind !== 'color') return notice('Projected corners must be selected in the color image.');
      if (points.length >= 4) return notice('Four corners selected. Use Select / reset to change them.');
      points.push(point); history.push('corner');
    } else {
      if (!frozenId) return notice('Capture a delayed star pose before selecting body points.');
      if (kind === 'color' && colorPairs.length === depthPairs.length && colorPairs.length < 4) {
        colorPairs.push(point); history.push('color');
        if ($('previewMode').value === 'color') showMode('depth');
      } else if (kind === 'depth' && depthPairs.length < colorPairs.length) {
        if ($('previewMode').value === 'active') return notice('Switch to Depth to select the matching body point.');
        depthPairs.push(point); history.push('depth');
        if ($('previewMode').value === 'depth') showMode('color');
      } else return notice('Follow the next point instruction above the images.');
    }
    updateSelection();
  };
}
action('connect', async () => {
  const result = await api('camera', {enabled: true, runtime: $('runtime').value, color_index: Number($('cameraIndex').value), color_backend: $('colorBackend').value, mirror_color: $('mirrorColor').checked, mirror_depth: $('mirrorDepth').checked});
  frozenId = null; handledCapture = null; contacts = [];
  settingsLoaded = false; // Reload server invalidation after a device change.
  notice(result.message + ' These settings will reconnect automatically next launch.');
  openStep('emptyStep');
});
action('retryColor', async () => {
  const result = await api('camera/color', {color_index: Number($('cameraIndex').value), color_backend: $('colorBackend').value});
  frozenId = null; handledCapture = null; settingsLoaded = false;
  notice(result.message);
});
action('disconnect', async () => {
  await api('camera', {enabled: false, runtime: $('runtime').value, color_index: Number($('cameraIndex').value), color_backend: $('colorBackend').value, mirror_color: $('mirrorColor').checked, mirror_depth: $('mirrorDepth').checked});
  frozenId = null; contacts = []; notice('Cameras disconnected.');
});
action('applyMirrors', async () => {
  const result = await api('mirrors', {mirror_color: $('mirrorColor').checked, mirror_depth: $('mirrorDepth').checked});
  if (result.changed) {
    frozenId = null; handledCapture = null; frozenSizes = null;
    settingsLoaded = false; contacts = []; points = []; colorPairs = []; depthPairs = [];
    frameReady.color = frameReady.depth = false;
    lastImages.color = lastImages.depth = null;
    openStep('emptyStep'); showMode('both'); selection = 'surface';
  }
  notice(result.message);
});
action('selectSurface', async () => {
  await api('capture', undefined, 'DELETE'); frozenId = null; handledCapture = null; frozenSizes = null;
  selection = 'surface'; showMode('depth'); frameReady.depth = false; updateSelection();
});
$('selectChest').onclick = () => { selection = 'chest'; showMode('depth'); updateSelection(); };
async function captureAfter(kind) {
  const result = await api('capture', {kind, delay: Number($('delay').value)});
  frozenId = null; handledCapture = null; frozenSizes = null;
  if (kind === 'pose') { colorPairs = []; depthPairs = []; history = []; selection = 'pairs'; }
  notice(kind === 'pose' ? 'Hold a star pose until capture finishes, then select the same physical hands/feet in both images.' : 'Stand closer to the camera at your desired upper boundary. Hold still until captured, then return to click your chest once.');
  if (status) status.capture = result;
  updateSelection();
}
action('rangeCapture', () => captureAfter('range'));
action('poseCapture', () => captureAfter('pose'));
action('cancel', async () => {
  await api('capture', undefined, 'DELETE'); frozenId = null; handledCapture = null;
  notice('Capture cancelled.');
});
action('live', async () => {
  await api('capture', undefined, 'DELETE'); frozenId = null; handledCapture = null; frameReady.color = frameReady.depth = false;
  showMode($('previewMode').value); notice('Live preview. Saved body alignment is retained.'); updateSelection();
});
$('selectCorners').onclick = () => { selection = 'corners'; points = []; history = []; showMode('color'); updateSelection(); };
$('selectPairs').onclick = () => { selection = 'pairs'; colorPairs = []; depthPairs = []; history = []; showMode('both'); updateSelection(); };
$('undo').onclick = () => {
  const last = history.pop();
  if (last === 'corner') points.pop();
  if (last === 'color') colorPairs.pop();
  if (last === 'depth') depthPairs.pop();
  updateSelection();
};
function bounds() {
  return {near_mm: status?.settings.near_mm || 15, far_mm: status?.settings.far_mm || 1500,
    min_area: Number($('area').value), body: $('interaction').value === 'body'};
}
function updateDistanceReadouts(distance) {
  const d = distance || {};
  $('surfaceStatus').textContent = d.surface_mm == null ? 'Surface not selected.' : 'Surface / lower boundary: ' + (d.surface_mm/1000).toFixed(3) + ' m from camera.';
  $('bandStatus').textContent = d.closest_mm == null ? 'Chest / upper boundary not selected.' : 'Chest / upper boundary: ' + (d.closest_mm/1000).toFixed(3) + ' m from camera. ' +
    (d.ready ? 'Playable thickness: ' + ((d.surface_mm-d.closest_mm-d.margin_mm)/10).toFixed(1) + ' cm.' : 'Select a valid surface and closer chest point.');
}
action('save', async () => {
  if (points.length !== 4) throw Error('Select four projected corners in the color image first.');
  if (colorPairs.length !== 4 || depthPairs.length !== 4) throw Error('Select four matching body points in each frozen image first.');
  await api('calibration', {space: 'color', points, color_points: colorPairs, depth_points: depthPairs,
    color_size: sizes().color, depth_size: sizes().depth, ...bounds()});
  openStep('boundsStep');
  notice('Alignment saved. Set the closest body distance in step 5, then return to live preview.');
});
action('applyBounds', async () => {
  await api('distance/options', {margin_mm: Math.round(Number($('margin').value)*10)});
  await api('bounds', bounds()); lastImages.depth = null;
  showMode('active'); notice('Surface margin and interaction settings saved. Green shows the playable depth band.');
});
function initializeForms(data) {
  if (!cameraFormLoaded) {
    $('runtime').value = data.camera_settings.runtime;
    $('cameraIndex').value = data.camera_settings.color_index;
    $('colorBackend').value = data.camera_settings.color_backend || 'auto';
    $('mirrorColor').checked = !!data.camera_settings.mirror_color;
    $('mirrorDepth').checked = !!data.camera_settings.mirror_depth;
    cameraFormLoaded = true;
  }
  if (!settingsLoaded) {
    points = data.settings.space === 'color' ? data.settings.points || [] : [];
    colorPairs = data.settings.color_points || []; depthPairs = data.settings.depth_points || [];
    $('margin').value = (data.distance?.margin_mm ?? 10)/10;
    $('area').value = data.settings.min_area; $('interaction').value = data.settings.body ? 'body' : 'contact';
    settingsLoaded = true; updateSelection();
  }
}
function updateStatus(data) {
  $('status').textContent = (data.camera ? 'Depth connected' : data.message) + ' · ' + (data.color_size ? 'Color connected' : data.color_message);
  $('cameraStatus').textContent = 'Depth: ' + (data.camera ? 'ready' : data.message) + '\nColor: ' + (data.color_size ? 'ready · ' + data.color_backend + ' · camera ' + data.camera_settings.color_index : data.color_message);
  updateDistanceReadouts(data.distance);
  const c = data.capture;
  $('captureStatus').textContent = c.phase === 'countdown'
    ? `${c.kind === 'pose' ? 'Hold your star pose' : c.kind === 'range' ? 'Stand at your closest playable position' : 'Clear the surface'} · ${c.remaining}s`
    : c.phase === 'ready' && c.kind === 'pose' ? 'Frozen body capture · return to the PC and select points'
    : c.phase === 'ready' && c.kind === 'range' ? 'Frozen body distance · click your chest once in Depth'
    : c.phase === 'ready' ? 'Capture ready'
    : c.phase === 'failed' ? 'Capture failed: ' + c.error : 'Live preview';
  const missing = [];
  if (!data.camera || !data.color_size) missing.push('connect cameras');
  if (!data.distance?.ready) missing.push('select surface + chest distances');
  if (!data.calibrated || !data.aligned) missing.push('save calibration');
  $('readiness').textContent = missing.length ? missing.join(' · ') : 'BODY INPUT READY';
  if (c.phase === 'ready' && c.kind === 'background' && c.id !== backgroundHandled) {
    backgroundHandled = c.id; openStep('areaStep');
  }
  if (c.phase === 'ready' && (c.kind === 'pose' || c.kind === 'range') && c.id !== handledCapture) {
    handledCapture = c.id; frozenId = c.id; openStep(c.kind === 'pose' ? 'poseStep' : 'boundsStep');
    frozenSizes = {color: c.color_size, depth: c.depth_size};
    selection = c.kind === 'pose' ? 'pairs' : 'chest'; showMode(c.kind === 'pose' ? 'both' : 'depth'); frameReady.color = frameReady.depth = false;
    updateSelection();
  }
}
async function refreshImages() {
  if (projector || imageBusy || !status) return;
  imageBusy = true;
  const currentId = frozenId;
  const mode = $('previewMode').value;
  const active = mode === 'active';
  const query = new URLSearchParams({t: String(Date.now())});
  if (currentId) query.set('capture_id', currentId);
  const depthQuery = new URLSearchParams(query);
  if (active) depthQuery.set('overlay', 'true');
  try {
    await Promise.all(['color', 'depth'].map(async kind => {
      if ((kind === 'color' && $('colorPanel').hidden) || (kind === 'depth' && $('depthPanel').hidden)) return;
      const imageKey = currentId + ':' + kind + ':' + (kind === 'depth' && active ? JSON.stringify(status.distance) : 'plain');
      if (currentId && frameReady[kind] && lastImages[kind] === imageKey) return;
      try {
        const response = await fetch('/api/' + kind + '.jpg?' + (kind === 'depth' ? depthQuery : query));
        if (!response.ok) throw Error('No frame');
        const blob = await response.blob(), url = URL.createObjectURL(blob);
        const image = new Image();
        try {
          image.src = url; await image.decode();
          if (frozenId !== currentId) return;
          const el = $(kind + 'Image'), previous = el.dataset.url;
          el.src = url; el.dataset.url = url;
          $(kind + 'Frame').style.aspectRatio = image.naturalWidth + '/' + image.naturalHeight;
          $(kind + 'Frame').classList.add('ready'); frameReady[kind] = true; lastImages[kind] = imageKey;
          if (previous) URL.revokeObjectURL(previous);
        } finally { if ($(kind + 'Image').dataset.url !== url) URL.revokeObjectURL(url); }
      } catch {
        frameReady[kind] = false; $(kind + 'Frame').classList.remove('ready');
      }
    }));
  } finally { imageBusy = false; }
}
async function pollStatus() {
  try {
    status = await api('status');
    initializeForms(status); updateStatus(status); await refreshImages();
  } catch (error) {
    $('status').textContent = 'Server unavailable'; contacts = [];
  }
  setTimeout(pollStatus, 300);
}
async function pollContacts() {
  try { contacts = (await api('contacts')).contacts; } catch { contacts = []; }
  setTimeout(pollContacts, 50);
}
pollStatus(); pollContacts(); updateSelection();
function draw() {
  const d = Math.min(devicePixelRatio || 1, 2), rect = canvas.getBoundingClientRect();
  const w = Math.round(rect.width * d), h = Math.round(rect.height * d);
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  ctx.fillStyle = '#060a12'; ctx.fillRect(0, 0, w, h);
  if (prefs.game === 'bubbles') {
    for (const b of bubbles) {
      if (contacts.some(p => Math.hypot((p.x-b.x)*w, (p.y-b.y)*h) < b.r*w+Number(prefs.radius)*d)) {
        b.x = .08+Math.random()*.84; b.y = .08+Math.random()*.84; score++;
      }
      ctx.beginPath(); ctx.arc(b.x*w, b.y*h, b.r*w, 0, Math.PI*2);
      ctx.fillStyle = '#153d48'; ctx.fill(); ctx.strokeStyle = prefs.color; ctx.lineWidth = 2*d; ctx.stroke();
    }
    ctx.fillStyle = '#aebbd0'; ctx.font = 18*d+'px system-ui'; ctx.fillText('Bubbles popped: '+score, 24*d, 36*d);
  }
  for (const p of contacts) {
    ctx.beginPath(); ctx.arc(p.x*w, p.y*h, Number(prefs.radius)*d, 0, Math.PI*2);
    ctx.strokeStyle = prefs.color; ctx.shadowColor = prefs.color; ctx.shadowBlur = 20*d;
    ctx.lineWidth = 4*d; ctx.stroke(); ctx.shadowBlur = 0;
    ctx.beginPath(); ctx.arc(p.x*w, p.y*h, 4*d, 0, Math.PI*2); ctx.fillStyle = '#fff'; ctx.fill();
  }
  if (!contacts.length && prefs.game === 'rings' && !projector) {
    ctx.fillStyle = '#6e809b'; ctx.textAlign = 'center'; ctx.font = 17*d+'px system-ui';
    ctx.fillText('Move your body in the calibrated play area', w/2, h/2); ctx.textAlign = 'left';
  }
  if (prefs.targets) {
    ctx.strokeStyle = '#fff'; ctx.lineWidth = 5*d; ctx.strokeRect(3*d, 3*d, w-6*d, h-6*d);
    [[0,0],[1,0],[1,1],[0,1]].forEach(([x,y], i) => {
      ctx.fillStyle = '#fff'; ctx.fillRect(x*w-14*d, y*h-14*d, 28*d, 28*d);
      ctx.fillStyle = '#ffdc93'; ctx.font = 28*d+'px system-ui';
      ctx.fillText(String(i+1), x ? w-40*d : 20*d, y ? h-25*d : 42*d);
    });
  }
  if (status?.capture.phase === 'countdown') {
    ctx.fillStyle = '#060a12dd'; ctx.fillRect(0,0,w,h);
    ctx.textAlign = 'center'; ctx.fillStyle = '#62ffe0'; ctx.font = 80*d+'px system-ui';
    ctx.fillText(status.capture.remaining, w/2, h*.5);
    ctx.font = 22*d+'px system-ui';
    ctx.fillText(status.capture.kind === 'pose' ? 'Spread arms and feet · hold still' : status.capture.kind === 'range' ? 'Stand closer · hold still' : 'Move out of view', w/2, h*.65);
    ctx.textAlign = 'left';
  }
  requestAnimationFrame(draw);
}
requestAnimationFrame(draw);

window.projectorAppStarted = true;
