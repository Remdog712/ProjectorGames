"""Realtime application service: one worker, cached previews, latest-only WebSockets."""
import asyncio
import json
import threading
import time
from pathlib import Path
import cv2
import numpy as np
from fastapi import HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import Response
from pydantic import BaseModel, Field
from .realtime import Pipeline, DEFAULTS
from .pose import PoseWorker
from .autocalibration import DICT, marker_layout, projector_mapping, fit_surface, render_surface, foreground_alignment


class Tuning(BaseModel):
    processing_width:int=Field(default=320,ge=160,le=640)
    fps:int=Field(default=30,ge=10,le=60)
    median:int=Field(default=3,ge=0,le=5)
    opening:int=Field(default=1,ge=0,le=3)
    closing:int=Field(default=1,ge=0,le=3)
    stable_frames:int=Field(default=2,ge=1,le=5)
    min_area:int=Field(default=500,ge=20,le=100000)
    smoothing:float=Field(default=.65,ge=.1,le=1)
    gate:float=Field(default=.18,ge=.02,le=.5)
    surface_gap_mm:int=Field(default=25,ge=1,le=300)
    reach_mm:int=Field(default=1500,ge=50,le=4000)
    max_regions:int=Field(default=6,ge=1,le=12)


class ProjectorInfo(BaseModel):
    width:int=Field(ge=320,le=8192)
    height:int=Field(ge=200,le=8192)
    pattern_id:int=0


class ReachSample(BaseModel):
    x:float
    y:float
    capture_id:str


