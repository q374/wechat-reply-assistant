param(
    [int]$Port = 8000,
    [switch]$RestartOnExit
)
$ErrorActionPreference = 'Stop'
$python = (Get-Command python -ErrorAction Stop).Source
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
do {
    & $python -m uvicorn app.main:app --host 127.0.0.1 --port $Port
    $exitCode = $LASTEXITCODE
    if (!$RestartOnExit) { exit $exitCode }
    Start-Sleep -Seconds 2
} while ($true)
