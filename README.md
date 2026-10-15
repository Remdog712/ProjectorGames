# ProjectorGames

Local projector games controlled by depth-camera body movement. Includes a setup GUI, color/depth previews, delayed captures, color-to-projector mapping, and depth-to-color alignment. Astra Pro depth uses OpenNI2; its separate color camera uses Windows UVC capture.

## Windows: launch from the computer

Install 64-bit Python 3.12 with the Python launcher. Connect the camera and install Orbbec's matching, trusted x64 OpenNI2 runtime and USB driver. Keep `OpenNI2.dll` and its accompanying folders together. Close Windows Camera and other viewers.

**Double-click `Start ProjectorGames.cmd`.** It creates the Python environment if needed, installs the pinned direct dependencies (including the OpenNI Python binding), starts the app, and opens its browser GUI. Keep the terminal open; Ctrl+C stops the server. No PowerShell execution-policy change or virtual environment activation is needed.

After pulling an update, stop the previous server and double-click again. The launcher installs updated requirements before startup. No raw DLL downloads or disabling TLS/driver signature checks are needed.

### First-time camera connection

In **1 · Connect cameras**, check the runtime folder and click **Connect / reconnect**. The app searches `C:\Program Files\Orbbec` for `OpenNI2.dll`; you can paste the correct folder or full DLL path. Choose the color camera number: commonly 0 or 1; if you see your PC webcam, try another number and reconnect. The GUI shows depth and color errors separately.

Connection settings are saved in `.local/camera.json` and reconnect automatically on future launches. A saved GUI configuration takes precedence over old environment variables. If a driver hangs during disconnection, restart the app. Changing camera number/runtime clears the calibration and empty surface; calibrate the newly selected camera before playing.

## Calibrate alone, entirely from the PC

No helper and no touching a projected wall corner are required.

1. **Empty surface:** choose a 5–20 second delay, click **Capture empty surface after delay**, and leave the camera view. A countdown appears in the GUI and on the projector. Keep the wall/floor and furniture unchanged.
2. **Projected area:** use Windows **Win+P → Extend**. Open the projector window, move it onto that display, and double-click its game area for fullscreen. Enable **Show calibration rectangle**. From the PC, select the four outer projected rectangle corners in the **color** preview: top-left, top-right, bottom-right, bottom-left. These define the play area's edges; they are separate from the distance bounds.
3. **Star pose:** click **Capture star pose after delay**, go near the wall, face the camera, and spread hands and feet. Hold still until the countdown ends, then return to the PC. Both camera images are retained **server-side**, so they stay frozen while you select points.
4. **Body pairing:** select the same visible physical points in each frozen image, in this order: image-left hand in color, same hand in depth; image-right hand in color/depth; image-right foot in color/depth; image-left foot in color/depth. Spread the four points into a convex shape. Follow the instruction above the images. Use **Undo last selection** to fix a click; resetting body pairs leaves the projected corners intact.
5. Click **Save play area + body alignment**, then **Return to live preview** and test movement. **Your body controls the game. Mouse movement never generates game contacts.**

Select **Color**, **Depth**, **Color + depth**, or **Active regions** to preview. A single-preview view automatically switches from color to depth when pairing points. You can cancel any countdown or recapture a pose. Near a wall, pose close to the interaction plane without needing to touch it. For floor projection, use visible corresponding body points close to the floor plane or another flat alignment target; a standing pose far above the floor is not an accurate floor-plane calibration.

## Lower and upper distance bounds

Both bounds measure the difference from the captured empty surface, along the camera's depth axis—not height on the wall:

- **Ignore closer than:** lower bound, in centimeters. Pixels nearer the surface than this are ignored. A small positive gap helps reject wall noise.
- **Ignore farther than:** upper bound, in centimeters. Pixels farther in front of the surface than this are ignored.

Start at **1.5–150 cm** for body movement. A band like **1.5–10 cm** concentrates on near-surface interaction. For a tilted camera these are depth-axis distances, not perpendicular distances to a fitted 3D wall plane.

