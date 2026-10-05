$ErrorActionPreference = 'Stop'
$taskPython = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$previousPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = Join-Path $PSScriptRoot 'src'
    & $taskPython -m unittest discover -s (Join-Path $PSScriptRoot 'tests') -v
    if ($LASTEXITCODE -ne 0) { throw "Tests failed with code $LASTEXITCODE" }
} finally {
    $env:PYTHONPATH = $previousPythonPath
}
