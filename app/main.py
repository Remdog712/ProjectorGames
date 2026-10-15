from pathlib import Path
from contextlib import asynccontextmanager
import json
import os
import cv2
import numpy as np
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from .camera import Camera
from .tracking import homography, detect

ROOT = Path(__file__).parent
DATA = Path(os.getenv('PROJECTORGAMES_DATA', str(ROOT.parent / '.local')))
camera = Camera()
state = {'matrix': None, 'background': None, 'alignment': None}
settings = {'points': [], 'near_mm': 15, 'far_mm': 100, 'min_area': 100, 'space': 'color', 'depth_points': [], 'color_points': [], 'color_size': None, 'depth_size': None}


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
    if os.getenv('PROJECTORGAMES_CAMERA') == 'openni':
        camera.start()
    yield
    camera.running = False


app = FastAPI(lifespan=lifespan)
app.mount('/static', StaticFiles(directory=ROOT / 'static'), name='static')


class Calibration(BaseModel):
    space: str = 'color'
    depth_points: list[tuple[float, float]] = Field(default_factory=list, max_length=4)
    color_points: list[tuple[float, float]] = Field(default_factory=list, max_length=4)
    color_size: tuple[int, int] | None = None
    depth_size: tuple[int, int] | None = None
    points: list[tuple[float, float]] = Field(min_length=4, max_length=4)
    near_mm: int = Field(default=15, ge=1, le=500)
    far_mm: int = Field(default=100, ge=2, le=1000)
    min_area: int = Field(default=100, ge=10, le=100000)


@app.get('/')
def index():
    return FileResponse(ROOT / 'static/index.html')


@app.get('/api/status')
def status():
    depth = camera.snapshot()
    color = camera.color_snapshot()
    return {'camera': camera.running and depth is not None, 'message': camera.error,
            'color_message': camera.color_error, 'color_size': None if color is None else [color.shape[1], color.shape[0]],
            'aligned': state['alignment'] is not None, 'calibrated': state['matrix'] is not None, 'background': state['background'] is not None,
            'settings': settings, 'size': None if depth is None else [depth.shape[1], depth.shape[0]]}


@app.post('/api/calibration')
def calibrate(config: Calibration):
    if config.near_mm >= config.far_mm:
        raise HTTPException(400, 'Near distance must be smaller than far distance.')
    if config.space not in ('depth', 'color'):
        raise HTTPException(400, 'Unknown calibration coordinate space')
    alignment = None
    try:
        matrix = homography(config.points)
        if config.depth_points or config.color_points:
            alignment = alignment_matrix(config.depth_points, config.color_points)
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    DATA.mkdir(parents=True, exist_ok=True)
    tmp = DATA / 'calibration.tmp'
    tmp.write_text(json.dumps(config.model_dump()))
    tmp.replace(DATA / 'calibration.json')
    settings.update(config.model_dump())
    state['matrix'] = matrix
    state['alignment'] = alignment
    return {'ok': True}


@app.post('/api/background')
def background():
    depth = camera.snapshot()
    if depth is None or not camera.running:
        raise HTTPException(409, 'A live depth camera is required. Use mouse simulation meanwhile.')
    state['background'] = depth
    return {'ok': True}


@app.get('/api/contacts')
def contacts():
    depth = camera.snapshot()
    if depth is None or not camera.running:
        return {'contacts': []}
    bg = state['background']
    if bg is not None and bg.shape != depth.shape:
        state['background'] = None
        return {'contacts': []}
    matrix = state['matrix']
    if settings['space'] == 'color':
        color = camera.color_snapshot()
        if state['alignment'] is None or color is None:
            return {'contacts': []}
        if settings['color_size'] not in ([color.shape[1], color.shape[0]], (color.shape[1], color.shape[0])):
            return {'contacts': []}
        if settings['depth_size'] not in ([depth.shape[1], depth.shape[0]], (depth.shape[1], depth.shape[0])):
            return {'contacts': []}
        matrix = None if matrix is None else matrix @ state['alignment']
    return {'contacts': detect(depth, bg, matrix, settings['near_mm'], settings['far_mm'], settings['min_area'])}


@app.get('/api/depth.jpg')
def preview():
    depth = camera.snapshot()
    if depth is None:
        raise HTTPException(409, 'No depth frame available')
    frame = cv2.applyColorMap(np.uint8(np.clip(depth / 5000, 0, 1) * 255), cv2.COLORMAP_TURBO)
    ok, jpg = cv2.imencode('.jpg', frame)
    if not ok:
        raise HTTPException(500, 'Could not encode depth frame')
    return Response(jpg.tobytes(), media_type='image/jpeg', headers={'Cache-Control': 'no-store'})


def alignment_matrix(depth_points, color_points):
    # Pair ordering must match and form a convex quad around the interaction area.
    homography(depth_points)
    homography(color_points)
    return cv2.getPerspectiveTransform(np.float32(depth_points), np.float32(color_points))


@app.get('/api/color.jpg')
def color_preview():
    frame = camera.color_snapshot()
    if frame is None:
        raise HTTPException(409, camera.color_error)
    ok, jpg = cv2.imencode('.jpg', frame)
    if not ok:
        raise HTTPException(500, 'Could not encode color frame')
    return Response(jpg.tobytes(), media_type='image/jpeg', headers={'Cache-Control': 'no-store'})
