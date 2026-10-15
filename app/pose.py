"""Optional local YOLOv8-pose ONNX adapter. No weights or downloads are bundled."""
import threading
import time
from pathlib import Path
import cv2
import numpy as np


def decode_pose(output,scale,pad,threshold=.35):
    raw=np.asarray(output)
    if raw.ndim==3:raw=raw[0]
    if raw.ndim!=2:raise ValueError('Expected raw YOLOv8-pose output [1,56,N]. Export without NMS.')
    if raw.shape[0]==56:raw=raw.T
    if raw.shape[1]!=56:raise ValueError('Model must have 17 pose joints (56 output channels).')
    rows=raw[raw[:,4]>=threshold]
    if not len(rows):return []
    boxes=[[float(r[0]-r[2]/2),float(r[1]-r[3]/2),float(r[2]),float(r[3])] for r in rows]
    kept=np.asarray(cv2.dnn.NMSBoxes(boxes,rows[:,4].tolist(),threshold,.45)).flatten()
    result=[]
    for i in kept[:6]:
        row=rows[int(i)];joints=row[5:].reshape(17,3).copy()
        joints[:,:2]=(joints[:,:2]-pad)/scale
        result.append({'score':float(row[4]),'joints':joints.tolist()})
    return result


class PoseWorker:
    def __init__(self,source):
        self.source=source;self.stop_event=threading.Event();self.thread=None;self.latest=[];self.timestamp=0
        self.status='Depth regions only';self.provider='';self.model='';self.ms=0
    def stop(self):
        self.stop_event.set()
        if self.thread:self.thread.join(timeout=3)
        if self.thread and self.thread.is_alive():raise RuntimeError('Pose inference is still closing; restart the app before loading another model.')
        self.latest=[];self.status='Depth regions only'
    def start(self,path):
        file=Path(path)
        if not file.is_file() or file.suffix.lower()!='.onnx':raise ValueError('Select an existing local exported YOLOv8-pose .onnx file. Weights are not included.')
        self.stop();self.model=str(file);self.stop_event.clear();self.status='Loading local pose model…'
        self.thread=threading.Thread(target=self._run,daemon=True);self.thread.start()
    def _run(self):
        try:
            try:
                import onnxruntime as ort
                providers=[p for p in ('DmlExecutionProvider','CUDAExecutionProvider','CPUExecutionProvider') if p in ort.get_available_providers()]
                options=ort.SessionOptions();options.enable_mem_pattern=False;options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL
                session=ort.InferenceSession(self.model,sess_options=options,providers=providers)
                input_info=session.get_inputs()[0]
                if input_info.shape!=[1,3,640,640]:raise ValueError('Export a fixed 640×640, batch-1, float32 YOLOv8-pose model.')
                self.provider=session.get_providers()[0]
                run=lambda blob:session.run(None,{input_info.name:blob})[0]
            except ImportError:
                net=cv2.dnn.readNetFromONNX(self.model);net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV);net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
                def run(blob):net.setInput(blob);return net.forward()
                self.provider='OpenCV CPU'
            while not self.stop_event.is_set():
                image=self.source()
                if image is None:self.stop_event.wait(.1);continue
                start=time.perf_counter();h,w=image.shape[:2];scale=min(640/w,640/h)
                nw,nh=round(w*scale),round(h*scale);px,py=(640-nw)//2,(640-nh)//2
                frame=np.full((640,640,3),114,np.uint8);frame[py:py+nh,px:px+nw]=cv2.resize(image,(nw,nh))
                blob=cv2.dnn.blobFromImage(frame,1/255,swapRB=True)
                self.latest=decode_pose(run(blob),scale,np.array([px,py]));self.timestamp=time.monotonic()
                self.ms=round((time.perf_counter()-start)*1000,1);self.status='Running · '+self.provider
                self.stop_event.wait(max(0,.1-(time.perf_counter()-start)))
        except Exception as exc:self.status='Pose unavailable: '+str(exc);self.latest=[]
