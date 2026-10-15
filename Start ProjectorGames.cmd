@echo off
setlocal
cd /d "%~dp0"
py -3.12 scripts\setup_processing.py
if errorlevel 1 goto failed
echo.
echo Open the sketch in Processing 4, Java mode, and press Run.
echo If Windows asks which app to use, select Processing or open the file from Processing's File menu.
start "" "%~dp0processing\ProjectorCalibrator\ProjectorCalibrator.pde"
exit /b 0
:failed
echo Dependency setup failed. Copy the error above. Python 3.12 with its Windows launcher is required.
pause
exit /b 1
