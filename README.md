# ProjectorGames Studio

Depth-controlled projector games for a wall or floor, using an original Orbbec Astra Pro. The Studio rebuild replaces per-window HTTP tracking polling with a shared realtime worker, GPU-capable rendering, automatic calibration and noise controls.

## Windows: update and start

Stop the old app with Ctrl+C, run `git pull`, and double-click **Start ProjectorGames.cmd**. It refreshes Python dependencies and opens the GUI. Python 3.12 x64, the matching Orbbec OpenNI2 x64 runtime, and the USB driver are required. Keep the launch terminal open.

The working camera connection settings from previous versions are retained. In Setup, verify the runtime folder, color camera number and backend. **Retry color only** leaves depth running. Close Windows Camera and other viewers. Color/depth mirror controls are retained; changing camera coordinates invalidates automatic calibration.

## New setup: no manual hand/foot point pairs

1. **Open projector.** Use Win+P → Extend, move the output window onto the projector display, and double-click its output for fullscreen. Frame the empty, flat wall/floor in both camera views. It should occupy most of the depth view.
2. **Auto surface setup.** Four uniquely coded squares appear. OpenCV detects all 16 corners, maps color coordinates to projector coordinates, and robustly fits an inverse-depth surface plane. The squares disappear and a reference color frame is captured. Keep people, hands and moving objects out of view during this step. Focus/exposure must make all four codes readable. The UI reports projector reprojection error and surface-fit RMS. Failed calibration preserves the last valid calibration.
3. **Capture body after delay.** Stand near the interaction surface with feet apart and one hand higher than the other. Hold still until capture finishes, then return to the PC. Click **Auto align body**: RGB and depth foreground silhouettes are matched automatically, including testing reflection. No eight-point clicking. A low-overlap match is rejected. Check **Registered overlay** at several positions before play.
4. **Set range and play.** In Noise & tracking, set the surface gap and maximum reach, or use **Capture closest body position**, return, and click your chest once in the frozen depth image. **Return to live & play** pauses the operator preview and prioritizes game rendering.

Preview choices are Color, Depth, Registered overlay and Active regions. Frozen captures are held on the server while you return to the PC. To re-align bodies after restarting, run Auto surface setup again to refresh its empty reference. Saved successful mapping/surface/alignment can otherwise be reused with unchanged hardware.

### Calibration limits

