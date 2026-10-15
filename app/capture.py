"""One delayed capture, retained server-side so selecting points needs no helper."""
import asyncio
import math
import time
import uuid


class CaptureManager:
    def __init__(self):
        self.task = None
        self.token = None
        self.kind = None
        self.phase = 'idle'
        self.deadline = 0
        self.error = ''
        self.depth = self.color = None

    def cancel(self):
        if self.task:
            self.task.cancel()
        self.token = None
        self.phase = 'idle'
        self.depth = self.color = None

    def begin(self, kind, delay, camera, on_background):
        self.cancel()
        self.token = uuid.uuid4().hex
        self.kind = kind
        self.phase = 'countdown'
        self.error = ''
        self.deadline = time.monotonic() + delay
        self.task = asyncio.create_task(self._capture(delay, camera, on_background))
        return self.status()

    async def _capture(self, delay, camera, on_background):
        try:
            await asyncio.sleep(delay)
            depth, color = camera.pair()
            if depth is None or (self.kind == 'pose' and color is None):
                raise ValueError('Fresh depth and color frames are required for alignment; fresh depth is required for a distance capture. Reconnect the camera and retry.')
            self.depth, self.color = depth, color
            if self.kind == 'background':
                on_background(depth.copy())
            self.phase = 'ready'
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            self.phase = 'failed'
            self.error = str(exc)

    def status(self):
        return {'id': self.token, 'kind': self.kind, 'phase': self.phase,
                'remaining': max(0, math.ceil(self.deadline-time.monotonic())) if self.phase == 'countdown' else 0,
                'error': self.error,
                'depth_size': None if self.depth is None else [self.depth.shape[1], self.depth.shape[0]],
                'color_size': None if self.color is None else [self.color.shape[1], self.color.shape[0]]}
