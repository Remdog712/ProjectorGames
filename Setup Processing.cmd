@echo off
setlocal
cd /d "%~dp0"
py -3.12 scripts\setup_processing.py
if errorlevel 1 goto failed
echo.
echo Open processing\ProjectorCalibrator\ProjectorCalibrator.pde in Processing 4, Java mode. Press Run.
pause
exit /b 0
:failed
echo Setup failed. Copy the error above. Python 3.12 with the Windows launcher is required for this dependency installer.
pause
exit /b 1
