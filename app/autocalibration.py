"""Projected coded markers and automatic planar RGB/depth foreground alignment.

Algorithms are implemented here; MagicSand was consulted for workflow, not copied.
"""
import cv2
import numpy as np

IDS = (10, 11, 12, 13)
DICT = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)


def marker_layout(width, height):
    side = int(min(width, height)*.18)
    pad = int(min(width, height)*.10)
    positions = [(pad,pad),(width-pad-side,pad),(width-pad-side,height-pad-side),(pad,height-pad-side)]
    return [(i, np.float32([[x,y],[x+side,y],[x+side,y+side],[x,y+side]])) for i,(x,y) in zip(IDS,positions)]


def projector_mapping(image, width, height):
    detector = cv2.aruco.ArucoDetector(DICT)
    for flip in (False, True):
        view = cv2.flip(image,1) if flip else image
        corners, ids, _ = detector.detectMarkers(view)
        if ids is None or not set(IDS).issubset(set(ids.flatten())):
            continue
        detected = {int(i):c.reshape(4,2) for i,c in zip(ids.flatten(),corners)}
        source, target = [], []
        for i, xy in marker_layout(width,height):
            pts=detected[i].copy()
            if flip: pts[:,0]=image.shape[1]-1-pts[:,0]
            source.extend(pts)
            target.extend(xy / [width,height])
        source,target=np.float32(source),np.float32(target)
        matrix,inliers=cv2.findHomography(source,target,cv2.RANSAC,.004)
        if matrix is None or inliers is None or inliers.sum()<12:
            continue
        predicted=cv2.perspectiveTransform(source[None],matrix)[0]
        error=float(np.sqrt(np.mean(np.sum(((predicted-target)*[width,height])**2,axis=1))))
        if error>6:
            raise ValueError(f'Marker fit error is {error:.1f} projector pixels. Refocus the projector and retry.')
        return matrix, {'reprojection_px':error,'points':int(inliers.sum()),'color_size':[image.shape[1],image.shape[0]]}
    raise ValueError('All four coded squares must be visible in the color camera. Check fullscreen projector, focus, exposure and framing, then retry. Existing mapping was kept.')


def fit_surface(depth):
    """Robust inverse-depth plane handles oblique flat walls without RGB registration."""
    h,w=depth.shape
    y,x=np.mgrid[0:h:12,0:w:12]
    z=depth[::12,::12]
    valid=np.isfinite(z)&(z>100)&(z<12000)
    x,y,z=x[valid]/w,y[valid]/h,z[valid]
    if len(z)<100: raise ValueError('Not enough valid depth to measure the surface.')
    a=np.column_stack([x,y,np.ones_like(x)])
    inv=1/z
    rng=np.random.default_rng(1)
    best=None
    for _ in range(80):
        chosen=rng.choice(len(z),3,replace=False)
        try: coef=np.linalg.solve(a[chosen],inv[chosen])
        except np.linalg.LinAlgError: continue
        estimate=a@coef
        predicted=np.divide(1,estimate,out=np.zeros_like(estimate),where=estimate>0)
        inliers=np.abs(predicted-z)<25
        if best is None or inliers.sum()>best.sum():best=inliers
    if best is None or best.sum()<max(100,len(z)*.55):
        raise ValueError('No dominant flat surface found. Clear the wall/floor and ensure it fills most of the depth view.')
    coef=np.linalg.lstsq(a[best],inv[best],rcond=None)[0]
    yy,xx=np.mgrid[:h,:w]
    denominator=coef[0]*xx/w+coef[1]*yy/h+coef[2]
    plane=np.divide(1,denominator,out=np.zeros_like(denominator,dtype=np.float64),where=denominator>0).astype(np.float32)
    rms=float(np.sqrt(np.mean((1/(a[best]@coef)-z[best])**2)))
    return plane,{'inverse_depth_plane':coef.tolist(),'inlier_fraction':float(best.mean()),'rms_mm':rms,'depth_size':[w,h]}


def render_surface(info):
    w,h=info['depth_size']; y,x=np.mgrid[:h,:w]; a,b,c=info['inverse_depth_plane']
    inv=a*x/w+b*y/h+c
    return np.divide(1,inv,out=np.zeros_like(inv,dtype=float),where=inv>0).astype(np.float32)


def foreground_alignment(empty_color, color, surface, depth):
    if empty_color.shape!=color.shape or surface.shape!=depth.shape:
        raise ValueError('Camera resolution changed. Run automatic surface calibration again.')
    ch,cw=color.shape[:2]; dh,dw=depth.shape
    w=320; h=max(120,round(ch*w/cw))
    delta=cv2.absdiff(empty_color,color)
    rgb=(np.max(delta,axis=2)>30).astype(np.uint8)*255
    rgb=cv2.resize(rgb,(w,h),interpolation=cv2.INTER_NEAREST)
    dep=((depth>0)&(surface>0)&(surface-depth>60)&(surface-depth<2500)).astype(np.uint8)*255
    dep=cv2.resize(dep,(w,h),interpolation=cv2.INTER_NEAREST)
    def largest(mask):
        mask=cv2.morphologyEx(mask,cv2.MORPH_OPEN,np.ones((5,5),np.uint8))
        cs,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
        if not cs or cv2.contourArea(max(cs,key=cv2.contourArea))<500:
            raise ValueError('No clear body silhouette in both views. Keep the background unchanged and stand with arms/feet apart, then recapture.')
        result=np.zeros_like(mask);cv2.drawContours(result,[max(cs,key=cv2.contourArea)],-1,255,-1)
        return result
    rgb,dep=largest(rgb),largest(dep)
    best=None
    for mirrored in (False,True):
        candidate=cv2.flip(dep,1) if mirrored else dep
        tc=cv2.boundingRect(cv2.findNonZero(rgb));ic=cv2.boundingRect(cv2.findNonZero(candidate))
        sx,sy=ic[2]/tc[2],ic[3]/tc[3]
        initial=np.float32([[sx,0,ic[0]-sx*tc[0]],[0,sy,ic[1]-sy*tc[1]]])
        try:
            _,warp=cv2.findTransformECC(rgb.astype(np.float32)/255,candidate.astype(np.float32)/255,initial,cv2.MOTION_AFFINE,(cv2.TERM_CRITERIA_COUNT|cv2.TERM_CRITERIA_EPS,100,1e-5),None,5)
        except cv2.error: continue
        inv=np.vstack([cv2.invertAffineTransform(warp),[0,0,1]])
        flip=np.array([[-1,0,w-1],[0,1,0],[0,0,1]]) if mirrored else np.eye(3)
        matrix=inv@flip@np.diag([w/dw,h/dh,1])
        projected=cv2.warpPerspective(dep,inv@flip,(w,h),flags=cv2.INTER_NEAREST)>0
        target=rgb>0
        iou=float(np.logical_and(projected,target).sum()/max(1,np.logical_or(projected,target).sum()))
        linear=inv[:2,:2]
        if abs(np.linalg.det(linear))<.25 or abs(np.linalg.det(linear))>4: continue
        if best is None or iou>best[0]: best=(iou,np.diag([cw/w,ch/h,1])@matrix,mirrored)
    if best is None or best[0]<.60:
        raise ValueError('Automatic silhouettes did not align reliably. Hold a clear star pose, avoid shadows/moving objects, and retry. Previous alignment was kept.')
    return best[1],{'silhouette_overlap':best[0],'reflection_detected':best[2],'depth_size':[dw,dh],'color_size':[cw,ch]}
