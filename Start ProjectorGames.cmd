@echo off
setlocal
cd /d "%~dp0"
if exist ".venv\Scripts\python.exe" goto install
py -3.12 -m venv .venv
if errorlevel 1 goto failed
:install
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 goto failed
".venv\Scripts\python.exe" scripts\launch.py
if errorlevel 1 goto failed
exit /b 0
:failed
echo ProjectorGames could not start. Copy the error above for troubleshooting.
pause
exit /b 1
