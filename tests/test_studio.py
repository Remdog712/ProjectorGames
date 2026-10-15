import unittest
import cv2
import numpy as np
from app.autocalibration import DICT,marker_layout,projector_mapping,fit_surface,foreground_alignment
from app.realtime import Pipeline,RegionTracker,DEFAULTS
from app.pose import decode_pose


class AutomaticCalibrationTests(unittest.TestCase):
    def board(self):
        image=np.full((800,1200,3),255,np.uint8)
        for identity,corners in marker_layout(1200,800):
            x,y=np.int32(corners[0]);side=int(corners[1,0]-corners[0,0])
            marker=cv2.aruco.generateImageMarker(DICT,identity,side)
            image[y:y+side,x:x+side]=cv2.cvtColor(marker,cv2.COLOR_GRAY2BGR)
        return image
    def test_projected_markers_automatically_map_and_handle_reflection(self):
        image=self.board()
        for flip in (False,True):
            actual=cv2.flip(image,1) if flip else image
            matrix,info=projector_mapping(actual,1200,800)
            x=1199-300 if flip else 300
            point=cv2.perspectiveTransform(np.float32([[[x,400]]]),matrix)[0,0]
            np.testing.assert_allclose(point,[.25,.5],atol=.004)
            self.assertLess(info['reprojection_px'],2)
            self.assertEqual(info['points'],16)
    def test_missing_markers_fail_instead_of_false_calibration(self):
        with self.assertRaises(ValueError):projector_mapping(np.zeros((480,640,3),np.uint8),1280,720)
    def test_surface_fit_handles_tilt_outliers_and_depth_holes(self):
        y,x=np.mgrid[:480,:640]
        truth=(1/(.00008*x/640+.00004*y/480+1/2500)).astype(np.float32)
        noisy=truth.copy();noisy[100:180,200:400]-=500;noisy[::8,::8]=0
        plane,info=fit_surface(noisy)
        self.assertLess(np.median(abs(plane-truth)),2)
        self.assertGreater(info['inlier_fraction'],.8)
    def test_silhouette_alignment_matches_reflected_depth_without_clicks(self):
        color=np.full((240,320,3),100,np.uint8);empty=color.copy()
        mask=np.zeros((240,320),np.uint8)
        cv2.rectangle(mask,(130,60),(180,160),255,-1)
        cv2.rectangle(mask,(30,60),(280,85),255,-1)
        cv2.line(mask,(140,150),(70,220),255,20);cv2.line(mask,(170,150),(230,220),255,20)
        color[mask>0]=[20,220,40]
        surface=np.full((240,320),2500,np.float32)
        depth=surface.copy();depth[cv2.flip(mask,1)>0]=1800
        matrix,info=foreground_alignment(empty,color,surface,depth)
        projected=cv2.warpPerspective((depth<2000).astype(np.uint8)*255,matrix,(320,240))>128
        iou=(projected&(mask>0)).sum()/(projected|(mask>0)).sum()
        self.assertGreater(iou,.85)
        self.assertGreater(info['silhouette_overlap'],.85)
    def test_empty_alignment_rejected(self):
        image=np.zeros((240,320,3),np.uint8);depth=np.full((240,320),2500,np.float32)
        with self.assertRaises(ValueError):foreground_alignment(image,image,depth,depth)


