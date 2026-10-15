"""Surface-relative body regions, mapped from camera pixels to projector UV."""
import cv2
import numpy as np


def homography(points):
    p = np.asarray(points, dtype=np.float32)
    if p.shape != (4, 2) or not np.isfinite(p).all():
        raise ValueError('Select four finite camera points.')
    contour = p.reshape(-1, 1, 2)
    if not cv2.isContourConvex(contour) or abs(cv2.contourArea(contour)) < 100:
        raise ValueError('Points must form a convex area in clockwise order. Spread hands and feet farther apart.')
    return cv2.getPerspectiveTransform(p, np.float32([[0, 0], [1, 0], [1, 1], [0, 1]]))


def interaction_mask(depth, background, near_mm=15, far_mm=1500):
    if background is None or depth.shape != background.shape:
        return np.zeros(depth.shape, dtype=np.uint8)
    delta = background.astype(np.float32) - depth.astype(np.float32)
    mask = ((depth > 0) & (background > 0) & (delta >= near_mm) & (delta <= far_mm)).astype(np.uint8) * 255
    return cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))


def detect(depth, background, matrix, near_mm=15, far_mm=100, min_area=100, body=False):
    if background is None or matrix is None:
        return []
    mask = interaction_mask(depth, background, near_mm, far_mm)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    hits = []
    for c in sorted(contours, key=cv2.contourArea, reverse=True)[:16]:
        if cv2.contourArea(c) < min_area:
            continue
        m = cv2.moments(c)
        if not m['m00']:
            continue
        locations = [(m['m10']/m['m00'], m['m01']/m['m00'])]
        if body:
            # Silhouette extremes let hands/feet interact even when connected to a torso.
            # These are geometry samples, not a skeletal pose model.
            hull = cv2.convexHull(c).reshape(-1, 2)
            directions = [(1,0),(-1,0),(0,1),(0,-1),(1,1),(-1,1),(1,-1),(-1,-1)]
            for direction in directions:
                p = hull[np.argmax(hull @ np.asarray(direction))]
                if all(np.linalg.norm(p-np.asarray(q)) >= 15 for q in locations):
                    locations.append(tuple(p))
        for x, y in locations:
            u, v = cv2.perspectiveTransform(np.float32([[[x, y]]]), matrix)[0, 0]
            if np.isfinite([u, v]).all() and 0 <= u <= 1 and 0 <= v <= 1:
                hits.append({'x': float(u), 'y': float(v)})
    return hits
