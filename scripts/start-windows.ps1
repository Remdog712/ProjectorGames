param([switch]$Camera, [string]$OpenNI2Redist = '')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path '.venv\Scripts\python.exe')) { throw 'Run scripts\setup-windows.ps1 first.' }
if ($Camera) {
    $env:PROJECTORGAMES_CAMERA = 'openni'
    if ($OpenNI2Redist) {
        if (-not (Test-Path $OpenNI2Redist -PathType Container)) { throw 'OpenNI2 runtime directory does not exist.' }
        $env:OPENNI2_REDIST = (Resolve-Path $OpenNI2Redist).Path
    }
} else {
    Remove-Item Env:PROJECTORGAMES_CAMERA -ErrorAction SilentlyContinue
}
Write-Host 'Open http://127.0.0.1:8000 in Edge or Chrome. Press Ctrl+C to stop.'
& .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
if ($LASTEXITCODE -ne 0) { throw 'Server stopped with an error.' }
