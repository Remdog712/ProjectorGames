# ProjectorGames

Local wall/floor projector games controlled by depth-camera body movement. Includes color/depth previews, mirror correction, delayed frozen captures, projector mapping, and a playable depth band selected with **one surface click and one chest click**.

## Windows: start the app

Install 64-bit Python 3.12 with the Python launcher, plus the trusted Orbbec x64 OpenNI2 runtime and USB driver for the original Astra Pro. Keep `OpenNI2.dll` and its accompanying folders together. Close Windows Camera and other viewers.

Double-click **Start ProjectorGames.cmd**. It installs the direct pinned Python dependencies and launches the browser after the server is ready. Keep its terminal open; Ctrl+C stops the server. No PowerShell script-policy change or manually setting camera variables is needed.

After pulling an update, stop the old server and double-click the launcher again. If the GUI is stale, Ctrl+F5 refreshes it. UI assets are content-versioned and served without caching. Script startup failures appear at the top of the page.

## 1. Connect and orient the cameras

The original Astra Pro exposes depth through OpenNI2 and color through a separate UVC camera.

- **Runtime folder:** auto-discovered under `C:\Program Files\Orbbec`, or paste the folder containing `OpenNI2.dll` (the complete DLL path is also accepted).
- **Color camera number:** commonly 0 or 1. Close other camera viewers. If the PC webcam appears, try another number.
- **Color capture backend:** Auto tries DirectShow, then Media Foundation if opening or the first frame fails. Select Media Foundation explicitly if needed. Camera numbering can differ across backends.
- **Retry color only:** keeps working depth running while retrying RGB. If a native driver hangs, restart the app rather than opening multiple streams over it.

Choose **Color + depth**, move one hand, and compare its position in both images. If they are mirrored relative to one another, toggle **Mirror color horizontally** or **Mirror depth horizontally**, then click **Apply mirror orientation**. Adjust one view until the same physical hand is on the same side in both. You can also keep both unflipped and pair reflected points carefully: the alignment supports opposite winding, but matching the wrong physical limb produces an incorrect mapping.

Mirror settings are applied to the actual pixel coordinates used by depth tracking, measurements, and frozen captures—not just a CSS preview. Changing mirrors clears old mapping and distance measurements; select them again afterward. Camera settings are saved in `.local/camera.json` and reconnect on the next launch.

## 2. One surface click sets the lower boundary

Click **Select surface point**, then click one solid spot on the wall or floor in the **Depth** image. The app takes a median of nearby valid pixels and displays the measured distance from the camera. You do not need an empty-scene capture or a helper touching the wall.

Choose the actual interaction surface, not furniture or a person. Invalid/missing depth produces an error so you can click elsewhere. Selecting a new surface resets the chest limit, which must be closer to the camera.

## 3. Mark the projected area from the PC

Use **Win+P → Extend**. Open the projector window, move it onto the projector display, and double-click the game area for fullscreen. Enable **Show calibration rectangle**.

From the PC, select its four outer corners in the **color image**: top-left, top-right, bottom-right, bottom-left, in projector coordinates. No touching the wall is needed. These image-space corners define where interaction can occur; they are independent of the selected depth band.

## 4. Align color and depth using a frozen pose

Choose a capture delay above the preview, then click **Capture star pose after delay**. Stand near the interaction surface, spread arms and feet, and hold still until the countdown finishes. Return to the PC: both images remain frozen on the server.

Select these **same physical** points in both images:

1. Your right hand in color, then that same right hand in depth.
2. Your left hand in color, then that same left hand in depth.
3. Your left foot in color, then that same left foot in depth.
4. Your right foot in color, then that same right foot in depth.

Do not choose "whichever hand appears on the left" independently in each image. Mirror settings can change apparent left/right, but physical identity must match. Spread the selected points into a convex quadrilateral; **Undo last selection** fixes a click. Save the play area and body alignment. This pairing is separate from selecting chest depth: the **chest measurement requires only one click**, not eight.

