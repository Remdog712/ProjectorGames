from pathlib import Path
from contextlib import asynccontextmanager
from typing import Literal
import json
import hashlib
import os
import cv2
import numpy as np
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from .camera import Camera
from .capture import CaptureManager
from .tracking import homography, detect, interaction_mask

ROOT = Path(__file__).parent
DATA = Path(os.getenv('PROJECTORGAMES_DATA', str(ROOT.parent / '.local')))
camera = Camera()
capture = CaptureManager()
state = {'matrix': None, 'background': None, 'alignment': None}
settings = {'points': [], 'near_mm': 15, 'far_mm': 1500, 'min_area': 100, 'body': True,
            'space': 'color', 'depth_points': [], 'color_points': [], 'color_size': None, 'depth_size': None}
camera_settings = {'enabled': False, 'runtime': '', 'color_index': 0, 'color_backend': 'auto'}


class Bounds(BaseModel):
    near_mm: int = Field(default=15, ge=1, le=4999)
    far_mm: int = Field(default=1500, ge=2, le=5000)
    min_area: int = Field(default=100, ge=10, le=100000)
    body: bool = True


class Calibration(Bounds):
    space: Literal['color', 'depth'] = 'color'
    depth_points: list[tuple[float, float]] = Field(default_factory=list, max_length=4)
    color_points: list[tuple[float, float]] = Field(default_factory=list, max_length=4)
    color_size: tuple[int, int] | None = None
    depth_size: tuple[int, int] | None = None
    points: list[tuple[float, float]] = Field(min_length=4, max_length=4)


class ColorSettings(BaseModel):
    color_index: int = Field(default=0, ge=0, le=20)
    color_backend: Literal['auto', 'dshow', 'msmf'] = 'auto'


class CameraSettings(ColorSettings):
    enabled: bool = True
    runtime: str = Field(default='', max_length=2048)


class CaptureRequest(BaseModel):
    kind: Literal['pose', 'background']
    delay: int = Field(default=10, ge=0, le=60)


def save_json(name, data):
    DATA.mkdir(parents=True, exist_ok=True)
    tmp = DATA / (name + '.tmp')
    tmp.write_text(json.dumps(data))
    tmp.replace(DATA / name)


def alignment_matrix(depth_points, color_points):
    homography(depth_points)
    homography(color_points)
    return cv2.getPerspectiveTransform(np.float32(depth_points), np.float32(color_points))


def discover_runtime():
    explicit = os.getenv('OPENNI2_REDIST', '')
    if explicit and (Path(explicit) / 'OpenNI2.dll').is_file():
        return explicit
    if os.name == 'nt':
        root = Path(os.getenv('ProgramFiles', 'C:/Program Files')) / 'Orbbec'
        if root.exists():
            # Restrict discovery to the vendor directory, never scan the whole disk.
            for dll in sorted(root.rglob('OpenNI2.dll')):
                if 'x86' not in str(dll).lower() and 'win32' not in str(dll).lower():
                    return str(dll.parent)
    return explicit


@asynccontextmanager
async def lifespan(app):
    DATA.mkdir(parents=True, exist_ok=True)
    try:
        saved = json.loads((DATA / 'calibration.json').read_text())
        saved.setdefault('space', 'depth')
        config = Calibration(**saved)
        state['matrix'] = homography(config.points)
        if config.space == 'color' and len(config.depth_points) == len(config.color_points) == 4:
            state['alignment'] = alignment_matrix(config.depth_points, config.color_points)
        settings.update(config.model_dump())
    except (OSError, ValueError, TypeError):
        pass
    try:
        camera_settings.update(CameraSettings(**json.loads((DATA / 'camera.json').read_text())).model_dump())
    except (OSError, ValueError, TypeError):
        camera_settings.update(enabled=os.getenv('PROJECTORGAMES_CAMERA') == 'openni',
                               color_index=int(os.getenv('PROJECTORGAMES_COLOR_INDEX', '0')))
    if not camera_settings['runtime']:
        camera_settings['runtime'] = discover_runtime()
    if camera_settings['enabled']:
        camera.start(camera_settings['runtime'], camera_settings['color_index'], camera_settings['color_backend'])
    yield
    capture.cancel()
    try:
        camera.stop()
    except RuntimeError:
        pass


