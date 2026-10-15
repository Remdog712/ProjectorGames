"""Reproducible processing-only benchmark; not camera/display latency or GPU inference."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent))
import json
import time
import cv2
import numpy as np
from app.realtime import Pipeline, DEFAULTS
from app.tracking import detect, homography


def run():
    cv2.setNumThreads(1)
    depth=np.full((480,640),2500,np.float32);surface=depth.copy()
    depth[100:400,250:390]=1700;depth[130:180,80:560]=1700
    matrix=homography([[0,0],[639,0],[639,479],[0,479]])
    pipeline=Pipeline()
    results={}
    for name,operation in [('legacy_640',lambda:detect(depth,surface,matrix,15,1500,100,True)),
                           ('studio_320',lambda:pipeline.process(depth,matrix,DEFAULTS,surface))]:
        samples=[]
        for i in range(320):
            start=time.perf_counter();operation();elapsed=(time.perf_counter()-start)*1000
            if i>=20:samples.append(elapsed)
        results[name]={'median_ms':round(float(np.median(samples)),3),'p95_ms':round(float(np.percentile(samples,95)),3),'samples':len(samples)}
    results['scope']='One synthetic 640x480 body silhouette; CPU processing only, no camera, transport, JPEG, display, or pose inference.'
    print(json.dumps(results,indent=2))
    return results

if __name__=='__main__':run()