Pairing approximates a flat color/depth alignment plane. For a wall, pose close to the wall. For a floor, use corresponding points close to the floor plane; a standing pose high above the floor is not an accurate floor-plane alignment. RGB and depth frames are independently acquired, so hold still. Alignment accuracy degrades away from the calibration plane; check ring positions across the play area.

## 5. One delayed chest click sets the upper boundary

Stand at the **closest position to the camera you want to include**. Click **Capture body distance after delay**, get into position and hold still, then return to the PC. Click **your chest once in the frozen Depth image**. This capture needs only the depth feed and does not require another color/depth point pair.

The app saves the chest and surface measurements immediately. All valid depth pixels inside this interval can interact inside the projected area:

```text
camera → ignored nearer space → chest / upper boundary → PLAYABLE → surface / lower boundary
```

The readouts show camera distances in meters and playable thickness in centimeters. "Upper" describes the closer/taller body limit, not a larger raw depth number. Missing depth, pixels closer than the selected chest limit, and pixels beyond the surface are excluded.

**Surface noise margin** defaults to 1 cm, excluding the last centimeter next to the wall/floor to avoid surface noise. Set it to 0 to include the exact measured surface distance. Click **Apply margin + interaction settings** to save adjustments. **Active regions** shows included pixels in green. Return to live preview to test body movement.

A surface sample is one constant camera-depth value, not a fitted 3D plane. With an oblique camera or sloping/uneven surface, other parts of the wall/floor may have different depths and can fall inside the band. Aim the camera as squarely at a flat interaction surface as possible, verify the green preview, and adjust the margin. Accurate perpendicular distance to an arbitrary tilted wall would require multi-point plane fitting and camera intrinsics.

## Play and customization

Your **body** controls the game. The mouse only operates setup controls and never generates game contacts.

Whole-body mode samples silhouette extremes and region centers so connected hands/feet can interact. It is geometric depth-region tracking, not anatomical joint recognition. Region-center mode uses one point per connected region. Minimum region area filters small blobs; multiple people may merge.

The first game draws rings around these depth samples. Bubble garden demonstrates another game. Ring color, size, game selection, and calibration rectangle synchronize between operator and projector windows.

Add a game option in `app/static/index.html` and renderer in `app/static/app.js`, which receives normalized contacts `{x,y}` in `[0,1]`. Acquisition (`camera.py`), delayed captures (`capture.py`), depth measurements (`distance.py`), and detection/mapping (`tracking.py`) are separate from rendering.

## Saved settings and hardware validation

- `.local/camera.json`: connection, backend, and mirror settings.
- `.local/calibration.json`: projected corners, body pairs, and interaction settings.
- `.local/distance.json`: sampled surface/chest distances, surface margin, expected depth resolution.

`PROJECTORGAMES_DATA` overrides this directory. Distances and mapping survive restart; frozen images remain in memory only. Recalibrate after moving the camera/projector, switching camera devices/orientation/resolution, or changing surfaces. Tracking is suppressed when expected frame dimensions do not match. Legacy background-difference API endpoints remain available for compatibility but are not required by the point-selection GUI.

The cloud machine has no Astra or projector. Automated tests use synthetic data and browser camera fixtures; Windows device acquisition, physical alignment, and real interaction accuracy must be verified on the installation PC. The local API is unauthenticated: keep the server bound to loopback. No secret or cloud account is required.

## Development and checks

From the existing checkout:

```sh
python -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

On Windows substitute `.venv\Scripts\python.exe`. Running `scripts/launch.py` opens the browser once the server is ready. Existing camera environment variables remain first-launch defaults when saved GUI settings do not exist.

```sh
.venv/bin/python -m unittest discover -s tests -v
node --check app/static/app.js
.venv/bin/python -m pip check
```

Tests cover sampled depth bands, noisy/invalid pixels, one-click chest measurement, persistence, mirrored coordinates, reflected homography, resolution guards, body samples, delayed/frozen captures, cancellation, camera retries, and Windows-backend fallback through mocks. Windows launchers and physical device behavior require testing on Windows.
