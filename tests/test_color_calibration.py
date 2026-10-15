import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import cv2
import numpy as np
from fastapi import HTTPException
from app import main
from app.tracking import detect


class ColorCalibrationTests(unittest.TestCase):
    def setUp(self):
        old_distance = main.distance_settings.copy()
        self.addCleanup(lambda: main.distance_settings.update(old_distance))
        main.distance_settings.update(active=False, surface_mm=None, closest_mm=None, depth_size=None)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.old_state = main.state.copy()
        self.old_settings = main.settings.copy()
        self.addCleanup(lambda: main.state.update(self.old_state))
        self.addCleanup(lambda: main.settings.update(self.old_settings))
        self.config = main.Calibration(
            points=[(10,20),(208,20),(208,218),(10,218)],
            depth_points=[(0,0),(99,0),(99,99),(0,99)],
            color_points=[(10,20),(208,20),(208,218),(10,218)],
            color_size=(240,240),depth_size=(100,100),body=False)

    def test_persist_and_map_depth_via_color(self):
        with patch.object(main, 'DATA', Path(self.temp.name)):
            self.assertEqual(main.calibrate(self.config), {'ok':True})
            self.assertTrue((Path(self.temp.name)/'calibration.json').exists())
        bg=np.full((100,100),1000,dtype=np.uint16)
        depth=bg.copy()
        depth[40:60,40:60]=950
        matrix=main.state['matrix'] @ main.state['alignment']
        hits=detect(depth,bg,matrix)
        self.assertEqual(len(hits),1)
        self.assertAlmostEqual(hits[0]['x'],.5,places=2)
        self.assertAlmostEqual(hits[0]['y'],.5,places=2)

    def test_bad_alignment_rejected(self):
        self.config.depth_points=[(0,0)]*4
        with self.assertRaises(HTTPException) as error:
            main.calibrate(self.config)
        self.assertEqual(error.exception.status_code,400)

    def test_no_color_frame_reports_conflict(self):
        with patch.object(main.camera,'color_snapshot',return_value=None):
            with self.assertRaises(HTTPException) as error:
                main.color_preview()
        self.assertEqual(error.exception.status_code,409)

    def test_real_color_jpeg(self):
        frame=np.zeros((60,80,3),dtype=np.uint8)
        frame[:,:,2]=255
        with patch.object(main.camera,'color_snapshot',return_value=frame):
            response=main.color_preview()
        decoded=cv2.imdecode(np.frombuffer(response.body,np.uint8),cv2.IMREAD_COLOR)
        self.assertEqual(decoded.shape,(60,80,3))
        self.assertGreater(int(decoded[30,40,2]),240)

    def test_contacts_blocked_without_alignment_or_at_wrong_resolution(self):
        depth=np.full((100,100),950,dtype=np.uint16)
        with patch.object(main,'DATA',Path(self.temp.name)):
            main.calibrate(self.config)
        main.state['background']=np.full_like(depth,1000)
        with patch.object(main.camera,'snapshot',return_value=depth), patch.object(main.camera,'color_snapshot',return_value=np.zeros((240,240,3),np.uint8)), patch.object(main.camera,'running',True):
            self.assertEqual(len(main.contacts()['contacts']),1)
            main.settings['color_size']=(640,480)
            self.assertEqual(main.contacts()['contacts'],[])
            main.settings['color_size']=(240,240)
            main.state['alignment']=None
            self.assertEqual(main.contacts()['contacts'],[])
