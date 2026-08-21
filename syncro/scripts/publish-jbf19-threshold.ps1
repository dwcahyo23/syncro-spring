<#
.SYNOPSIS
    Publishes the pilot threshold MQTT fixture (counting=900, 90.00% >= 90%) to the local EMQX
    broker through the EMQX Management API.

.DESCRIPTION
    Story 7-2 consumer contract, scripted: the fixture FILE BODY is published verbatim as the MQTT
    message body to the canonical pilot topic factory/GM1/BF-08410/telemetry at QoS 1 (matching the
    backend subscription), with only the "timestamp" value refreshed to the current UTC instant
    (yyyy-MM-dd'T'HH:mm:ss'Z', Instant.parse-compatible). Every other byte - especially messageId
    "pilot-jbf19-threshold-900" - stays verbatim: the payload is regex-substituted on the raw file
    text and is NEVER re-serialized through ConvertFrom-Json/ConvertTo-Json.

    Transport: EMQX Management API on the local broker (no MQTT client needed on the host):
      1. POST /api/v5/login with the EMQX dashboard credentials docker-compose maps from
         SYNCRO_MQTT_USERNAME / SYNCRO_MQTT_PASSWORD -> JWT bearer token
      2. POST /api/v5/publish with Authorization: Bearer <token>, body
         @{ topic; payload; qos = 1; retain = $false }
    All connection values come from the env file (default syncro/.env) plus the documented
    non-secret defaults localhost / 18083. No secret is hardcoded.

    Expected backend outcome: counting=900 -> 90.00% >= 90% threshold -> exactly one OPEN
    sparepart alert and a TECHNICIAN notification job. That outcome is proven end-to-end by
    story 7-5 with the backend running; this script only publishes the data.

.EXAMPLE
    powershell -NoProfile -File syncro/scripts/publish-jbf19-threshold.ps1

    Publishes the threshold fixture with a refreshed UTC timestamp to
    factory/GM1/BF-08410/telemetry using syncro/.env connection values.

.EXAMPLE
    powershell -NoProfile -File syncro/scripts/publish-jbf19-threshold.ps1 -NoTimestampRefresh

    Publishes the fixture file fully verbatim (stale timestamp) - for exact-duplicate evidence
    runs only; a historical timestamp produces a historical-looking Influx point and a skewed
    publish-to-visible latency indicator (documented 7-2 behavior).

.NOTES
    Prerequisites: the docker compose stack up (emqx healthy). The backend does not need to be
    running for the publish itself; with the backend STOPPED the publish is a pure mechanics
    check (login -> publish -> 200 echo): cleanSession(true) means the stopped backend never
    receives the message, so no alert/job state is created - that state belongs to story 7-5.

    Duplicate behavior: republishing the same fixture within the Redis dedupe window
    (default PT30S, key syncro:machine:{machineId}:telemetry:dedupe:{messageId}) makes the
    backend log mqtt_telemetry_duplicate and skip persisting - intentional evidence for 7-5's
    duplicate-publish AC. After the window, the alert-level non-RESOLVED dedup guard and the
    notification-job idempotency key keep a republish harmless.

    Windows PowerShell 5.1+ compatible (also runs on PowerShell 7). -UseBasicParsing avoids the
    IE first-run dialog on 5.1 and is a no-op on 7.
#>
param(
    [string]$EnvFile,
    [string]$FixtureFile,
    [string]$Topic = 'factory/GM1/BF-08410/telemetry',
    [switch]$NoTimestampRefresh
)

$ErrorActionPreference = 'Stop'

$SyncroRoot = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) { $EnvFile = Join-Path $SyncroRoot '.env' }
if (-not $FixtureFile) { $FixtureFile = Join-Path $SyncroRoot 'tests\fixtures\mqtt-jbf19-threshold-payload.json' }

foreach ($requiredFile in @($EnvFile, $FixtureFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        Write-Host "ERROR: required file not found: $requiredFile"
        exit 1
    }
}
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$FixtureFile = (Resolve-Path -LiteralPath $FixtureFile).Path

try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { Write-Verbose "console encoding left unchanged: $($_.Exception.Message)" }

