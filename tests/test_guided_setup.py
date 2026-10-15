import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock, call
import cv2
import numpy as np
from fastapi import HTTPException
from app import main
from app.camera import Camera
from app.capture import CaptureManager
from app.tracking import detect, homography, interaction_mask


class FakeCamera:
    def __init__(self):
        self.depth = np.full((100,100), 1000, np.float32)
        self.color = np.full((100,100,3), 120, np.uint8)
    def pair(self):
        return self.depth.copy(), self.color.copy()


class CaptureTests(unittest.IsolatedAsyncioTestCase):
    async def test_delayed_capture_remains_frozen_after_player_returns(self):
        camera = FakeCamera()
        manager = CaptureManager()
        snapshot = manager.begin('pose', .02, camera, lambda d: None)
        self.assertEqual(snapshot['phase'], 'countdown')
        await manager.task
        self.assertEqual(manager.status()['phase'], 'ready')
        frozen = manager.depth.copy()
        camera.depth[:] = 800
        np.testing.assert_equal(manager.depth, frozen)
        self.assertEqual(manager.token, snapshot['id'])

    async def test_empty_capture_callback_and_cancelled_capture(self):
        camera, manager, backgrounds = FakeCamera(), CaptureManager(), []
        manager.begin('background', 0, camera, backgrounds.append)
        await manager.task
        self.assertEqual(len(backgrounds), 1)
        manager.begin('background', 10, camera, backgrounds.append)
        task = manager.task
        manager.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(len(backgrounds), 1)
        self.assertEqual(manager.phase, 'idle')

    async def test_missing_camera_fails_instead_of_freezing_stale_frame(self):
        manager, camera = CaptureManager(), FakeCamera()
        camera.pair = lambda: (None, None)
        manager.begin('pose', 0, camera, lambda d: None)
        await manager.task
        self.assertEqual(manager.phase, 'failed')
        self.assertIn('Fresh', manager.error)


class GuidedSetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.saved_state = main.state.copy()
        self.saved_settings = main.settings.copy()
        self.saved_camera_settings = main.camera_settings.copy()
        self.addCleanup(lambda: main.state.update(self.saved_state))
        self.addCleanup(lambda: main.settings.update(self.saved_settings))
        self.addCleanup(lambda: main.camera_settings.update(self.saved_camera_settings))

    def test_gui_connection_is_persisted_and_switching_device_invalidates_mapping(self):
        with patch.object(main,'DATA',Path(self.temp.name)), patch.object(main,'camera') as camera:
            main.state['matrix'] = np.eye(3)
            result = main.configure_camera(main.CameraSettings(runtime=self.temp.name, color_index=2))
            self.assertTrue(result['ok'])
            camera.start.assert_called_once_with(self.temp.name, 2, 'auto')
            self.assertTrue((Path(self.temp.name)/'camera.json').exists())
            self.assertIsNone(main.state['matrix'])
            self.assertIsNone(main.state['background'])

    def test_invalid_bounds_do_not_change_saved_state(self):
        with self.assertRaises(HTTPException):
            main.set_bounds(main.Bounds(near_mm=300, far_mm=100))
        self.assertEqual(main.settings, self.saved_settings)

    def test_body_extremities_interact_and_distance_band_filters(self):
        bg = np.full((200,200), 2000, np.float32)
        depth = bg.copy()
        depth[30:170,80:120] = 1500
        depth[50:70,20:180] = 1500
        matrix = homography([[0,0],[199,0],[199,199],[0,199]])
        centers = detect(depth,bg,matrix,15,1000,100,False)
        body = detect(depth,bg,matrix,15,1000,100,True)
        self.assertEqual(len(centers),1)
        self.assertGreater(len(body),3)
        self.assertLess(min(p['x'] for p in body),.15)
        self.assertGreater(max(p['x'] for p in body),.85)
        self.assertFalse(interaction_mask(depth,bg,15,100).any())

    def test_preview_overlay_and_expired_capture(self):
        depth = np.full((100,100), 1000, np.float32)
        depth[30:60,30:60] = 950
        main.state['background'] = np.full_like(depth,1000)
        with patch.object(main.camera,'snapshot',return_value=depth):
            response = main.preview(overlay=True,near=15,far=100)
        image = cv2.imdecode(np.frombuffer(response.body,np.uint8),cv2.IMREAD_COLOR)
        self.assertGreater(int(image[40,40,1]),200)
        with self.assertRaises(HTTPException):
            main.color_preview(capture_id='expired')

    def test_suggest_bounds_from_body_frame(self):
        manager = CaptureManager()
        manager.phase, manager.kind = 'ready', 'pose'
        manager.depth = np.full((100,100),1000,np.float32)
        manager.depth[20:70,20:70] = 400
        main.state['background'] = np.full_like(manager.depth,1000)
        with patch.object(main,'capture',manager):
            result = main.suggest_bounds()
        self.assertLessEqual(result['near_mm'],600)
        self.assertGreaterEqual(result['far_mm'],600)

    def test_camera_discards_stale_frames(self):
        camera = Camera()
        camera.depth = np.ones((2,2))
        camera.color = np.ones((2,2,3))
        self.assertEqual(camera.pair(), (None,None))


