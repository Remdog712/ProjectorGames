# ProjectorGames — Processing / Java

The current version is a small **Processing 4 (Java mode)** calibration tool. It provides:

- Fits a homography from **9, 16, or 25 manually clicked correspondences** to a projected grid.
- Tests projected colors and reads wall/floor distance from the **Depth** image.
- Captures an empty surface (low bound), then a delayed body image to choose a high bound.
- Runs a depth-controlled **bubble popping game** and **color silhouette halo**, with adjustable noise and blob-size filters.

Setup uses manual point selection and depth silhouettes. It does not require skeleton tracking, a browser UI or a web server. The earlier Python Studio remains in the repository for reference, but is not the application described here.

## Windows setup

1. Install the **Windows x64 version of [Processing 4](https://processing.org/download)**. Use Java mode.
2. Stop any older ProjectorGames server or camera viewer.
3. In this repository, double-click **Setup Processing.cmd**. It uses your existing Python 3.12 installation only to download two Java dependency jars with pinned SHA-256 checks. OpenCV's jar includes its native libraries; no Processing Video library installation is needed.
4. Open **processing/ProjectorCalibrator/ProjectorCalibrator.pde** in Processing and press **Run**.
5. Choose **Runtime folder** and select the folder containing your working **x64 OpenNI2.dll**. Keep its driver subfolders intact. The tool imports the old `.local/camera.json` connection preferences on first launch when available.
6. Choose your **Color #** (often 0 or 1), select Auto / DirectShow / Media Foundation, then **Connect**. Close Windows Camera before connecting.

**Only the selected camera stream runs.** Choosing Depth closes UVC color before opening OpenNI depth; choosing Color closes depth first. Switching previews can take a moment. This avoids simultaneous capture competing for the Astra USB connection.

The actual application is Java: OpenNI2 depth is accessed directly through JNA, and UVC color through OpenCV Java. Python is not used during capture, calibration or measurement. Keep the existing trusted Orbbec drivers/runtime; do not download an isolated DLL from third-party sites.

**Start ProjectorGames.cmd now verifies the Java dependencies and opens the Processing sketch**, rather than starting the old browser server. If `.pde` files are not associated with Processing, open the file using Processing's File → Open menu. Press Run in the IDE.

## Guided setup

The app shows one step at a time, with **Next** and **Back**:

1. **Connect camera**: choose the runtime folder and Connect. Adjust the camera number/backend here if needed. Next waits for live color. Demo is available for trying the workflow without hardware.
2. **Check preview**: switch between Color and Depth and adjust Mirror separately. Next returns to Color for calibration.
3. **Place projector**: use Windows **Win+P → Extend**, choose the display, and **Show grid**. Choose 3 × 3 (9), 4 × 4 (16), or 5 × 5 (25) points. Escape hides the projector window.
4. **Click targets**: from your PC, click each numbered target center in the color preview. The next target is highlighted on the projector. **Undo point** (or Z) corrects the last click; **Freeze** holds the color image. Next requires all points and fits the homography using every correspondence.
5. **Verify mapping**: click several places in the color preview and check the projected crosshair against the physical surface. **Back** lets you correct points. **Save & next** saves only after you have tried a preview click; you judge the physical alignment.
6. **Projection color test**: inspect red/green/blue/white bars on the projector. **Next color** cycles solid colors; **Show projector** reopens its window.
7. **Measure surface / low**: keep the projected surface empty, then click a solid wall/floor point in Depth. This saves its distance as the low bound and holds the full depth frame as the empty-surface background.
8. **Choose high point**: click **Capture in 5s**, stand closer to the camera, and let it freeze the depth image. Return to the PC and click one body point (chest, hand or raised foot). It must be closer than the low point by more than the noise margin. Next resumes live depth.
9. **Align depth interaction**: select **Color selection** to click grid targets in the saved color image, or **Use color grid** to reuse every point from color calibration. This uses normalized image coordinates with mirror correction; it is an **approximation**, not factory RGB/depth registration. Different fields of view, lens distortion and sensor offset can cause mismatch. Check physical alignment in the game. **Depth selection** lets you select/correct the corresponding depth locations using surface landmarks when needed. Undo works in either view.
10. **Interaction games**: the mode button cycles **Bubbles → Halo → Light pads → Aurora**. Use Pause / Play, Restart, Show game, Noise, Min area and Flip X. **Trail** adjusts halo fade duration. **Mic** enables the default microphone for Aurora; it starts OFF each session.

### Games and experiences

- **Bubbles**: move hands, feet or body over projected bubbles inside the selected depth layer. Mouse clicks do not pop targets.
- **Halo**: a changing-color silhouette outline leaves shapes behind as you move. Those trails fade over the selected duration, including after leaving the camera view. Flip and mode changes clear old trails.
- **Light pads**: touch the illuminated colored pad to score and activate the next one. Uses stable depth silhouette/contacts and a cooldown to prevent repeated instant scoring.
- **Aurora**: flowing colored ribbons and body-centered ripples, without scoring. With **Mic: ON**, microphone volume changes ribbon motion, width and wave amplitude. Music, voice and claps all work as input; this is amplitude response, not musical beat recognition.

Microphone capture uses Java Sound and the Windows default recording input. Choose the intended microphone in Windows Sound settings and allow desktop-app microphone access if needed. No audio is recorded to disk or sent anywhere. If no microphone is available, the status explains the error and Aurora still animates. The microphone stops when the sketch closes or Mic is switched off.


The last saved mapping is read on startup; stepping through a new calibration replaces it only at **Save & next**. Back does not erase your selected points. Camera, mirror and grid changes reset active calibration. A mapping must match the camera, mirror orientation, resolution and physical mounting. Recalibrate after moving equipment. Keep projector resolution/aspect unchanged after fitting.

Homography assumes one flat wall/floor plane. Lens distortion and objects away from that plane can cause offsets. Fit error describes the clicked correspondences, not independently measured physical touch accuracy. Points must cover an area; collinear/degenerate input is rejected. A plain depth image generally cannot see projected visible-light dots, so use **Color** for wall/floor grid selection.

## Click the wall/floor to measure distance

1. Advance to **Measure surface**, and select **Depth** if needed.
2. Click a solid point on the wall or floor in the depth preview.
3. Read the value in **millimeters and meters** at the right. A crosshair marks the selected depth pixel.

The reading is the median of valid depth samples in a 5 × 5 patch. Zero/missing depth is excluded. If too few valid samples exist, the tool asks you to click a nearby solid point. Mirror applies to the displayed image and measurement coordinates together. Freeze is available during color target selection; depth measurements use the live depth stream.

**Color homography and depth measurements are independent.** The Astra Pro's RGB and depth sensors have different image coordinates. This tool does not pretend a clicked color pixel is the corresponding depth pixel. Select Depth for measurement. It reports camera-axis depth (Z), not distance perpendicular to an arbitrary wall or radial range from the optical center. The application handles OpenNI depth modes in millimeters and 100-micrometer units.

## Bubble interaction and noise

The high/low clicks define the allowed layer thickness: `surface distance - high-point distance`. During play, each pixel is compared with the captured empty-surface depth at the same pixel. Changes smaller than **Noise** (default 20 mm) or larger than the layer thickness are ignored. This helps accommodate a sloping surface; these remain camera-axis depth differences, not perpendicular distances to a fitted plane.

The detector samples every fourth depth pixel, groups neighboring foreground samples and rejects groups below **Min area** (default about 192 source pixels). A blob must persist across two successive captures. Stable silhouette pixels pop overlapping bubbles; the projected yellow rings show blob centers. Raise Noise or Min area if bubbles pop by themselves. Lower them for smaller hands/feet. This is silhouette detection, not skeleton/person identification: objects inside the layer can also interact.

**Halo** uses the same stable depth silhouette and draws a soft, changing-color glow along its boundary. It does not draw a single ring around the body center or require skeleton tracking. Select a high bound that includes the body parts you want to illuminate. Pause and missing/stale depth stop new halo drawing; existing trails continue to fade.

**Flip X** transforms mapped interaction X to `1 - X` for every mode. It leaves the saved homographies, camera preview orientation and empty-surface frame unchanged. The flip and game selection are saved in `settings.json` and `interaction.json`. Toggle Flip X if a different projector/camera arrangement already has the correct direction.

A planar depth homography cannot correct parallax for bodies far from the calibrated surface. Verify physical bubble alignment after calibration. Capture the empty background again if the camera, surface or projector moves. The background is session-only and is deliberately recaptured on a new setup run.

The hardware-free Demo mode supplies a static synthetic plane for setup controls; it does not simulate a moving person or create mouse-driven bubble pops.

## Saved files

Inside `processing/ProjectorCalibrator/data/`:

- `settings.json`: runtime path, color camera/backend, mirrors and grid size.
- `calibration.json`: normalized color source points, grid, source resolution/mirrors and fitted 3 × 3 matrix.
- `interaction.json`: low/high measurements, noise, minimum area and depth homography/resolution/mirror for inspection. The guide recaptures the background and interaction setup each run.

These are generated local files and are ignored by Git. Dependency jars are generated under the sketch's `code/` folder and are also ignored. Existing Python configuration files are preserved.

The **Demo** button provides a clearly labeled synthetic 640 × 480 image/depth plane so the calibration controls can be tried without camera hardware. Demo values are not physical measurements.

## Verification and limitations

The Java sources compile against Processing Core 4.3.1, JNA 5.15.0 and OpenCV 4.9.0. Dependencies come from Maven Central, with pinned SHA-256 hashes in `scripts/setup_processing.py`.

Validation performed in the cloud:

- 9-, 16- and 25-point projective fits and RMS error, insufficient/collinear input rejection.
- Depth patch sampling, missing-depth rejection and mirror consistency.
- OpenNI C ABI using a synthetic native fixture: padded row stride, both depth-unit modes, frame release and device cleanup.
- A real Processing/Java2D window on a virtual display, using automated mouse clicks: all ten guided steps, color test, delayed capture, low/high gates, independent depth mapping, mouse exclusion, game controls, resizing and projector rendering.
- Audio RMS and light-pad scoring/cooldown checks; actual microphone hardware is not attached in the cloud.
- Halo rendering and trail decay, mode selection and color-assisted calibration clicks.
- Synthetic depth silhouettes: stable bubble collision, depth-range/noise rejection, missing background, minimum blob area and duplicate-frame rejection.

Both windows explicitly use `pixelDensity(1)`. The control window chooses its initial size before creating the drawing surface, avoiding a resize during startup on Windows/high-density displays.

The original Astra Pro and your Windows OpenNI2 runtime are not attached to the cloud machine. Actual Windows DLL loading, USB color/depth capture and physical projector accuracy still need checking on your PC. Native driver errors are shown in the tool and printed to the Processing console.

Developer compilation (JDK 17+; use 21 for the supplied checks):

```text
python scripts/setup_processing.py --with-core
java scripts/java/BuildProcessing.java <repo-root> <repo-root>/.processing-build/core-4.3.1.jar
```

Processing itself supplies Core when running the `.pde`; do not add a second Core jar to the sketch's `code/` folder. `ManualCalibrationChecks` exercises mapping and sampling; the optional `FakeOpenNI.c` fixture is Linux-only test code, not a device driver. `ProcessingUiChecks` requires a desktop/virtual display and runs the standalone equivalent of the thin `.pde` entry point.