# Load env file into the process environment (syncro/scripts/start-backend.ps1 parsing idiom).
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^([^#=][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}

$MqttHost = if ($env:SYNCRO_MQTT_HOST) { $env:SYNCRO_MQTT_HOST } else { 'localhost' }
$DashboardPort = if ($env:SYNCRO_MQTT_DASHBOARD_PORT) { $env:SYNCRO_MQTT_DASHBOARD_PORT } else { '18083' }
if (-not $env:SYNCRO_MQTT_USERNAME -or -not $env:SYNCRO_MQTT_PASSWORD) {
    Write-Host "ERROR: SYNCRO_MQTT_USERNAME / SYNCRO_MQTT_PASSWORD missing - check $EnvFile"
    Write-Host '       (they are the EMQX dashboard credentials docker-compose maps to'
    Write-Host '        EMQX_DASHBOARD__DEFAULT_USERNAME / EMQX_DASHBOARD__DEFAULT_PASSWORD)'
    exit 1
}

$bodyText = Get-Content -Raw -Encoding UTF8 $FixtureFile

# Extract contract fields from the raw text (no JSON re-serialization of the payload).
$messageIdMatch = [regex]::Match($bodyText, '"messageId"\s*:\s*"([^"]+)"')
if (-not $messageIdMatch.Success) {
    Write-Host "ERROR: cannot find messageId in fixture: $FixtureFile (fixture drift?)"
    exit 1
}
$messageId = $messageIdMatch.Groups[1].Value
$countingMatch = [regex]::Match($bodyText, '"counting"\s*:\s*([0-9]+)')
if (-not $countingMatch.Success) {
    Write-Host "ERROR: cannot find counting in fixture (fixture drift?) - refusing to publish a payload the backend would quarantine."
    exit 1
}
$counting = $countingMatch.Groups[1].Value

$publishedTimestamp = '(verbatim from fixture)'
if (-not $NoTimestampRefresh) {
    # Exactly one "timestamp" field is a precondition for a safe refresh: with zero or
    # several, a blind regex replace would publish a body we cannot reason about.
    $timestampFieldCount = [regex]::Matches($bodyText, '"timestamp"\s*:\s*"[^"]*"').Count
    if ($timestampFieldCount -ne 1) {
        Write-Host "ERROR: expected exactly one `"timestamp`" field in fixture, found $timestampFieldCount (fixture drift?) - refusing to publish a body whose timestamp cannot be safely refreshed. Use -NoTimestampRefresh to publish the fixture verbatim."
        exit 1
    }
    $publishedTimestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", [System.Globalization.CultureInfo]::InvariantCulture)
    # Targeted regex replace on the RAW text: only the timestamp value changes, every other
    # byte (messageId included) stays exactly as committed in the fixture file. The captured
    # groups reproduce the original key/colon spacing verbatim - nothing is normalized.
    # (${1}/${2} braced references are load-bearing: the timestamp starts with a digit, so a
    # bare $1 would read as an invalid group "$12026..." and stay literal.)
    $bodyText = [regex]::Replace($bodyText, '("timestamp"\s*:\s*")[^"]*(")', '${1}' + $publishedTimestamp + '${2}')
}

# Runtime guard against mangling of the prepared body before it leaves the host.
if (-not $bodyText.Contains($messageId)) {
    Write-Host "ERROR: prepared payload no longer contains messageId $messageId - aborting before publish."
    exit 1
}

$BaseUrl = "http://$MqttHost`:$DashboardPort"

Write-Host '=== Publish pilot fixture (threshold) ==='
Write-Host "env file:     $EnvFile"
Write-Host "fixture file: $FixtureFile"
Write-Host "broker:       $BaseUrl (EMQX Management API)"
Write-Host "topic:        $Topic"
Write-Host ''

Write-Host '--- EMQX login (/api/v5/login) ---'
$token = $null
try {
    $loginBody = @{ username = $env:SYNCRO_MQTT_USERNAME; password = $env:SYNCRO_MQTT_PASSWORD } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v5/login" -ContentType 'application/json; charset=utf-8' -Body $loginBody -TimeoutSec 15 -UseBasicParsing
    $token = $login.token
} catch {
    Write-Host "ERROR: EMQX login failed: $($_.Exception.Message)"
    Write-Host "Guidance: check SYNCRO_MQTT_USERNAME / SYNCRO_MQTT_PASSWORD in $EnvFile -"
    Write-Host '          they are the EMQX dashboard credentials docker-compose maps.'
    exit 1
}
if (-not $token) {
    Write-Host 'ERROR: EMQX login returned no token.'
    exit 1
}
Write-Host 'PASS: login ok (JWT bearer token obtained)'

Write-Host ''
Write-Host '--- Publish (/api/v5/publish, QoS 1, retain false) ---'
$publishBody = @{ topic = $Topic; payload = $bodyText; qos = 1; retain = $false } | ConvertTo-Json
$publish = $null
try {
    $publish = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v5/publish" -Headers @{ Authorization = "Bearer $token" } -ContentType 'application/json; charset=utf-8' -Body $publishBody -TimeoutSec 15 -UseBasicParsing
} catch {
    Write-Host "ERROR: EMQX publish failed: $($_.Exception.Message)"
    Write-Host "       topic was: $Topic"
    exit 1
}
# HTTP 200 reached here means the broker accepted the publish. EMQX 6.2.2 answers with
# {message, reason_code} - it does NOT echo the payload; "no_matching_subscribers" means the
# message was accepted while no subscriber was online (e.g. backend stopped).
$brokerMessage = ''
if ($publish.PSObject.Properties['message']) { $brokerMessage = [string]$publish.message }
$brokerReasonCode = ''
if ($publish.PSObject.Properties['reason_code']) { $brokerReasonCode = [string]$publish.reason_code }
# Verdict honesty: reason_code 0/empty is a clean accept; 16 / no_matching_subscribers is the
# documented backend-down accept; anything else is unexpected and must not read as a PASS.
if ($brokerReasonCode -eq '' -or $brokerReasonCode -eq '0') {
    Write-Host 'PASS: publish accepted by broker'
} elseif ($brokerReasonCode -eq '16' -or $brokerMessage -eq 'no_matching_subscribers') {
    Write-Host "PASS: publish accepted (broker message: '$brokerMessage', reason_code: $brokerReasonCode)"
    Write-Host '      no subscriber was online at publish time (backend stopped?) - the message is not'
    Write-Host '      retained (retain=false) and the backend uses cleanSession(true), so a stopped'
    Write-Host '      backend never receives it: no alert/job state is created (that proof belongs to 7-5).'
} else {
    Write-Host "WARN: publish returned unexpected reason_code=$brokerReasonCode message='$brokerMessage' - treat as unverified"
}

Write-Host ''
Write-Host '--- Published pilot payload ---'
Write-Host "messageId: $messageId (verbatim from fixture)"
Write-Host "counting:  $counting"
Write-Host "timestamp: $publishedTimestamp"
Write-Host "topic:     $Topic"

Write-Host ''
Write-Host 'Next steps:'
Write-Host '  - Run powershell -NoProfile -File syncro/scripts/verify-pilot.ps1 to check pilot state.'
Write-Host '  - Republishing this fixture within the 30s dedupe window is intentional duplicate evidence:'
Write-Host '    the backend logs mqtt_telemetry_duplicate and skips persisting (no second alert/job).'
exit 0
