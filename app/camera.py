"""Optional legacy OpenNI2 depth adapter. Vendor runtime is installed separately."""
import os
import threading
import time
import numpy as np


class Camera:
    def __init__(self):
        self.lock = threading.Lock()
        self.depth = None
        self.error = 'Simulation mode; camera not started.'
        self.running = False
        self.thread = None

    def start(self):
        if self.thread:
            return
        self.running = True
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        stream = None
        sdk = None
        try:
            from openni import openni2
            sdk = openni2
            path = os.getenv('OPENNI2_REDIST')
            sdk.initialize(path) if path else sdk.initialize()
            device = sdk.Device.open_any()
            stream = device.create_depth_stream()
            stream.start()
            self.error = ''
            while self.running:
                frame = stream.read_frame()
                # OpenNI depth modes may use millimeters or tenths of millimeters.
                mode = stream.get_video_mode()
                from openni import _openni2
                scale = 0.1 if mode.pixelFormat == _openni2.OniPixelFormat.ONI_PIXEL_FORMAT_DEPTH_100_UM else 1.0
                depth = np.frombuffer(frame.get_buffer_as_uint16(), dtype=np.uint16).reshape(frame.height, frame.width).astype(np.float32) * scale
                with self.lock:
                    self.depth = depth.copy()
        except Exception as exc:
            self.error = f'Camera unavailable: {type(exc).__name__}: {exc}'
        finally:
            if stream:
                stream.stop()
            if sdk:
                sdk.unload()
            self.running = False

    def snapshot(self):
        with self.lock:
            return None if self.depth is None else self.depth.copy()