This borrows the automatic-pattern/surface/filtering workflow from [MagicSand](https://github.com/thomwolf/Magic-Sand); the algorithms here are independently implemented. MagicSand's [calibration documentation](https://github.com/thomwolf/Magic-Sand#calibration) explains its projected patterns and Kinect registration. The Astra Pro's separate UVC color camera does not provide that Kinect registration through this adapter.

Automatic silhouette alignment is an **affine approximation at the captured body plane**, not factory RGB/depth calibration or full 3D projector extrinsics. It can be affected by shadows, matching clothing/background colors, moved objects, symmetric poses, occlusion, or moving away from the calibrated plane. Keep one hand higher to disambiguate reflection. If the overlay is poor, clear the scene, capture a more distinct asymmetric pose near the surface and retry. It is deliberately rejected below 60% mask overlap; a high overlap alone still does not prove physical touch accuracy.

The measured surface model accounts for planar depth variation across a tilted wall/floor. Gaps are measured **along the camera depth axis**, not as perpendicular millimeters to a 3D plane. Missing depth is excluded. The dominant surface must cover at least 55% of valid sampled pixels; a different dominant plane or uneven floor needs reframing. Wall and floor installations require separate calibration.

## Performance architecture

- One latest-frame CPU tracking worker for all windows; each sensor timestamp is processed once. No queue of old frames and no per-viewer tracking computation.
- Default 320×240 processing for a 640×480 sensor: one quarter of the pixels. 640-wide detailed mode remains available.
- Immutable published camera frames avoid copying both RGB and depth for each request.
- Normalized contact/region data is pushed by WebSocket. Stale game contacts are cleared after 250 ms without fresh processed depth.
- JPEG previews are encoded once per demanded view, capped at 8 FPS, cached across viewers and independent of tracking. Play mode pauses operator preview. Frozen captures are fetched once.
- Game rings are WebGL point sprites when supported, with Canvas fallback. Browser/driver settings determine whether WebGL runs on a physical GPU; the UI reports the selected renderer. Depth processing uses native OpenCV/NumPy on CPU, not CUDA.
- Optional pose inference has its own lower-frequency worker so a slow model cannot block depth tracking.

The UI reports tracking FPS, processing milliseconds, frame age and render FPS. A 30 FPS depth camera still limits input updates to 30 FPS; selecting 60 cannot create extra camera frames. Two-frame stability adds one camera frame of latency; use Lowest latency for one-frame acceptance.

### Reproducible benchmark

```powershell
.\.venv\Scripts\python.exe scripts\benchmark.py
```

On the development cloud CPU, with 300 timed iterations of a single synthetic 640×480 silhouette and one OpenCV thread, while the synthetic browser fixture was also running:

| Processing path | Median | p95 |
| --- | ---: | ---: |
| Previous full-frame detector | 1.336 ms | 3.035 ms |
| Studio, default 320-wide processing | 0.838 ms | 1.483 ms |

This is **processing only**, not camera-to-projector latency, actual Astra FPS, GPU speed or Windows performance. The browser integration test used synthetic camera frames and a software WebGL implementation; it does not establish physical GPU acceleration. Measure the live counters on the installation PC.

## Noise and tracking controls

Choose a preset (Balanced, Lowest latency, Noisy room, Near-surface), then **Apply & save**. Inspect Active regions while moving.

- **Surface gap:** rejects depth near the fitted wall/floor; increase to suppress wall noise.
- **Maximum reach:** excludes objects too far in front of the fitted surface.
- **Small-region rejection:** raw-camera pixel-area threshold; larger values reject speckles but can remove small hands.
- **Stable frames:** rejects transient detections; higher values cost latency.
- **Median depth filter:** smooths isolated depth spikes; Off minimizes work, 3×3 balances quality and speed.
- **Remove speckles / fill holes:** morphological opening/closing; strong values can remove fingers or merge neighbors.
- **Follow movement:** higher values reduce smoothing delay; lower values reduce jitter.

Tracked connected regions have stable IDs with gated nearest-center association and temporal smoothing. They are **not anatomical identities**; touching people can merge, crossing people can swap IDs, and long disappearances create new tracks. Rings sample silhouette extremes rather than pretending to recognize hands.

## Optional 17-joint person pose and Windows GPU inference

The app includes a local YOLOv8-pose ONNX adapter. **Model weights are not included**, no model is downloaded automatically, and this is optional: depth body tracking works without it.

Supply a compatible exported model: YOLOv8n-pose, fixed `[1,3,640,640]` float32 input, raw `[1,56,N]` output (17 joints), no embedded NMS. For example, using the official [Ultralytics export workflow](https://docs.ultralytics.com/modes/export/) in a separate environment with its dependencies:

```text
yolo export model=yolov8n-pose.pt format=onnx imgsz=640 batch=1 dynamic=False half=False nms=False
```

Use a model/license appropriate for your deployment. In Noise & tracking → Person tracking & skeletons, enter the local `.onnx` path and load it. Without ONNX Runtime, OpenCV DNN supplies CPU inference. For compatible Windows DirectX hardware, install the optional runtime into the app environment:

```powershell
.\.venv\Scripts\python.exe -m pip install onnxruntime-directml
```

Restart the app after installing a runtime. The adapter prefers DirectML, then an available CUDA provider, then CPU, and reports the **actual provider**. Installing the normal CPU `onnxruntime` package does not enable GPU inference. Do not install conflicting ONNX Runtime distributions in the same environment.

When confident poses and valid depth are available, wrists/ankles supply game contacts and are gated by the depth interaction mask. Otherwise depth-region tracking remains available. Pose inference uses RGB and requires projector/color/depth alignment. This is not a native Kinect skeleton SDK; it does not give equivalent depth-joint accuracy, robust occlusion handling or guaranteed person identity. The adapter/output decoder is tested with synthetic tensors; real ONNX weights and DirectML hardware have **not** been validated in the cloud. Model selection, provider loading, physical-camera accuracy and inference latency must be checked on the PC. Pose models are loaded per app session.

Orbbec's [SDK compatibility notes](https://github.com/orbbec/OrbbecSDK) distinguish legacy OpenNI devices from newer UVC devices; Astra Pro Plus/Astra 2 support must not be assumed to cover the original Astra Pro. The working OpenNI adapter is retained.

## Saved files and developer layout

`.local/studio.json` stores Studio tuning, surface plane and automatic transforms. Existing `.local/camera.json` is reused. Previous `.local/calibration.json` / `.local/distance.json` remain for legacy compatibility; a valid prior map can be used until new automatic setup is saved. A new automatic projector map requires new automatic body alignment before game output resumes. Coordinate/device changes invalidate Studio calibration. Empty RGB references and frozen images are memory-only. Override local storage with `PROJECTORGAMES_DATA`.

- `camera.py`: OpenNI depth and Windows UVC RGB acquisition.
- `autocalibration.py`: coded-marker map, robust inverse-depth plane and silhouette matching.
- `realtime.py`: downsampled masks, filtering, body-region tracking and smoothing.
- `studio.py`: single worker, cached previews, WebSocket publication and calibration endpoints.
- `pose.py`: optional local ONNX/OpenCV pose worker.
- `static/studio.*`: setup/tuning/play GUI; `renderer.js`: GPU-capable game renderer.
- Legacy APIs/files remain for compatibility; the new GUI does not poll them for gameplay.

The API controls local devices without authentication; bind it to `127.0.0.1`. No cloud account or credentials are needed. No physical Astra/projector is attached to the cloud machine.

```sh
python -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
.venv/bin/python -m unittest discover -s tests -v
node --check app/static/studio.js
node --check app/static/renderer.js
.venv/bin/python -m pip check
.venv/bin/python scripts/benchmark.py
```

On Windows use `.venv\Scripts\python.exe`. Automated tests cover marker/reflection detection, surface tilt/outliers, foreground alignment rejection, region IDs, filtering, once-per-sensor-frame processing, failed-calibration preservation, pose decoding and earlier camera/capture behavior. Actual Windows drivers, camera latency, GPU acceleration, projector focus and physical alignment require testing on the installation machine.