app = FastAPI(lifespan=lifespan)
app.mount('/static', StaticFiles(directory=ROOT / 'static'), name='static')


@app.middleware('http')
async def fresh_local_ui(request, call_next):
    response = await call_next(request)
    # Updates are installed in place; never reuse UI or hardware status from an older run.
    response.headers['Cache-Control'] = 'no-store'
    return response


@app.get('/')
def index():
    page = (ROOT / 'static/index.html').read_text(encoding='utf-8')
    for filename in ('app.js', 'style.css'):
        version = hashlib.sha256((ROOT / 'static' / filename).read_bytes()).hexdigest()[:12]
        page = page.replace('/static/' + filename, '/static/' + filename + '?v=' + version)
    return HTMLResponse(page)


@app.get('/api/status')
def status():
    depth = camera.snapshot()
    color = camera.color_snapshot()
    return {'camera': camera.running and depth is not None, 'message': camera.error,
            'color_message': camera.color_error, 'color_backend': getattr(camera, 'color_backend', ''), 'color_size': None if color is None else [color.shape[1], color.shape[0]],
            'aligned': state['alignment'] is not None, 'calibrated': state['matrix'] is not None,
            'background': state['background'] is not None, 'settings': settings, 'camera_settings': camera_settings,
            'capture': capture.status(), 'size': None if depth is None else [depth.shape[1], depth.shape[0]]}


@app.post('/api/camera')
def configure_camera(config: CameraSettings):
    runtime = config.runtime.strip().strip('"')
    if runtime.lower().endswith('.dll'):
        runtime = str(Path(runtime).parent)
    if config.enabled and runtime and not Path(runtime).is_dir():
        raise HTTPException(400, 'The runtime folder does not exist on this PC. Select the folder containing OpenNI2.dll.')
    try:
        camera.stop()
    except RuntimeError as exc:
        raise HTTPException(409, str(exc))
    capture.cancel()
    state['background'] = None
    if runtime != camera_settings['runtime'] or config.color_index != camera_settings['color_index'] or config.color_backend != camera_settings['color_backend']:
        state['matrix'] = state['alignment'] = None
        settings.update(points=[], depth_points=[], color_points=[])
        save_json('calibration.json', settings)
    camera_settings.update(config.model_dump(), runtime=runtime)
    save_json('camera.json', camera_settings)
    if config.enabled:
        camera.start(runtime, config.color_index, config.color_backend)
    return {'ok': True, 'message': 'Connecting. Check depth and color status.' if config.enabled else 'Disconnected.'}


@app.post('/api/camera/color')
def retry_color(config: ColorSettings):
    try:
        camera.restart_color(config.color_index, config.color_backend)
    except RuntimeError as exc:
        raise HTTPException(409, str(exc))
    capture.cancel()
    if config.color_index != camera_settings['color_index'] or config.color_backend != camera_settings['color_backend']:
        state['matrix'] = state['alignment'] = None
        settings.update(points=[], depth_points=[], color_points=[])
        save_json('calibration.json', settings)
    camera_settings.update(config.model_dump())
    save_json('camera.json', camera_settings)
    return {'ok': True, 'message': 'Retrying color only; depth keeps running. Check the camera preview and color status.'}


@app.post('/api/calibration')
def calibrate(config: Calibration):
    if config.near_mm >= config.far_mm:
        raise HTTPException(400, 'Lower distance must be smaller than upper distance.')
    alignment = None
    try:
        matrix = homography(config.points)
        if config.depth_points or config.color_points:
            alignment = alignment_matrix(config.depth_points, config.color_points)
        if config.space == 'color' and (alignment is None or config.color_size is None or config.depth_size is None):
            raise ValueError('Complete the frozen color/depth body pairs before saving.')
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    save_json('calibration.json', config.model_dump())
    settings.update(config.model_dump())
    state['matrix'] = matrix
    state['alignment'] = alignment
    return {'ok': True}