class Studio:
    def __init__(self):
        self.stop_event=threading.Event();self.thread=None;self.lock=threading.Lock()
        self.config=DEFAULTS.copy();self.pipeline=Pipeline();self.packet={'sequence':0,'contacts':[],'regions':[],'processing_ms':0,'fps':0}
        self.sequence=0;self.revision=0;self.frame_shape=None;self.plane=None;self.color_matrix=None;self.alignment=None
        self.surface_info=None;self.map_info=None;self.alignment_info=None;self.empty_color=None
        self.pattern=False;self.pattern_id=0;self.projector=None;self.preview_cache={};self.preview_times={}
        self.preview_demand={};self.error='';self.calibrating=False;self.message='Connect the camera, open projector, then run Auto surface setup.'
        self.pose=PoseWorker(self.color_frame)
        self.suspended=False
        self.last_frame=0;self.latest_mask=None;self.last_geometry=None
    def context(self):
        from . import main
        return main
    def depth_frame(self):
        c=self.context()
        if not hasattr(c.camera,'depth_ref'):return c.oriented_depth()
        frame=c.camera.depth_ref()
        return cv2.flip(frame,1) if frame is not None and c.camera_settings['mirror_depth'] else frame
    def color_frame(self):
        c=self.context()
        if not hasattr(c.camera,'color_ref'):return c.oriented_color()
        frame=c.camera.color_ref()
        return cv2.flip(frame,1) if frame is not None and c.camera_settings['mirror_color'] else frame
    def save(self):
        data={'tuning':self.config,'surface':self.surface_info,'mapping':self.map_info,'alignment_info':self.alignment_info,
              'color_matrix':None if self.color_matrix is None else self.color_matrix.tolist(),
              'alignment':None if self.alignment is None else self.alignment.tolist()}
        self.context().save_json('studio.json',data)
    def start(self):
        c=self.context()
        try:
            data=json.loads((c.DATA/'studio.json').read_text())
            self.config=Tuning(**data['tuning']).model_dump();self.surface_info=data.get('surface')
            self.map_info=data.get('mapping');self.alignment_info=data.get('alignment_info')
            self.color_matrix=np.array(data['color_matrix']) if data.get('color_matrix') else None
            self.alignment=np.array(data['alignment']) if data.get('alignment') else None
            if self.surface_info:self.plane=render_surface(self.surface_info)
        except (OSError,KeyError,TypeError,ValueError):pass
        # Small per-frame OpenCV operations are faster without a nested CPU thread pool.
        cv2.setNumThreads(1)
        self.stop_event.clear();self.thread=threading.Thread(target=self._loop,daemon=True);self.thread.start()
    def stop(self):
        self.stop_event.set()
        try:self.pose.stop()
        except RuntimeError:pass
        if self.thread:self.thread.join(timeout=3)
    def invalidate(self):
        self.color_matrix=self.alignment=self.plane=None
        self.surface_info=self.map_info=self.alignment_info=self.empty_color=None
        self.revision+=1;self.pipeline=Pipeline();self.preview_cache.clear();self.save()
    def _loop(self):
        last_key=None;last_tick=time.perf_counter();rate=0
        while not self.stop_event.is_set():
            started=time.perf_counter()
            try:
                c=self.context();stamp=getattr(c.camera,'depth_time',None)
                geometry=(tuple((k,str(v)) for k,v in c.camera_settings.items()),str(c.distance_settings),str(c.settings))
                if self.last_geometry is not None and geometry!=self.last_geometry:
                    self.pipeline=Pipeline();self.revision+=1
                self.last_geometry=geometry
                key=(stamp,self.revision)
                depth=self.depth_frame()
                if depth is None:
                    if not self.suspended:self.pipeline=Pipeline();self.suspended=True;last_key=None
                    self.packet={**self.packet,'contacts':[],'regions':[],'fps':0}
                    now=time.monotonic()
                    if now-self.preview_demand.get('color',0)<2 and now-self.preview_times.get('color',0)>.125:
                        color=self.color_frame()
                        if color is not None:
                            if color.shape[1]>640:color=cv2.resize(color,(640,round(color.shape[0]*640/color.shape[1])))
                            ok,jpg=cv2.imencode('.jpg',color,[cv2.IMWRITE_JPEG_QUALITY,70])
                            if ok:self.preview_cache['color']=jpg.tobytes();self.preview_times['color']=now
                    self.stop_event.wait(.03);continue
                if self.suspended:self.suspended=False;last_tick=time.perf_counter()-1/self.config['fps']
                if stamp is not None and key==last_key:
                    self.stop_event.wait(.003);continue
                last_key=key
                self.frame_shape=depth.shape
                matrix=self.matrix(depth)
                packet,mask,small=self.pipeline.process(depth,matrix,self.config,self.plane,{**c.distance_settings,'ready':c.distance_ready(depth)})
                now=time.perf_counter();instant=1/max(now-last_tick,.001);last_tick=now;rate=instant if not rate else .9*rate+.1*instant
                self.sequence+=1;self.last_frame=stamp if stamp is not None else time.monotonic();self.latest_mask=mask
                if self.calibrating or c.capture.phase=='countdown':packet['contacts']=[];packet['regions']=[]
                if self.pose.latest and time.monotonic()-self.pose.timestamp<.3 and self.color_matrix is not None and self.alignment is not None:
                    joints=[]
                    inverse=np.linalg.inv(self.alignment)
                    for person in self.pose.latest:
                        for index in (9,10,15,16):
                            x,y,confidence=person['joints'][index]
                            if confidence<.4:continue
                            uv=cv2.perspectiveTransform(np.float32([[[x,y]]]),self.color_matrix)[0,0]
                            xy=cv2.perspectiveTransform(np.float32([[[x,y]]]),inverse)[0,0]
                            mx,my=int(xy[0]*mask.shape[1]/depth.shape[1]),int(xy[1]*mask.shape[0]/depth.shape[0])
                            if 0<=mx<mask.shape[1] and 0<=my<mask.shape[0] and mask[max(0,my-2):my+3,max(0,mx-2):mx+3].any() and 0<=uv[0]<=1 and 0<=uv[1]<=1:
                                joints.append({'x':float(uv[0]),'y':float(uv[1]),'joint':index})
                    if not self.calibrating and c.capture.phase!='countdown':packet['contacts']=joints
                self.packet={**packet,'sequence':self.sequence,'fps':round(rate,1),'captured_at':time.time(),'calibrated':matrix is not None}
                self._previews(depth,mask)
                self.error=''
            except Exception as exc:self.error=f'{type(exc).__name__}: {exc}'
            self.stop_event.wait(max(0,1/self.config['fps']-(time.perf_counter()-started)))
    def matrix(self,depth):
        c=self.context()
        if self.color_matrix is not None:
            if self.alignment is None:return None
            info=self.alignment_info or {}
            color_size=(c.camera.metadata().get('color_size') if hasattr(c.camera,'metadata') else None)
            if tuple(info.get('depth_size',()))!=(depth.shape[1],depth.shape[0]):return None
            if color_size and tuple(info.get('color_size',()))!=tuple(color_size):return None
            return self.color_matrix@self.alignment
        return c.projector_matrix(depth)
    def _previews(self,depth,mask):
        now=time.monotonic();c=self.context()
        for kind,last in list(self.preview_demand.items()):
            if now-last>2 or now-self.preview_times.get(kind,0)<.125:continue
            if kind=='color':frame=self.color_frame()
            elif kind=='active':
                frame=cv2.cvtColor(cv2.resize(mask,(min(640,depth.shape[1]),round(depth.shape[0]*min(640,depth.shape[1])/depth.shape[1]))),cv2.COLOR_GRAY2BGR)
                frame[frame[:,:,0]>0]=(120,255,70)
            elif kind=='overlay':
                frame=self.color_frame()
                if frame is not None and self.alignment is not None:
                    native=cv2.resize(mask,(depth.shape[1],depth.shape[0]),interpolation=cv2.INTER_NEAREST)
                    registered=cv2.warpPerspective(native,self.alignment,(frame.shape[1],frame.shape[0]))
                    frame=frame.copy();frame[registered>128]=frame[registered>128]*.4+np.array([60,255,100])*.6
            else:frame=cv2.applyColorMap(np.uint8(np.clip(depth/5000,0,1)*255),cv2.COLORMAP_TURBO)
            if frame is None:continue
            if frame.shape[1]>640:frame=cv2.resize(frame,(640,round(frame.shape[0]*640/frame.shape[1])))
            ok,jpg=cv2.imencode('.jpg',frame,[cv2.IMWRITE_JPEG_QUALITY,70])
            if ok:self.preview_cache[kind]=jpg.tobytes();self.preview_times[kind]=now
    def status(self):
        c=self.context()
        meta=c.camera.metadata() if hasattr(c.camera,'metadata') else {'depth_size':None,'color_size':None}
        return {'tuning':self.config,'surface':self.surface_info,'mapping':self.map_info,'alignment':self.alignment_info,
                'pattern':self.pattern,'pattern_id':self.pattern_id,'projector':self.projector,'calibrating':self.calibrating,'message':self.message,
                'pose':{'status':self.pose.status,'provider':self.pose.provider,'inference_ms':self.pose.ms},
                'error':self.error,'capture':c.capture.status(),'camera_settings':c.camera_settings,'camera':meta,
                'depth_error':c.camera.error,'color_error':c.camera.color_error,'distance':c.distance_settings,
                'stats':{'processing_ms':self.packet['processing_ms'],'tracking_fps':self.packet['fps'],'sequence':self.sequence,
                         'frame_age_ms':round((time.monotonic()-self.last_frame)*1000) if self.last_frame else None}}
    async def automatic_surface(self):
        if self.calibrating:raise HTTPException(409,'Calibration is already running.')
        if not self.projector:raise HTTPException(409,'Open the projector window and make it fullscreen first.')
        c=self.context();
        if hasattr(c,'capture'):c.capture.cancel()
        self.calibrating=True;self.pattern=True;self.pattern_id+=1;self.message='Detecting projected coded squares. Keep the surface clear.'
        old=(self.color_matrix,self.plane,self.map_info,self.surface_info)
        run_revision=self.revision
        try:
            # Wait for the projector window to acknowledge that the pattern was drawn.
            generation=self.pattern_id
            for _ in range(40):
                if self.projector and self.projector.get('pattern_id')==generation:break
                await asyncio.sleep(.1)
            else:raise ValueError('The projector window did not display the pattern. Keep it visible and retry.')
            await asyncio.sleep(.6)
            color=c.oriented_color();depth=c.oriented_depth()
            if color is None or depth is None:raise ValueError('Both camera feeds must be live.')
            matrix,info=await asyncio.to_thread(projector_mapping,color,self.projector['width'],self.projector['height'])
            plane,surface=await asyncio.to_thread(fit_surface,depth)
            self.pattern=False
            await asyncio.sleep(.6)
            empty=c.oriented_color()
            if empty is None:raise ValueError('Color feed stopped before the reference frame was captured.')
            if self.revision!=run_revision:raise ValueError('Camera or tracking settings changed during calibration. Keep settings fixed and retry.')
            self.color_matrix,self.map_info,self.plane,self.surface_info=matrix,info,plane,surface
            self.empty_color=empty.copy();self.alignment=self.alignment_info=None
            self.pipeline=Pipeline();self.revision+=1;self.save()
            self.message='Surface and projector mapped. Now capture a star pose and press Auto align body.'
            return {'ok':True,'mapping':info,'surface':surface}
        except (ValueError,cv2.error) as exc:
            if self.revision==run_revision:self.color_matrix,self.plane,self.map_info,self.surface_info=old
            self.message=str(exc);raise HTTPException(400,str(exc))
        finally:self.pattern=False;self.calibrating=False
    def automatic_alignment(self):
        if self.calibrating:raise HTTPException(409,'Wait for surface calibration to finish first.')
        c=self.context()
        if self.empty_color is None or self.plane is None:raise HTTPException(409,'Run Auto surface setup with an empty surface first.')
        if c.capture.phase!='ready' or c.capture.kind!='pose':raise HTTPException(409,'Capture a delayed star pose first.')
        try:matrix,info=foreground_alignment(self.empty_color,c.capture.color,self.plane,c.capture.depth)
        except (ValueError,cv2.error) as exc:raise HTTPException(400,str(exc))
        self.alignment,self.alignment_info=matrix,info;self.pipeline=Pipeline();self.revision+=1;self.save()
        self.message='Body alignment saved. Verify the Overlay preview, then play.'
        return {'ok':True,'alignment':info}
    def attach(self,app):
        @app.get('/api/studio')
        def studio_status():return self.status()
        @app.post('/api/studio/tuning')
        def tuning(config:Tuning):
            if config.median not in (0,3,5):raise HTTPException(400,'Median filter must be Off, 3 or 5.')
            if config.surface_gap_mm>=config.reach_mm:raise HTTPException(400,'Surface gap must be smaller than maximum reach.')
            self.config=config.model_dump();self.pipeline=Pipeline();self.revision+=1;self.save();return {'ok':True}
        @app.post('/api/studio/projector')
        def projector(info:ProjectorInfo):self.projector=info.model_dump();return {'ok':True}
        @app.post('/api/studio/pose')
        def pose(config:dict):
            try:
                if config.get('path'):self.pose.start(str(config['path']))
                else:self.pose.stop()
            except (ValueError,RuntimeError) as exc:raise HTTPException(400,str(exc))
            return {'ok':True,'message':self.pose.status}
        @app.get('/api/studio/marker/{identity}.png')
        def marker(identity:int):
            if identity not in (10,11,12,13):raise HTTPException(404,'Unknown marker')
            _,png=cv2.imencode('.png',cv2.aruco.generateImageMarker(DICT,identity,256))
            return Response(png.tobytes(),media_type='image/png')
        @app.post('/api/studio/calibrate')
        async def calibrate():return await self.automatic_surface()
        @app.post('/api/studio/align')
        def align():return self.automatic_alignment()
        @app.post('/api/studio/reach')
        def reach(point:ReachSample):
            c=self.context();depth=c.preview_frame('depth',point.capture_id)
            if self.plane is None or self.plane.shape!=depth.shape:raise HTTPException(409,'Measure the surface first.')
            try:
                from .distance import sample_depth
                value=sample_depth(depth,point.x,point.y)
                z=sample_depth(self.plane,point.x,point.y);reach=int(z-value)
                if not 50<=reach<=4000:raise ValueError('Click your chest in front of the measured surface.')
            except ValueError as exc:raise HTTPException(400,str(exc))
            self.config['reach_mm']=reach;self.revision+=1;self.save();return {'ok':True,'reach_mm':reach}
        @app.get('/api/studio/preview/{kind}.jpg')
        def preview(kind:str):
            if kind not in ('color','depth','active','overlay'):raise HTTPException(404,'Unknown preview')
            self.preview_demand[kind]=time.monotonic()
            if kind not in self.preview_cache or time.monotonic()-self.preview_times.get(kind,0)>1:raise HTTPException(409,'Waiting for a fresh camera frame')
            return Response(self.preview_cache[kind],media_type='image/jpeg',headers={'Cache-Control':'no-store'})
        @app.websocket('/api/live')
        async def live(ws:WebSocket):
            await ws.accept();sequence=-1;last_status=0
            try:
                while True:
                    now=time.monotonic()
                    if now-self.last_frame>.25:
                        payload={**self.packet,'contacts':[],'regions':[]}
                    else:payload=self.packet
                    if payload['sequence']!=sequence or now-last_status>.5:
                        sequence=payload['sequence'];message={'type':'frame',**payload}
                        if now-last_status>.5:message['status']=self.status();last_status=now
                        await ws.send_json(message)
                    await asyncio.sleep(.01)
            except (WebSocketDisconnect,RuntimeError):pass
