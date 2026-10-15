"""One latest-frame CPU pipeline shared by all viewers; no queued camera frames."""
import threading
import time
import cv2
import numpy as np

DEFAULTS={'processing_width':320,'fps':30,'median':3,'opening':1,'closing':1,'stable_frames':2,
          'min_area':500,'smoothing':.65,'gate':.18,'surface_gap_mm':25,'reach_mm':1500,'max_regions':6}


class RegionTracker:
    def __init__(self): self.tracks={};self.next_id=1
    def update(self,regions,smoothing=.65,gate=.18):
        used=set();result=[]
        pairs=[]
        for i,region in enumerate(regions):
            for identity,track in self.tracks.items():
                distance=np.linalg.norm(np.array(region['center'])-track['center'])
                if distance<gate:pairs.append((distance,i,identity))
        assigned={}
        for _,i,identity in sorted(pairs):
            if i not in assigned and identity not in used:assigned[i]=identity;used.add(identity)
        for i,region in enumerate(regions):
            identity=assigned.get(i)
            if identity is None:identity=self.next_id;self.next_id+=1
            old=self.tracks.get(identity)
            center=np.array(region['center'])
            points=np.array(region['points'])
            if old is not None:
                center=old['center']*(1-smoothing)+center*smoothing
                if old['points'].shape==points.shape:points=old['points']*(1-smoothing)+points*smoothing
            self.tracks[identity]={'center':center,'points':points,'missed':0}
            used.add(identity)
            result.append({**region,'id':identity,'center':center.tolist(),'points':points.tolist()})
        for identity in list(self.tracks):
            if identity not in used:
                self.tracks[identity]['missed']+=1
                if self.tracks[identity]['missed']>5:del self.tracks[identity]
        return result


class Pipeline:
    def __init__(self):
        self.history=None;self.tracker=RegionTracker();self.shape=None
    def process(self,depth,matrix,config,plane=None,band=None):
        started=time.perf_counter();h,w=depth.shape
        width=min(w,config['processing_width']);height=round(h*width/w)
        small=cv2.resize(depth,(width,height),interpolation=cv2.INTER_NEAREST) if width!=w else depth
        measured_valid=np.isfinite(small)&(small>0)
        if config['median'] in (3,5):small=cv2.medianBlur(small.astype(np.float32),config['median'])
        valid=measured_valid&np.isfinite(small)&(small>0)
        if plane is not None and plane.shape==depth.shape:
            surface=cv2.resize(plane,(width,height),interpolation=cv2.INTER_NEAREST)
            delta=surface-small
            mask=valid&(surface>0)&(delta>=config['surface_gap_mm'])&(delta<=config['reach_mm'])
        elif band and band.get('ready'):
            mask=valid&(small>=band['closest_mm'])&(small<=band['surface_mm']-band['margin_mm'])
        else:mask=np.zeros_like(valid)
        stable=config['stable_frames']
        if self.history is None or self.history.shape!=mask.shape:self.history=np.zeros(mask.shape,np.uint8)
        self.history=np.where(mask,np.minimum(self.history+1,stable),0).astype(np.uint8)
        mask=(self.history>=stable).astype(np.uint8)*255
        kernel=np.ones((3,3),np.uint8)
        if config['opening']:mask=cv2.morphologyEx(mask,cv2.MORPH_OPEN,kernel,iterations=config['opening'])
        if config['closing']:mask=cv2.morphologyEx(mask,cv2.MORPH_CLOSE,kernel,iterations=config['closing'])
        mask[~measured_valid]=0
        m=None if matrix is None else matrix@np.diag([w/width,h/height,1])
        if m is not None:
            try:
                poly=cv2.perspectiveTransform(np.float32([[[0,0],[1,0],[1,1],[0,1]]]),np.linalg.inv(m))[0]
                if np.isfinite(poly).all():
                    roi=np.zeros_like(mask);cv2.fillConvexPoly(roi,np.int32(np.clip(poly,-10000,10000)),255);mask&=roi
            except (np.linalg.LinAlgError,cv2.error):m=None
        contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
        regions=[];scale=width/w;accepted=np.zeros_like(mask)
        for contour in sorted(contours,key=cv2.contourArea,reverse=True)[:config['max_regions']]:
            area=cv2.contourArea(contour)
            if area<config['min_area']*scale*scale:continue
            cv2.drawContours(accepted,[contour],-1,255,-1)
            moments=cv2.moments(contour)
            if not moments['m00'] or m is None:continue
            cx,cy=moments['m10']/moments['m00'],moments['m01']/moments['m00']
            hull=cv2.convexHull(contour).reshape(-1,2)
            locations=[(cx,cy)]+[hull[np.argmax(hull@direction)] for direction in np.array([[1,0],[-1,0],[0,1],[0,-1],[1,1],[-1,1],[1,-1],[-1,-1]])]
            mapped=cv2.perspectiveTransform(np.float32([locations]),m)[0]
            points=[p.tolist() for p in mapped[1:] if np.isfinite(p).all() and 0<=p[0]<=1 and 0<=p[1]<=1]
            if not points:continue
            center=mapped[0]
            regions.append({'center':center.tolist(),'points':points,'area':round(area/(scale*scale))})
        mask&=accepted
        regions=self.tracker.update(regions,config['smoothing'],config['gate'])
        contacts=[]
        for region in regions:
            unique=[]
            for point in region['points']:
                if all(np.linalg.norm(np.array(point)-other)>.012 for other in unique):unique.append(np.array(point))
            contacts.extend({'x':float(p[0]),'y':float(p[1]),'track_id':region['id']} for p in unique)
        return {'contacts':contacts,'regions':regions,'processing_ms':round((time.perf_counter()-started)*1000,2)},mask,small
