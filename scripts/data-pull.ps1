param([string]$Remote = 'origin')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dataTree = Join-Path ([System.IO.Path]::GetTempPath()) ("collector-data-" + [guid]::NewGuid())
Push-Location $root
try {
    git fetch --no-tags $Remote refs/heads/data
    if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the data branch.' }
    git worktree add --no-checkout --detach $dataTree FETCH_HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Could not check out the data branch.' }
    try {
        git -C $dataTree sparse-checkout set out
        if ($LASTEXITCODE -ne 0) { throw 'Could not select the out directory.' }
        git -C $dataTree reset --hard HEAD
        if ($LASTEXITCODE -ne 0) { throw 'Could not populate the data worktree.' }
        $install = ':core:installDist'
        .\gradlew.bat $install
        if ($LASTEXITCODE -ne 0) { throw 'Could not build the collector.' }
        & .\core\build\install\core\bin\core.bat --data-import $dataTree
        if ($LASTEXITCODE -ne 0) { throw 'Could not import the collector output.' }
    } finally {
        git worktree remove $dataTree
    }
} finally {
    Pop-Location
}
