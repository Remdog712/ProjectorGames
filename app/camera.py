"""Legacy Astra depth (OpenNI2) and separate UVC color acquisition."""
import os
import threading
import time
import cv2
import numpy as np


class Camera:
    def __init__(self):
        self.lock = threading.Lock()
        self.depth = self.color = None
        self.depth_time = self.color_time = 0.0
        self.error = 'Connect the camera in Setup.'
        self.color_error = 'Color camera not connected.'
        self.running = False
        self.thread = self.color_thread = None
        self.stop_event = threading.Event()

    def start(self, runtime=None, color_index=None):
        if any(t and t.is_alive() for t in (self.thread, self.color_thread)):
            raise RuntimeError('Disconnect the camera before reconnecting.')
        self.stop_event.clear()
        self.depth = self.color = None
        self.error = self.color_error = 'Connecting…'
        self.running = True
        self.thread = threading.Thread(target=self._run, args=(runtime,), daemon=True)
        self.color_thread = threading.Thread(target=self._run_color, args=(color_index,), daemon=True)
        self.thread.start()
        self.color_thread.start()

    def stop(self):
        self.stop_event.set()
        for thread in (self.thread, self.color_thread):
            if thread:
                thread.join(timeout=3)
        if any(t and t.is_alive() for t in (self.thread, self.color_thread)):
            raise RuntimeError('Camera driver is still closing. Wait and retry; restart the app if it remains stuck.')
        self.running = False
        with self.lock:
            self.depth = self.color = None
        self.error = 'Camera disconnected.'
        self.color_error = 'Color camera disconnected.'

    def _run(self, runtime):
        stream = sdk = device = None
        initialized = False
        try:
            from openni import openni2, _openni2
            sdk = openni2
            path = runtime or os.getenv('OPENNI2_REDIST')
            sdk.initialize(path) if path else sdk.initialize()
            initialized = True
            device = sdk.Device.open_any()
            stream = device.create_depth_stream()
            stream.start()
            mode = stream.get_video_mode()
            scale = 0.1 if mode.pixelFormat == _openni2.OniPixelFormat.ONI_PIXEL_FORMAT_DEPTH_100_UM else 1.0
            self.error = ''
            while not self.stop_event.is_set():
                # The Python wrapper accepts seconds, not OpenNI's native milliseconds.
                if sdk.wait_for_any_stream([stream], 1.0) is None:
                    continue
                frame = stream.read_frame()
                depth = np.frombuffer(frame.get_buffer_as_uint16(), dtype=np.uint16).reshape(frame.height, frame.width).astype(np.float32) * scale
                with self.lock:
                    self.depth = depth.copy()
                    self.depth_time = time.monotonic()
        except Exception as exc:
            self.error = f'Depth unavailable: {type(exc).__name__}: {exc}'
        finally:
            for cleanup in (lambda: stream and stream.stop(), lambda: device and device.close(), lambda: initialized and sdk.unload()):
                try:
                    cleanup()
                except Exception:
                    pass
            self.running = False
            with self.lock:
                self.depth = None

    def _run_color(self, color_index):
        capture = None
        try:
            index = int(os.getenv('PROJECTORGAMES_COLOR_INDEX', '0')) if color_index is None else color_index
            capture = cv2.VideoCapture(index, cv2.CAP_DSHOW) if os.name == 'nt' else cv2.VideoCapture(index)
            if not capture.isOpened():
                raise RuntimeError(f'Cannot open color camera {index}. Choose another camera number, then reconnect.')
            while not self.stop_event.is_set():
                ok, frame = capture.read()
                if not ok:
                    raise RuntimeError('No color frames. Close other camera apps, then reconnect.')
                with self.lock:
                    self.color = frame.copy()
                    self.color_time = time.monotonic()
                self.color_error = ''
        except Exception as exc:
            self.color_error = f'Color unavailable: {exc}'
        finally:
            with self.lock:
                self.color = None
            if capture is not None:
                capture.release()

    def pair(self):
        """Copy the latest frames together; streams are not hardware synchronized."""
        with self.lock:
            now = time.monotonic()
            depth = self.depth.copy() if self.depth is not None and now-self.depth_time < 2 else None
            color = self.color.copy() if self.color is not None and now-self.color_time < 2 else None
            return depth, color

    def snapshot(self):
        return self.pair()[0]

    def color_snapshot(self):
        return self.pair()[1]
