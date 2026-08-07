# bmad-loop-until-done.ps1
# Unattended full-sprint driver: repeatedly runs bmad-loop until no actionable
# stories remain in sprint-status, then notifies. Stops if a run pauses on an
# escalation that needs a human.
param(
    [string]$Project = "E:\01 DEV\SYNCRO-SPRING",
    [int]$MaxRounds = 20
)
$ErrorActionPreference = "Stop"
$env:BMAD_LOOP_HTTP_TIMEOUT_S = "300.0"
$env:BMAD_LOOP_HTTP_CONNECT_TIMEOUT_S = "10.0"
$env:BMAD_LOOP_HTTP_SSE_READ_TIMEOUT_S = "60.0"
$env:BMAD_LOOP_HTTP_HEALTH_TIMEOUT_S = "60.0"
$sprint = Join-Path $Project "_bmad-output\implementation-artifacts\sprint-status.yaml"

function Get-ActionableCount {
    $ss = Get-Content $sprint -Raw
    return ([regex]::Matches($ss, ":\s*(backlog|ready-for-dev)")).Count
}

function Get-LatestRunState {
    $runsDir = Join-Path $Project ".bmad-loop\runs"
    $runs = Get-ChildItem $runsDir -Directory -ErrorAction SilentlyContinue
    if (-not $runs) { return $null }
    $latest = $runs | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $statePath = Join-Path $latest.FullName "state.json"
    if (-not (Test-Path $statePath)) { return $null }
    return (Get-Content $statePath -Raw | ConvertFrom-Json)
}

function Notify {
    param([string]$Title, [string]$Body)
    try {
        New-BurntToastNotification -Text $Title, $Body -ErrorAction Stop
    } catch {
        [Console]::Beep(1000, 600)
        [Console]::Beep(1000, 600)
    }
    Write-Host "`n*** $Title — $Body ***"
}

$round = 0
while ($round -lt $MaxRounds) {
    $round++
    $remaining = Get-ActionableCount
    if ($remaining -eq 0) {
        Notify "bmad-loop SELESAI" "Semua $($round - 1) round selesai, tidak ada story tersisa."
        exit 0
    }
    Write-Host "`n===== ROUND $round / $MaxRounds — $remaining stories tersisa ====="
    Write-Host "mulai: $(Get-Date -Format 'HH:mm:ss')"
    bmad-loop run --project $Project
    $code = $LASTEXITCODE

    $state = Get-LatestRunState
    if ($state -and $state.paused_reason -and -not $state.finished -and -not $state.stopped) {
        Notify "bmad-loop PAUSED" "Butuh aksi manusia: $($state.paused_reason.Substring(0, [Math]::Min(200, $state.paused_reason.Length)))"
        Write-Host "Resume kapan pun: bmad-loop resume $($state.run_id)"
        exit 2
    }
    if ($code -ne 0) {
        Write-Host "WARNING: bmad-loop run exit code $code — cek log, lanjut round berikutnya."
    }
    Start-Sleep -Seconds 15
}

Notify "bmad-loop STOP" "Mencapai MaxRounds ($MaxRounds) tapi masih ada story tersisa."
exit 1
