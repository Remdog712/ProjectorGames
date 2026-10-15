# ProjectorGames — Processing / Java

The current version is a small **Processing 4 (Java mode)** calibration tool. It does two things:

- Fits a homography from **9, 16, or 25 manually clicked correspondences** to a projected grid.
- Reads the depth-camera distance when you click a wall/floor point in the **Depth** image.

There is **no closest-to-camera setting**, automatic pose calibration, skeleton setup, browser UI, or web server in this version. The earlier Python Studio remains in the repository for reference, but is not the application described here.

## Windows setup

1. Install the **Windows x64 version of [Processing 4](https://processing.org/download)**. Use Java mode.
2. Stop any older ProjectorGames server or camera viewer.
3. In this repository, double-click **Setup Processing.cmd**. It uses your existing Python 3.12 installation only to download two Java dependency jars with pinned SHA-256 checks. OpenCV's jar includes its native libraries; no Processing Video library installation is needed.
4. Open **processing/ProjectorCalibrator/ProjectorCalibrator.pde** in Processing and press **Run**.
5. Choose **Runtime folder** and select the folder containing your working **x64 OpenNI2.dll**. Keep its driver subfolders intact. The tool imports the old `.local/camera.json` connection preferences on first launch when available.
6. Choose your **Color #** (often 0 or 1), select Auto / DirectShow / Media Foundation, then **Connect**. Close Windows Camera before connecting.

The actual application is Java: OpenNI2 depth is accessed directly through JNA, and UVC color through OpenCV Java. Python is not used during capture, calibration or measurement. Keep the existing trusted Orbbec drivers/runtime; do not download an isolated DLL from third-party sites.

**Start ProjectorGames.cmd now verifies the Java dependencies and opens the Processing sketch**, rather than starting the old browser server. If `.pde` files are not associated with Processing, open the file using Processing's File → Open menu. Press Run in the IDE.

## Guided setup

The app shows one step at a time, with **Next** and **Back**:

1. **Connect camera**: choose the runtime folder and Connect. Adjust the camera number/backend here if needed. Next waits for live color. Demo is available for trying the workflow without hardware.
2. **Check preview**: switch between Color and Depth and adjust Mirror separately. Next returns to Color for calibration.
3. **Place projector**: use Windows **Win+P → Extend**, choose the display, and **Show grid**. Choose 3 × 3 (9), 4 × 4 (16), or 5 × 5 (25) points. Escape hides the projector window.
4. **Click targets**: from your PC, click each numbered target center in the color preview. The next target is highlighted on the projector. **Undo point** (or Z) corrects the last click; **Freeze** holds the color image. Next requires all points and fits the homography using every correspondence.
5. **Verify mapping**: click several places in the color preview and check the projected crosshair against the physical surface. **Back** lets you correct points. **Save & next** saves only after you have tried a preview click; you judge the physical alignment.
6. **Measure surface**: the app selects Depth. Click the wall or floor to read its distance. Next requires a valid measurement, or use **Skip measurement** explicitly.
7. **Setup complete**: the mapping is saved. Switch Color/Depth to check mapping or measure again.

The last saved mapping is read on startup; stepping through a new calibration replaces it only at **Save & next**. Back does not erase your selected points. Camera, mirror and grid changes reset active calibration. A mapping must match the camera, mirror orientation, resolution and physical mounting. Recalibrate after moving equipment. Keep projector resolution/aspect unchanged after fitting.

Homography assumes one flat wall/floor plane. Lens distortion and objects away from that plane can cause offsets. Fit error describes the clicked correspondences, not independently measured physical touch accuracy. Points must cover an area; collinear/degenerate input is rejected. A plain depth image generally cannot see projected visible-light dots, so use **Color** for wall/floor grid selection.

## Click the wall/floor to measure distance

1. Advance to **Measure surface**, or select **Depth** on the completion screen.
2. Click a solid point on the wall or floor in the depth preview.
3. Read the value in **millimeters and meters** at the right. A crosshair marks the selected depth pixel.

The reading is the median of valid depth samples in a 5 × 5 patch. Zero/missing depth is excluded. If too few valid samples exist, the tool asks you to click a nearby solid point. Mirror applies to the displayed image and measurement coordinates together. Freeze is available during color target selection; depth measurements use the live depth stream.

**Color homography and depth measurements are independent.** The Astra Pro's RGB and depth sensors have different image coordinates. This tool does not pretend a clicked color pixel is the corresponding depth pixel. Select Depth for measurement. It reports camera-axis depth (Z), not distance perpendicular to an arbitrary wall or radial range from the optical center. The application handles OpenNI depth modes in millimeters and 100-micrometer units.

No near/far range, chest pose or body tracking is required. Those features are outside this deliberately small version.

## Saved files

Inside `processing/ProjectorCalibrator/data/`:

- `settings.json`: runtime path, color camera/backend, mirrors and grid size.
- `calibration.json`: normalized source points, grid, source image space/resolution, mirrors and the fitted 3 × 3 matrix.

These are generated local files and are ignored by Git. Dependency jars are generated under the sketch's `code/` folder and are also ignored. Existing Python configuration files are preserved.

The **Demo** button provides a clearly labeled synthetic 640 × 480 image/depth plane so the calibration controls can be tried without camera hardware. Demo values are not physical measurements.

## Verification and limitations

The Java sources compile against Processing Core 4.3.1, JNA 5.15.0 and OpenCV 4.9.0. Dependencies come from Maven Central, with pinned SHA-256 hashes in `scripts/setup_processing.py`.

Validation performed in the cloud:

- 9-, 16- and 25-point projective fits and RMS error, insufficient/collinear input rejection.
- Depth patch sampling, missing-depth rejection and mirror consistency.
- OpenNI C ABI using a synthetic native fixture: padded row stride, both depth-unit modes, frame release and device cleanup.
- A real Processing/Java2D window on a virtual display, using automated mouse clicks: all seven guided steps, missing-camera/incomplete-point/unchecked-mapping guards, Undo, Back, fit, probe, save, known depth measurement, explicit skip, resizing and projector rendering.

Both windows explicitly use `pixelDensity(1)`. The control window chooses its initial size before creating the drawing surface, avoiding a resize during startup on Windows/high-density displays.

The original Astra Pro and your Windows OpenNI2 runtime are not attached to the cloud machine. Actual Windows DLL loading, USB color/depth capture and physical projector accuracy still need checking on your PC. Native driver errors are shown in the tool and printed to the Processing console.

Developer compilation (JDK 17+; use 21 for the supplied checks):

```text
python scripts/setup_processing.py --with-core
java scripts/java/BuildProcessing.java <repo-root> <repo-root>/.processing-build/core-4.3.1.jar
```

Processing itself supplies Core when running the `.pde`; do not add a second Core jar to the sketch's `code/` folder. `ManualCalibrationChecks` exercises mapping and sampling; the optional `FakeOpenNI.c` fixture is Linux-only test code, not a device driver. `ProcessingUiChecks` requires a desktop/virtual display and runs the standalone equivalent of the thin `.pde` entry point.