@app.post('/api/bounds')
def set_bounds(config: Bounds):
    if config.near_mm >= config.far_mm:
        raise HTTPException(400, 'Lower distance must be smaller than upper distance.')
    settings.update(config.model_dump())
    save_json('calibration.json', settings)
    return {'ok': True}


@app.post('/api/capture')
async def schedule_capture(config: CaptureRequest):
    return capture.begin(config.kind, config.delay, camera, lambda depth: state.update(background=depth))


@app.delete('/api/capture')
async def cancel_capture():
    capture.cancel()
    return {'ok': True}


@app.post('/api/bounds/suggest')
def suggest_bounds():
    bg, depth = state['background'], capture.depth
    if capture.phase != 'ready' or capture.kind != 'pose' or bg is None or depth is None or bg.shape != depth.shape:
        raise HTTPException(409, 'First capture an empty surface, then a body pose with the same camera setup.')
    delta = bg.astype(np.float32)-depth.astype(np.float32)
    valid = (bg > 0) & (depth > 0) & (delta >= 25) & (delta <= 4500)
    if int(valid.sum()) < 100:
        raise HTTPException(409, 'Too few foreground pixels. Capture your pose again, closer to the camera than the wall.')
    lo, hi = np.percentile(delta[valid], [2, 98])
    return {'near_mm': max(1, int(lo)-30), 'far_mm': min(5000, int(hi)+100),
            'message': 'Suggested from foreground depth differences; verify the highlighted regions before playing.'}


@app.post('/api/background')
def background():
    depth = camera.snapshot()
    if depth is None or not camera.running:
        raise HTTPException(409, 'Connect a live depth camera first.')
    state['background'] = depth
    return {'ok': True}


def projector_matrix(depth):
    matrix = state['matrix']
    if settings['space'] == 'color':
        color = camera.color_snapshot()
        if state['alignment'] is None or color is None:
            return None
        if tuple(settings['color_size'] or ()) != (color.shape[1], color.shape[0]):
            return None
        if tuple(settings['depth_size'] or ()) != (depth.shape[1], depth.shape[0]):
            return None
        matrix = None if matrix is None else matrix @ state['alignment']
    return matrix


@app.get('/api/contacts')
def contacts():
    depth = camera.snapshot()
    if depth is None or not camera.running or capture.phase == 'countdown':
        return {'contacts': []}
    bg = state['background']
    if bg is not None and bg.shape != depth.shape:
        state['background'] = None
        return {'contacts': []}
    return {'contacts': detect(depth, bg, projector_matrix(depth), settings['near_mm'], settings['far_mm'], settings['min_area'], settings['body'])}


def jpeg(frame):
    ok, image = cv2.imencode('.jpg', frame)
    if not ok:
        raise HTTPException(500, 'Could not encode camera frame')
    return Response(image.tobytes(), media_type='image/jpeg', headers={'Cache-Control': 'no-store'})


def preview_frame(kind, capture_id):
    if capture_id:
        if capture_id != capture.token or capture.phase != 'ready':
            raise HTTPException(409, 'Frozen capture expired. Capture a new pose.')
        return capture.depth if kind == 'depth' else capture.color
    return camera.snapshot() if kind == 'depth' else camera.color_snapshot()


@app.get('/api/depth.jpg')
def preview(capture_id: str | None = None, overlay: bool = False,
            near: int | None = Query(default=None, ge=1, le=4999), far: int | None = Query(default=None, ge=2, le=5000)):
    depth = preview_frame('depth', capture_id)
    if depth is None:
        raise HTTPException(409, 'No depth frame available')
    frame = cv2.applyColorMap(np.uint8(np.clip(depth / 5000, 0, 1) * 255), cv2.COLORMAP_TURBO)
    frame[depth <= 0] = 0
    if overlay:
        mask = interaction_mask(depth, state['background'], near if near is not None else settings['near_mm'], far if far is not None else settings['far_mm'])
        frame = (frame * .3).astype(np.uint8)
        frame[mask > 0] = (120, 255, 70)
    return jpeg(frame)


@app.get('/api/color.jpg')
def color_preview(capture_id: str | None = None):
    frame = preview_frame('color', capture_id)
    if frame is None:
        raise HTTPException(409, camera.color_error)
    return jpeg(frame)
