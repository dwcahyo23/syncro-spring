# bmad-loop TUI launcher.
# Usage: .\bmad-loop-tui.ps1            -> open the TUI dashboard
#        .\bmad-loop-tui.ps1 -Run      -> start a new run in the background, then open the TUI
#        .\bmad-loop-tui.ps1 -Resume   -> resume a paused run, then open the TUI
#        .\bmad-loop-tui.ps1 -Status   -> show run + sprint state (no TUI)

param(
    [switch]$Run,
    [switch]$Resume,
    [switch]$Status
)

$ErrorActionPreference = "Stop"

# psmux (installed via winget) lives on the user PATH; pick it up without a shell restart.
$env:Path = [Environment]::GetEnvironmentVariable("Path", "User") + ";" + [Environment]::GetEnvironmentVariable("Path", "Machine")

if (-not (Get-Command bmad-loop -ErrorAction SilentlyContinue)) {
    Write-Error "bmad-loop not found on PATH. Install it with: uv tool install bmad-loop"
    exit 1
}

if (-not (Get-Command psmux -ErrorAction SilentlyContinue)) {
    Write-Warning "psmux not found on PATH - bmad-loop will have no terminal multiplexer."
}

if ($Status) {
    bmad-loop status
    exit $LASTEXITCODE
}

if ($Run) {
    bmad-loop run
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
elseif ($Resume) {
    bmad-loop resume
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

bmad-loop tui
exit $LASTEXITCODE
