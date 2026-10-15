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
        self.color_stop_event = threading.Event()
        self.color_backend = ''

    def start(self, runtime=None, color_index=None, color_backend='auto'):
        if any(t and t.is_alive() for t in (self.thread, self.color_thread)):
            raise RuntimeError('Disconnect the camera before reconnecting.')
        self.stop_event.clear()
        self.color_stop_event.clear()
        self.depth = self.color = None
        self.error = self.color_error = 'Connecting…'
        self.running = True
        self.thread = threading.Thread(target=self._run, args=(runtime,), daemon=True)
        self.color_thread = threading.Thread(target=self._run_color, args=(color_index, color_backend), daemon=True)
        self.thread.start()
        self.color_thread.start()

    def stop(self):
        self.stop_event.set()
        self.color_stop_event.set()
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

    def restart_color(self, color_index, backend='auto'):
        """Retry RGB without disrupting OpenNI depth or an empty-surface capture."""
        self.color_stop_event.set()
        if self.color_thread:
            self.color_thread.join(timeout=3)
            if self.color_thread.is_alive():
                raise RuntimeError('Color driver is still closing. Restart the app before retrying.')
        self.color_stop_event.clear()
        with self.lock:
            self.color = None
        self.color_error = 'Connecting color camera…'
        self.color_thread = threading.Thread(target=self._run_color, args=(color_index, backend), daemon=True)
        self.color_thread.start()

    def _run_color(self, color_index, backend='auto'):
        capture = None
        attempts = []
        try:
            index = int(os.getenv('PROJECTORGAMES_COLOR_INDEX', '0')) if color_index is None else color_index
            choices = [('DirectShow', cv2.CAP_DSHOW), ('Media Foundation', cv2.CAP_MSMF)] if os.name == 'nt' else [('Default', cv2.CAP_ANY)]
            if os.name == 'nt' and backend != 'auto':
                choices = [choices[0] if backend == 'dshow' else choices[1]]
            for name, api in choices:
                if self.color_stop_event.is_set():
                    return
                self.color_backend = name
                self.color_error = f'Trying color camera {index} via {name}…'
                capture = cv2.VideoCapture(index, api)
                if capture.isOpened():
                    # Avoid a webcam's high-resolution default consuming USB bandwidth.
                    capture.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
                    capture.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
                    capture.set(cv2.CAP_PROP_FPS, 30)
                    ok, frame = capture.read()
                    if ok and frame is not None and frame.size:
                        break
                    attempts.append(name + ': opened but no frames')
                else:
                    attempts.append(name + ': could not open')
                capture.release()
                capture = None
            else:
                raise RuntimeError(f'Camera {index}: ' + '; '.join(attempts) + '. Close other camera apps, verify the camera number, or choose another capture backend.')
            while not self.color_stop_event.is_set():
                with self.lock:
                    self.color = frame.copy()
                    self.color_time = time.monotonic()
                self.color_error = ''
                ok, frame = capture.read()
                if not ok or frame is None or not frame.size:
                    raise RuntimeError(f'Camera {index} via {self.color_backend} stopped producing frames. Retry color or choose another backend.')
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

    def depth_ref(self):
        # Camera workers replace arrays and never mutate published frames.
        with self.lock:
            return self.depth if self.depth is not None and time.monotonic()-self.depth_time<.5 else None

    def color_ref(self):
        with self.lock:
            return self.color if self.color is not None and time.monotonic()-self.color_time<.5 else None

    def metadata(self):
        with self.lock:
            now=time.monotonic()
            depth_ok=self.depth is not None and now-self.depth_time<.5
            color_ok=self.color is not None and now-self.color_time<.5
            return {'depth_size':[self.depth.shape[1],self.depth.shape[0]] if depth_ok else None,
                    'color_size':[self.color.shape[1],self.color.shape[0]] if color_ok else None,
                    'color_backend':self.color_backend}

    def snapshot(self):
        value=self.depth_ref()
        return None if value is None else value.copy()

    def color_snapshot(self):
        value=self.color_ref()
        return None if value is None else value.copy()
