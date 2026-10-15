# ProjectorGames

A local projector playground with a browser setup GUI, four-corner homography calibration, depth-relative contact detection, and extensible canvas games. The first game draws rings around contact blobs; a bubble game demonstrates another interaction. Mouse and multitouch simulation work without hardware.

## Run

Python 3.12 recommended. From this checkout:

```sh
python -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Open the local server in a browser. On Windows use `.venv\Scripts\python.exe`. Click **Open projector window**, move that window onto the projector display, and double-click the play area for fullscreen. The control screen stays on the operator display. Game, ring color, size, and simulation settings synchronize between windows on the same browser origin.

## Astra hardware (optional, requires device validation)

The hardware adapter targets **legacy Astra devices supported by OpenNI2**. Astra variants have different SDK requirements; this adapter is not claimed compatible with every Astra or Astra 2. Confirm the model and host OS before choosing the vendor SDK. Install Orbbec's matching, trusted OpenNI2 runtime and USB drivers/permissions, then install the optional Python binding with `.venv/bin/python -m pip install openni==2.3.0`. Set `OPENNI2_REDIST` to its runtime library directory if not automatically found. Start with `PROJECTORGAMES_CAMERA=openni` in the environment. No credentials or cloud service are required. Do not disable driver signatures or TLS checks to install dependencies.

The cloud environment has no physical camera, projector, or USB passthrough; actual camera acquisition and touch accuracy must be validated on the installation machine. Camera errors appear in the GUI. Restart the server after correcting a device error or disconnect.

## Calibration

1. Fix the camera and projector in place, covering the same flat wall or floor. Use simulation first to verify projection and games.
2. Show corner targets on the projector. In the camera depth preview, click the projected area's top-left, top-right, bottom-right, bottom-left corners, as viewed in **projector coordinates**, even if the camera is rotated.
3. Save mapping, clear all people/objects from the surface, and capture its depth. The background is held only in memory; recapture after every server restart. Mapping is saved locally in `.local/calibration.json` (override with `PROJECTORGAMES_DATA`). Recalibrate after resolution, mounting, or surface changes.
4. Disable simulation. Tune the near/far depth band and minimum blob area. Defaults detect objects 15–100 mm closer to the camera than the empty surface. These are proximity contacts, not skeletal hand recognition or proof of physical touch. Feet and hands can produce multiple or merged blobs. Invalid depth is excluded.

Homography assumes a planar surface and a stable camera viewpoint. It maps image-space contact centroids, with parallax away from the plane. Accurate physical touch may require a fitted 3D plane, camera intrinsics, lens correction, temporal tracking, and hardware-specific tuning. A single background frame is a starting point, not a production calibration system. Keep projected intensity comfortable and supervise children; mount equipment securely.

## Add games

The canvas loop in `app/static/app.js` supplies normalized contacts `{x, y}` in `[0,1]`, independent of resolution. Add a game option in `index.html` and a renderer in the loop. Camera acquisition (`camera.py`), detection/mapping (`tracking.py`), and HTTP endpoints (`main.py`) are independent of game rendering. Current choices and styling are in the browser; shared geometry and thresholds are stored by the server. The local API is unauthenticated: bind to loopback unless you deliberately secure access.

## Validate

```sh
.venv/bin/python -m unittest discover -s tests -v
node --check app/static/app.js
```

Tests cover synthetic depth contacts, mapping, invalid depth, threshold rejection, and calibration errors. Hardware behavior remains a separate on-device check.

## Windows PC installation: wall and floor projection

Install 64-bit Python 3.12 with the Python launcher. Open PowerShell in the project folder:

```powershell
.\scripts\setup-windows.ps1
.\scripts\start-windows.ps1
```

If your machine blocks PowerShell scripts, follow your organization's script policy. You can also run the equivalent Python commands above directly using `.venv\Scripts\python.exe`; virtual environment activation is unnecessary.

In Windows display settings choose **Extend these displays**. Open the GUI in Edge or Chrome, open the projector window, drag it to the projector display, and double-click the play area for fullscreen. Keep the control GUI on the PC screen. Verify mouse simulation before connecting a camera.

For floor play, aim the projector and camera at the floor. Use the same four-corner mapping and empty-surface capture as for a wall. Camera orientation may differ: always select corners in the order shown by the projector, not the apparent order in the camera image. Place the camera so feet are visible without participants obscuring most of the surface. Recalibrate when moving between floor and wall installations.

Before installing a camera runtime, identify the device from the label on its underside/back or Windows **Device Manager → device Properties → Details → Hardware Ids**. Record the product name and USB VID/PID; do not assume all Astra models use OpenNI2. Once a legacy OpenNI2-compatible model and matching 64-bit vendor runtime have been confirmed:

```powershell
.\.venv\Scripts\python.exe -m pip install openni==2.3.0
.\scripts\start-windows.ps1 -Camera -OpenNI2Redist 'C:\path\to\OpenNI2\Redist'
```

The path is an example, not an included driver. Device-specific runtime installation, USB access, camera streaming, and wall/floor contact accuracy must be verified on the Windows PC. The launch scripts have not been executed on Windows in the cloud environment.