class CameraPollingTests(unittest.TestCase):
    def test_depth_poll_uses_seconds_and_skips_timed_out_reads(self):
        from openni import openni2
        camera = Camera()
        stream = MagicMock()
        stream.get_video_mode.return_value.pixelFormat = 0
        frame = MagicMock()
        frame.height = frame.width = 2
        frame.get_buffer_as_uint16.return_value = np.array([1000]*4,dtype=np.uint16).tobytes()
        def read():
            camera.stop_event.set()
            return frame
        stream.read_frame.side_effect = read
        device = MagicMock()
        device.create_depth_stream.return_value = stream
        with patch.object(openni2,'initialize'), patch.object(openni2,'unload'), patch.object(openni2.Device,'open_any',return_value=device), patch.object(openni2,'wait_for_any_stream',side_effect=[None,stream]) as wait:
            camera._run('/fake/runtime')
        self.assertEqual(wait.call_args_list,[call([stream],1.0),call([stream],1.0)])
        stream.read_frame.assert_called_once()
        stream.stop.assert_called_once()
        device.close.assert_called_once()


class ColorRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def test_windows_fallback_releases_failed_capture_and_keeps_depth(self):
        from types import SimpleNamespace
        camera = Camera()
        camera.depth = np.ones((2,2),np.float32)
        failed, success = MagicMock(), MagicMock()
        failed.isOpened.return_value = False
        success.isOpened.return_value = True
        frame = np.ones((2,2,3),np.uint8)
        def read():
            if success.read.call_count >= 2:
                camera.color_stop_event.set()
            return True, frame
        success.read.side_effect = read
        with patch('app.camera.os',SimpleNamespace(name='nt')), patch('app.camera.cv2.VideoCapture',side_effect=[failed,success]) as create:
            camera._run_color(1,'auto')
        self.assertEqual(create.call_args_list,[call(1,cv2.CAP_DSHOW),call(1,cv2.CAP_MSMF)])
        failed.release.assert_called_once()
        success.release.assert_called_once()
        np.testing.assert_equal(camera.depth,np.ones((2,2),np.float32))
        self.assertEqual(camera.color_backend,'Media Foundation')

    def test_open_camera_without_frames_also_falls_back(self):
        from types import SimpleNamespace
        camera = Camera()
        captures = [MagicMock(),MagicMock()]
        for cap in captures:
            cap.isOpened.return_value=True
            cap.read.return_value=(False,None)
        with patch('app.camera.os',SimpleNamespace(name='nt')), patch('app.camera.cv2.VideoCapture',side_effect=captures) as create:
            camera._run_color(2,'auto')
        self.assertEqual(create.call_count,2)
        self.assertIn('DirectShow: opened but no frames',camera.color_error)
        self.assertIn('Media Foundation: opened but no frames',camera.color_error)

    def test_color_only_retry_preserves_depth_and_empty_surface(self):
        bg=np.full((2,2),1000,np.float32)
        old_state=main.state.copy()
        old_settings=main.settings.copy()
        old_camera=main.camera_settings.copy()
        self.addCleanup(lambda:main.state.update(old_state))
        self.addCleanup(lambda:main.settings.update(old_settings))
        self.addCleanup(lambda:main.camera_settings.update(old_camera))
        main.state['background']=bg
        with patch.object(main,'DATA',Path(self.temp.name)), patch.object(main,'camera') as camera:
            result=main.retry_color(main.ColorSettings(color_index=1,color_backend='msmf'))
            camera.restart_color.assert_called_once_with(1,'msmf')
            camera.stop.assert_not_called()
            camera.start.assert_not_called()
            self.assertIs(main.state['background'],bg)
            self.assertTrue(result['ok'])
