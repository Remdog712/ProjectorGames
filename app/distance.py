"""Depth band selected by one surface point and one closer body point."""
import numpy as np
import cv2


def sample_depth(depth, x, y):
    if depth is None:
        raise ValueError('No fresh depth image. Connect the depth camera first.')
    if not np.isfinite([x, y]).all() or not (0 <= x < depth.shape[1] and 0 <= y < depth.shape[0]):
        raise ValueError('Select a point inside the depth image.')
    x, y = int(x), int(y)
    patch = depth[max(0,y-2):y+3, max(0,x-2):x+3]
    valid = patch[np.isfinite(patch) & (patch > 0)]
    if valid.size < 3:
        raise ValueError('No reliable depth at this point. Click a nearby solid part of the surface or chest.')
    return float(np.median(valid))


def band_mask(depth, closest_mm, surface_mm, margin_mm=10):
    if closest_mm is None or surface_mm is None or closest_mm >= surface_mm-margin_mm:
        return np.zeros(depth.shape, np.uint8)
    mask = (np.isfinite(depth) & (depth > 0) & (depth >= closest_mm) & (depth <= surface_mm-margin_mm)).astype(np.uint8)*255
    return cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3,3),np.uint8))
