param(
    [switch]$Dev,
    [ValidateRange(1024, 65535)][int]$Port = 8000
)
$ErrorActionPreference = 'Stop'
$taskPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $taskPython)) { throw 'Create backend/.venv and install requirements-dev.txt first.' }
$previousDevAuth = $env:CHARACTER_DEV_AUTH
try {
    $env:CHARACTER_DEV_AUTH = if ($Dev) { '1' } else { '0' }
    & $taskPython -m uvicorn character_app.main:create_app --factory --app-dir (Join-Path $PSScriptRoot 'src') --host 127.0.0.1 --port $Port --no-proxy-headers
    if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE" }
} finally {
    $env:CHARACTER_DEV_AUTH = $previousDevAuth
}