class RealtimeTests(unittest.TestCase):
    def test_stability_rejects_flash_and_ids_persist(self):
        pipe=Pipeline();depth=np.full((480,640),2500,np.float32);plane=depth.copy();depth[100:400,250:390]=1500;depth[130:180,80:560]=1500
        matrix=np.diag([1/639,1/479,1])
        first,_,_=pipe.process(depth,matrix,DEFAULTS,plane)
        self.assertEqual(first['contacts'],[])
        second,mask,small=pipe.process(depth,matrix,DEFAULTS,plane)
        self.assertEqual(small.shape,(240,320))
        self.assertEqual(len(second['regions']),1)
        identity=second['regions'][0]['id']
        next_depth=np.roll(depth,2,axis=1)
        third,_,_=pipe.process(next_depth,matrix,DEFAULTS,plane)
        self.assertEqual(third['regions'][0]['id'],identity)
        self.assertTrue(third['contacts'])
        clear,_,_=pipe.process(plane,matrix,DEFAULTS,plane)
        self.assertEqual(clear['contacts'],[])
    def test_plane_band_excludes_surface_and_beyond_reach(self):
        pipe=Pipeline();config={**DEFAULTS,'stable_frames':1,'median':0}
        depth=np.full((100,100),2500,np.float32);plane=depth.copy();depth[20:70,20:70]=500
        packet,mask,_=pipe.process(depth,np.diag([.01,.01,1]),config,plane)
        self.assertFalse(mask.any())
    def test_pose_decodes_people_joints_nms_and_letterbox(self):
        raw=np.zeros((1,56,2),np.float32)
        raw[0,:5,0]=[320,320,100,200,.9]
        raw[0,5:,0]=np.tile([320,320,.95],17)
        decoded=decode_pose(raw,2,np.array([0,80]))
        self.assertEqual(len(decoded),1)
        np.testing.assert_allclose(decoded[0]['joints'][0],[160,120,.95],atol=1e-5)
        with self.assertRaises(ValueError):decode_pose(np.zeros((1,84,12)),1,np.array([0,0]))


class StudioLifecycleTests(unittest.TestCase):
    def test_failed_surface_calibration_preserves_existing_mapping(self):
        import asyncio
        from unittest.mock import patch
        from fastapi import HTTPException
        from app.studio import Studio
        class CameraContext:
            def oriented_depth(self):return np.full((480,640),2500,np.float32)
            def oriented_color(self):return np.zeros((480,640,3),np.uint8)
        studio=Studio();studio.projector={'width':1280,'height':720,'pattern_id':1}
        old=np.eye(3);studio.color_matrix=old
        async def no_delay(*args):pass
        with patch.object(studio,'context',return_value=CameraContext()),patch('app.studio.asyncio.sleep',side_effect=no_delay):
            with self.assertRaises(HTTPException):asyncio.run(studio.automatic_surface())
        self.assertIs(studio.color_matrix,old)
        self.assertFalse(studio.pattern)
        self.assertFalse(studio.calibrating)

    def test_once_per_new_sensor_frame_not_once_per_viewer(self):
        import time
        from types import SimpleNamespace
        from unittest.mock import patch
        from app.studio import Studio
        import tempfile
        depth=np.full((240,320),2500,np.float32)
        class Camera:
            depth_time=123.0
            def depth_ref(self):return depth
            def color_ref(self):return None
            def metadata(self):return {'color_size':None,'depth_size':[320,240]}
        ctx=SimpleNamespace(camera=Camera(),camera_settings={'mirror_depth':False,'mirror_color':False},distance_settings={'active':False},settings={},capture=SimpleNamespace(phase='idle'),projector_matrix=lambda d:None,distance_ready=lambda d:False)
        studio=Studio()
        with patch.object(studio,'context',return_value=ctx):
            studio.stop_event.clear()
            import threading
            worker=threading.Thread(target=studio._loop);worker.start()
            try:
                deadline=time.monotonic()+1
                while studio.sequence<1 and time.monotonic()<deadline:time.sleep(.005)
                self.assertEqual(studio.sequence,1)
                for _ in range(20):_ = studio.packet
                time.sleep(.07)
                self.assertEqual(studio.sequence,1)
                ctx.camera.depth_time=124
                deadline=time.monotonic()+1
                while studio.sequence<2 and time.monotonic()<deadline:time.sleep(.005)
                self.assertEqual(studio.sequence,2)
            finally:studio.stop_event.set();worker.join(timeout=1)

    def test_optional_pose_missing_weights_reported_without_breaking_depth(self):
        from app.pose import PoseWorker
        worker=PoseWorker(lambda:None)
        with self.assertRaises(ValueError):worker.start('/no/such/model.onnx')
        self.assertFalse(worker.latest)
