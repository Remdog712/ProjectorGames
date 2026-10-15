import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import cv2
import numpy as np
from fastapi import HTTPException
from app import main
from app.capture import CaptureManager
from app.distance import sample_depth, band_mask
from app.tracking import homography, detect


class DistanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        for obj in [main.state,main.settings,main.camera_settings,main.distance_settings]:
            saved=obj.copy()
            self.addCleanup(lambda obj=obj,saved=saved:obj.update(saved))
        main.camera_settings.update(mirror_depth=False,mirror_color=False)
        main.distance_settings.update(active=False,surface_mm=None,closest_mm=None,margin_mm=10,depth_size=None)
        self.patch=patch.object(main,'DATA',Path(self.temp.name))
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_surface_and_one_frozen_chest_point_define_band(self):
        depth=np.full((100,100),3000,np.float32)
        with patch.object(main.camera,'snapshot',return_value=depth):
            surface=main.select_distance(main.DistanceSample(target='surface',x=20,y=20))
        self.assertEqual(surface['sample_mm'],3000)
        self.assertFalse(surface['distance']['ready'])
        manager=CaptureManager()
        manager.phase,manager.kind,manager.token='ready','range','pose-id'
        manager.depth=depth.copy()
        manager.depth[40:60,40:60]=1700
        with patch.object(main,'capture',manager):
            chest=main.select_distance(main.DistanceSample(target='body',x=50,y=50,capture_id='pose-id'))
        self.assertEqual(chest['sample_mm'],1700)
        self.assertTrue(chest['distance']['ready'])
        saved=json.loads((Path(self.temp.name)/'distance.json').read_text())
        self.assertEqual(saved['closest_mm'],1700)
        mask=band_mask(manager.depth,1700,3000,10)
        self.assertEqual(mask[50,50],255)
        self.assertEqual(mask[10,10],0)
        test=np.repeat(np.array([[0,1600,1700,2000,2990,3000,3100]],np.float32),5,axis=0)
        # Thick stripes survive the noise opening.
        test=np.repeat(test,5,axis=1)
        expected=[False,False,True,True,True,False,False]
        result=band_mask(test,1700,3000,10)
        self.assertEqual([bool(result[2,x*5+2]) for x in range(7)],expected)

    def test_chest_requires_capture_and_rejects_surface_point(self):
        main.distance_settings.update(active=True,surface_mm=2000,depth_size=[100,100])
        with self.assertRaises(HTTPException):
            main.select_distance(main.DistanceSample(target='body',x=50,y=50))
        manager=CaptureManager()
        manager.phase,manager.kind,manager.token='ready','range','pose-id'
        manager.depth=np.full((100,100),2000,np.float32)
        with patch.object(main,'capture',manager):
            with self.assertRaises(HTTPException):
                main.select_distance(main.DistanceSample(target='body',x=50,y=50,capture_id='pose-id'))
        self.assertIsNone(main.distance_settings['closest_mm'])

    def test_mirrors_apply_to_preview_capture_samples_and_reset_old_mapping(self):
        depth=np.repeat(np.array([[1000,1200,1400,1600,1800]],np.float32),5,axis=0)
        color=np.zeros((5,5,3),np.uint8)
        color[:,0]=255
        main.state['matrix']=np.eye(3)
        main.distance_settings.update(active=True,surface_mm=3000,closest_mm=1000,depth_size=[5,5])
        with patch.object(main.camera,'snapshot',return_value=depth), patch.object(main.camera,'color_snapshot',return_value=color), patch.object(main.camera,'pair',return_value=(depth,color)):
            response=main.set_mirrors(main.Mirrors(mirror_depth=True,mirror_color=True))
            self.assertTrue(response['changed'])
            np.testing.assert_equal(main.oriented_depth(),cv2.flip(depth,1))
            np.testing.assert_equal(main.oriented_color(),cv2.flip(color,1))
            pair=main.OrientedFrames().pair()
            np.testing.assert_equal(pair[0],main.oriented_depth())
            # Sample near the mirrored right edge: median local depth is 1200, not 1600.
            result=main.select_distance(main.DistanceSample(target='surface',x=4,y=2))
            self.assertEqual(result['sample_mm'],1200)
        self.assertIsNone(main.state['matrix'])
        self.assertIsNone(main.distance_settings['closest_mm'])
        self.assertTrue(json.loads((Path(self.temp.name)/'camera.json').read_text())['mirror_depth'])

    def test_alignment_handles_opposite_winding_and_same_physical_points(self):
        depth_points=[[20,20],[80,20],[80,80],[20,80]]
        color_points=[[79,20],[19,20],[19,80],[79,80]]
        matrix=main.alignment_matrix(depth_points,color_points)
        result=cv2.perspectiveTransform(np.float32([[[20,20],[80,80]]]),matrix)[0]
        np.testing.assert_allclose(result,[[79,20],[19,80]],atol=.001)

    def test_sampling_ignores_holes_and_rejects_invalid_pixels(self):
        depth=np.full((9,9),2000,np.float32)
        depth[4,4]=0
        self.assertEqual(sample_depth(depth,4,4),2000)
        with self.assertRaises(ValueError):sample_depth(np.zeros((9,9)),4,4)
        with self.assertRaises(ValueError):sample_depth(depth,20,20)

    def test_contacts_use_band_without_background_and_reject_new_resolution(self):
        depth=np.full((100,100),3000,np.float32)
        depth[30:70,30:70]=1800
        main.distance_settings.update(active=True,surface_mm=3000,closest_mm=1700,depth_size=[100,100])
        main.state.update(background=None,matrix=homography([[0,0],[99,0],[99,99],[0,99]]))
        main.settings.update(space='depth',body=False)
        with patch.object(main.camera,'snapshot',return_value=depth),patch.object(main.camera,'running',True):
            self.assertEqual(len(main.contacts()['contacts']),1)
            main.distance_settings['depth_size']=[640,480]
            self.assertEqual(main.contacts()['contacts'],[])

    def test_margin_cannot_invert_band(self):
        main.distance_settings.update(surface_mm=1000,closest_mm=950)
        with self.assertRaises(HTTPException):
            main.set_distance_options(main.DistanceOptions(margin_mm=100))
        main.set_distance_options(main.DistanceOptions(margin_mm=0))
        self.assertEqual(main.distance_settings['margin_mm'],0)


class ChestCaptureTests(unittest.IsolatedAsyncioTestCase):
    async def test_chest_capture_only_needs_depth(self):
        class DepthOnly:
            def pair(self):return np.full((10,10),2000,np.float32),None
        manager=CaptureManager()
        manager.begin('range',0,DepthOnly(),lambda d:None)
        await manager.task
        self.assertEqual(manager.phase,'ready')
        self.assertIsNone(manager.color)