After an empty-surface capture and frozen body capture, **Suggest bounds from frozen body pose** estimates a range from changed depth pixels. Review **Active regions**: included pixels are green, the remainder is dimmed. Suggestions include any changed objects, not just a recognized person. Adjust values and click **Apply distance bounds** to persist them. Unsaved input previews the proposed band; gameplay uses the last applied/saved values.

**Whole body** mode samples region centers and silhouette extremes so hands/feet connected to the torso can interact. It is geometric foreground tracking, **not anatomical joint recognition**. **Near-surface contact** mode uses region centers. Minimum region area filters small blobs. Multiple people may merge; invalid/missing depth cannot produce interactions.

## Accuracy and retained settings

Two homographies map depth pixels → color pixels → projector coordinates. The paired transform approximates one flat plane; it is not factory RGB/depth registration, lens correction, or a full 3D calibration. Alignment accuracy changes away from the calibration plane. RGB/depth acquisition is not hardware synchronized; hold the pose still. Check rings at multiple positions and re-pair near the interaction surface if offset.

Mapping and bounds live in `.local/calibration.json`; camera settings live in `.local/camera.json`. Use `PROJECTORGAMES_DATA` to change the local settings directory. Empty surface and frozen images stay in memory only: **capture a fresh empty surface each app restart**. Recalibrate when moving equipment, switching camera index, changing resolution, or moving between wall and floor. Contacts are suppressed when expected frame dimensions do not match calibration.

The cloud machine has no Astra or projector. Automated tests verify synthetic depth/color data and browser behavior; actual Windows SDK loading, UVC capture, alignment accuracy, and body interaction require testing on the installation PC.

## Python development

From the existing checkout:

```sh
python -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

On Windows use `.venv\Scripts\python.exe`. You can run `scripts/launch.py` with that Python to open the browser after the server is ready. GUI camera connection requires no shell variables. Existing `PROJECTORGAMES_CAMERA=openni`, `OPENNI2_REDIST`, and `PROJECTORGAMES_COLOR_INDEX` remain first-launch defaults when saved camera settings do not exist.

The unauthenticated API controls local hardware and writes local settings. Keep it bound to loopback. No account, secret, or cloud service is required.

## Extend games

`app/static/app.js` consumes normalized depth contacts `{x,y}` in `[0,1]`, independent of projector resolution. Add an option in `index.html` and a renderer in the game loop. Rendering is separate from `camera.py` (device acquisition), `capture.py` (delayed frozen captures), `tracking.py` (surface difference and silhouette samples), and `main.py` (HTTP/configuration). Ring color, radius, game choice, and calibration rectangle synchronize across operator/projector windows on the same browser origin.

## Validate

```sh
.venv/bin/python -m unittest discover -s tests -v
node --check app/static/app.js
.venv/bin/python -m pip check
```

Tests exercise mapping, depth bands, whole-body extremes, invalid depth, configuration persistence, delayed/frozen capture, cancellation, missing cameras, stale frames, and preview encoding. Windows launchers and vendor device behavior must be checked on Windows.

### Depth works but color is missing

Under **1 · Connect cameras**, check the color camera number and **Windows color capture** backend. **Auto** tries DirectShow first, then Media Foundation if opening or the first frame fails. To try the Windows Camera-style API explicitly, select **Media Foundation** and click **Retry color only**. This leaves depth streaming and the empty-surface reference intact. Close Windows Camera/other viewers before retrying. Camera numbering can differ between backends, so try 0/1/2 and visually verify the selected camera. Changing the number or backend clears the saved mapping, since it may select a different camera; recalibrate after finding the correct feed.

Color capture requests 640×480 at 30 fps to avoid an unnecessarily large default stream; the driver may negotiate a different supported mode. The GUI status reports the active backend and camera number. A driver blocked inside a native open/read call may require restarting the app; the retry does not launch a second stream over a hung one. Hardware-specific simultaneous depth/color behavior still needs verification on the PC; passing mocked backend tests does not establish hardware compatibility.
