param([switch]$Camera, [string]$OpenNI2Redist = '')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path '.venv\Scripts\python.exe')) { throw 'Double-click Start ProjectorGames.cmd to install and launch.' }
# Older command-line flags remain supported for a first launch. Saved GUI settings take precedence.
if ($Camera) { $env:PROJECTORGAMES_CAMERA = 'openni' }
if ($OpenNI2Redist) { $env:OPENNI2_REDIST = $OpenNI2Redist }
& .\.venv\Scripts\python.exe scripts\launch.py
if ($LASTEXITCODE -ne 0) { throw 'Server stopped with an error.' }
