<#
.SYNOPSIS
    Checks and reports the Syncro pilot state (seed rows, accepted telemetry, alert, notification
    jobs, acknowledgement result) from the local stack, with state-keyed verdicts.

.DESCRIPTION
    Reads ONLY state the production pipeline already persists, through the established
    in-container exec patterns (no host tooling, no credentials):
      - PostgreSQL via: docker compose --env-file <env> -f <compose> exec -T postgres `
            sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c "<sql>"'
        (machine_counter_states, sparepart_alerts, notification_jobs, telemetry_quarantine,
        plus the 7-1 canonical seed rows). $POSTGRES_USER/$POSTGRES_DB expand inside the
        container - container-local trust, no password involved.
      - Redis via: docker compose ... exec -T redis redis-cli --raw HGETALL `
            syncro:machine:{machineId}:latest
        (best-effort latest-telemetry hash; TTL 5 minutes, so an absent hash is INFO, not FAIL -
        the durable authority is machine_counter_states).

    Sections and verdict semantics (each line prints PASS / FAIL / INFO; the script exits
    non-zero only on FAIL):
      1. PREFLIGHT   - postgres SELECT 1 and redis PING hard-fail; EMQX /api/v5/status (public)
                       and the backend health endpoint are warn-only (persisted evidence outlives
                       broker/backend restarts).
      2. SEED        - canonical 7-1 row counts (plant GM1, group Forming, machine BF-08410
                       ACTIVE, sparepart BF-08410GM1ELEPLCWEC000, installation 1000/0/90,
                       3 users, TECHNICIAN/STAFF/LEADER responsibilities).
      3. TELEMETRY   - machine_counter_states for BF-08410 (saved on every accepted persist),
                       Redis latest-hash fields (counting, countingDelta, receivedAt, traceId),
                       and telemetry_quarantine as a diagnostic (rows present -> FAIL with the
                       latest rejection reason: a quarantined publish is the fastest root-cause
                       path).
      4. ALERT       - interpreted from the counter state: counting=890 -> expect zero
                       non-RESOLVED alerts; counting=900 -> expect exactly one non-RESOLVED
                       alert (snapshot 90.00, traceId printed); no counter row -> pre-publish
                       baseline (expect none).
      5. NOTIFICATION- notification_jobs for the active alert: escalation_level, status
                       (PENDING/ROUTING_FAILED/SENT/EXHAUSTED/ESCALATED/CANCELLED/RATE_LIMITED),
                       recipient_phone, trace_id, error_detail. Placeholder pilot numbers
                       (6281234567801/02/03) make PENDING/ROUTING_FAILED with attempt evidence
                       the expected live-WAHA outcome - reported as evidence, not failure.
      6. ACKNOWLEDGEMENT RESULT - alert status plus per-level job timeline. After an
                       acknowledgement, STAFF/LEADER jobs must not be SENT (the acknowledge
                       action itself is owned by story 7-6; this section reports the state).

    The machine UUID is resolved by natural key (plant GM1 + lower(machine code) = bf-08410),
    never assumed from the seed's fixed UUID (the seed adopts pre-existing case-variant rows).

.EXAMPLE
    powershell -NoProfile -File syncro/scripts/verify-pilot.ps1

    Verifies the pilot state using the default paths (syncro/.env,
    syncro/infra/docker-compose.yml), resolved from the script location - works from any
    working directory.

.EXAMPLE
    powershell -NoProfile -File syncro/scripts/verify-pilot.ps1 -BackendHealthUrl http://localhost:8080/api/v1/health

    Same, with an explicit backend health URL.

.NOTES
    Prerequisites: the docker compose stack up (postgres + redis at minimum). The EMQX and
    backend probes are optional (warn-only). All connection values come from parameters, the
    env file, or documented non-secret defaults (localhost/ports); no secret is hardcoded and
    the PostgreSQL/Redis paths need no credential at all (in-container).

    Windows PowerShell 5.1+ compatible (also runs on PowerShell 7).
#>
param(
    [string]$EnvFile,
    [string]$ComposeFile,
    [string]$BackendHealthUrl = 'http://localhost:8080/api/v1/health',
    [string]$WebUrl = 'http://localhost:3000'
)

$ErrorActionPreference = 'Stop'

$SyncroRoot = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) { $EnvFile = Join-Path $SyncroRoot '.env' }
if (-not $ComposeFile) { $ComposeFile = Join-Path $SyncroRoot 'infra\docker-compose.yml' }

foreach ($requiredFile in @($EnvFile, $ComposeFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        Write-Host "ERROR: required file not found: $requiredFile"
        exit 1
    }
}
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$ComposeFile = (Resolve-Path -LiteralPath $ComposeFile).Path

try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { Write-Verbose "console encoding left unchanged: $($_.Exception.Message)" }

# Load env file into the process environment (syncro/scripts/start-backend.ps1 parsing idiom)
# for the warn-only EMQX probe (host/port).
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^([^#=][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}

$script:FailCount = 0

function Write-Section { param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host ''
    Write-Host "=== $Name ==="
}
function Print-Pass { param([Parameter(Mandatory = $true)][string]$Message) Write-Host "  [PASS] $Message" }
function Print-Fail { param([Parameter(Mandatory = $true)][string]$Message) $script:FailCount++; Write-Host "  [FAIL] $Message" }
function Print-Info { param([Parameter(Mandatory = $true)][string]$Message) Write-Host "  [INFO] $Message" }

function Invoke-PilotSql {
    param([Parameter(Mandatory = $true)][string]$Sql)
    # $POSTGRES_USER / $POSTGRES_DB expand INSIDE the container (single-quoted PS string, no
    # host expansion; the compose env values are space-free so sh needs no quotes around
    # them). The SQL itself travels over stdin, so no quoting of SQL is needed on any
    # PowerShell version (embedded double quotes in a native argument would be stripped by
    # Windows PowerShell 5.1's legacy argument passing - live-verified).
    $shCommand = 'psql -U $POSTGRES_USER -d $POSTGRES_DB -t -A'
    $output = $Sql + ';' | & docker compose --env-file $script:EnvFile -f $script:ComposeFile exec -T postgres sh -c $shCommand
    if ($LASTEXITCODE -ne 0) {
        throw "psql query failed with exit code $($LASTEXITCODE): $Sql"
    }
    return $output
}

function Invoke-PilotRedis {
    param([Parameter(Mandatory = $true)][string[]]$RedisCommand)
    $output = & docker compose --env-file $script:EnvFile -f $script:ComposeFile exec -T redis redis-cli --raw @RedisCommand
    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli failed with exit code $($LASTEXITCODE) ($($RedisCommand -join ' '))"
    }
    return $output
}

Write-Host '=== Syncro pilot verification ==='
Write-Host "env file:     $EnvFile"
Write-Host "compose file: $ComposeFile"

# ---------------------------------------------------------------------------
# 1. PREFLIGHT
# ---------------------------------------------------------------------------
Write-Section '1. PREFLIGHT'
try {
    $postgresOne = Invoke-PilotSql 'SELECT 1'
    if ((@($postgresOne) -join '').Trim() -eq '1') {
        Print-Pass 'postgres reachable (SELECT 1 via docker compose exec)'
    } else {
        Print-Fail "postgres SELECT 1 returned unexpected value: '$postgresOne'"
    }
} catch {
    Print-Fail "postgres unreachable: $($_.Exception.Message)"
    Print-Info  'Hard failure - cannot verify anything else. Start the stack:'
    Print-Info  "  docker compose --env-file `"$EnvFile`" -f `"$ComposeFile`" up -d"
    exit 1
}
try {
    $redisPong = Invoke-PilotRedis @('PING')
    if ((@($redisPong) -join '').Trim() -eq 'PONG') {
        Print-Pass 'redis reachable (PING via docker compose exec)'
    } else {
        Print-Fail "redis PING returned unexpected value: '$redisPong'"
        exit 1
    }
} catch {
    Print-Fail "redis unreachable: $($_.Exception.Message)"
    Print-Info  'Hard failure - cannot verify latest-hash evidence. Start the stack:'
    Print-Info  "  docker compose --env-file `"$EnvFile`" -f `"$ComposeFile`" up -d"
    exit 1
}

$MqttHost = if ($env:SYNCRO_MQTT_HOST) { $env:SYNCRO_MQTT_HOST } else { 'localhost' }
$DashboardPort = if ($env:SYNCRO_MQTT_DASHBOARD_PORT) { $env:SYNCRO_MQTT_DASHBOARD_PORT } else { '18083' }
try {
    $null = Invoke-RestMethod -Method Get -Uri "http://$MqttHost`:$DashboardPort/api/v5/status" -TimeoutSec 5 -UseBasicParsing
    Print-Pass "EMQX management API status reachable (http://$MqttHost`:$DashboardPort/api/v5/status)"
} catch {
    Print-Info "EMQX management API not reachable at http://$MqttHost`:$DashboardPort/api/v5/status (warn-only;"
    Print-Info 'persisted evidence outlives broker restarts; publish scripts need the broker).'
}
try {
    $null = Invoke-RestMethod -Method Get -Uri $BackendHealthUrl -TimeoutSec 5 -UseBasicParsing
    Print-Pass "backend health reachable ($BackendHealthUrl)"
} catch {
    Print-Info "backend health not reachable at $BackendHealthUrl (warn-only; persisted evidence"
    Print-Info 'outlives backend restarts - but live telemetry ingestion needs the backend running).'
}

# ---------------------------------------------------------------------------
# 2. SEED
# ---------------------------------------------------------------------------
Write-Section '2. SEED (canonical 7-1 rows)'
$seedChecks = @(
    @{ Name = 'plant GM1 exists'; Expected = '1';
       Sql = "SELECT count(*) FROM plants WHERE code = 'GM1'" },
    @{ Name = 'machine group Forming under GM1'; Expected = '1';
       Sql = "SELECT count(*) FROM machine_groups g JOIN plants p ON p.id = g.plant_id WHERE p.code = 'GM1' AND lower(g.name) = 'forming'" },
    @{ Name = 'machine BF-08410 ACTIVE'; Expected = '1';
       Sql = "SELECT count(*) FROM machines m JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410' AND m.status = 'ACTIVE'" },
    @{ Name = 'sparepart BF-08410GM1ELEPLCWEC000'; Expected = '1';
       Sql = "SELECT count(*) FROM spareparts sp WHERE lower(sp.code) = 'bf-08410gm1eleplcwec000'" },
    @{ Name = 'installation 1000/0/90'; Expected = '1';
       Sql = "SELECT count(*) FROM machine_sparepart_installations i JOIN machines m ON m.id = i.machine_id JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410' AND i.expected_production_count = 1000 AND i.baseline_counter = 0 AND i.threshold_percentage = 90" },
    @{ Name = 'pilot recipient users (3)'; Expected = '3';
       Sql = "SELECT count(*) FROM auth_users WHERE login_identifier IN ('technician.gm1@syncro.dev', 'staff.gm1@syncro.dev', 'leader.gm1@syncro.dev')" },
    @{ Name = 'TECHNICIAN responsibility'; Expected = '1';
       Sql = "SELECT count(*) FROM machine_responsibilities r JOIN machines m ON m.id = r.machine_id JOIN plants p ON p.id = m.plant_id JOIN auth_users u ON u.id = r.user_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410' AND r.level = 'TECHNICIAN' AND u.login_identifier = 'technician.gm1@syncro.dev'" },
    @{ Name = 'STAFF responsibility'; Expected = '1';
       Sql = "SELECT count(*) FROM machine_responsibilities r JOIN machines m ON m.id = r.machine_id JOIN plants p ON p.id = m.plant_id JOIN auth_users u ON u.id = r.user_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410' AND r.level = 'STAFF' AND u.login_identifier = 'staff.gm1@syncro.dev'" },
    @{ Name = 'LEADER responsibility'; Expected = '1';
       Sql = "SELECT count(*) FROM machine_responsibilities r JOIN machines m ON m.id = r.machine_id JOIN plants p ON p.id = m.plant_id JOIN auth_users u ON u.id = r.user_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410' AND r.level = 'LEADER' AND u.login_identifier = 'leader.gm1@syncro.dev'" }
)
foreach ($check in $seedChecks) {
    $actual = ''
    try {
        $actual = (@(Invoke-PilotSql $check.Sql) -join '').Trim()
    } catch {
        Print-Fail "$($check.Name): query failed: $($_.Exception.Message)"
        continue
    }
    if ($actual -eq $check.Expected) {
        Print-Pass "$($check.Name): $actual"
    } else {
        Print-Fail "$($check.Name): expected $($check.Expected), got '$actual' - run syncro/scripts/seed-pilot.ps1"
    }
}

# ---------------------------------------------------------------------------
# 3. TELEMETRY
# ---------------------------------------------------------------------------
Write-Section '3. TELEMETRY (accepted telemetry evidence)'
$machineId = ''
try {
    $machineId = (@(Invoke-PilotSql "SELECT m.id::text FROM machines m JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410'") -join '').Trim()
} catch {
    Print-Fail "cannot resolve pilot machine UUID: $($_.Exception.Message)"
}
if (-not $machineId) {
    Print-Fail 'pilot machine BF-08410 (plant GM1) not found - seed missing?'
} else {
    Print-Pass "pilot machine resolved by natural key: $machineId"
}

$countingValue = $null
if ($machineId) {
    $counterRows = @()
    try {
        $counterRows = @(Invoke-PilotSql "SELECT counting::text || '|' || to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') FROM machine_counter_states WHERE machine_id = '$machineId'::uuid")
    } catch {
        Print-Fail "cannot read machine_counter_states: $($_.Exception.Message)"
    }
    $counterLine = ($counterRows | Where-Object { $_ } | Select-Object -First 1)
    if ($counterLine) {
        $counterParts = $counterLine -split '\|', 2
        $countingValue = $counterParts[0]
        Print-Pass "machine_counter_states: counting=$($counterParts[0]) updated_at=$($counterParts[1]) (saved on every accepted persist)"
    } else {
        Print-Info 'no telemetry yet (machine_counter_states row absent) - pre-publish baseline state.'
    }

    $redisKey = "syncro:machine:$machineId`:latest"
    $hashLines = @()
    try {
        $hashLines = @(Invoke-PilotRedis @('HGETALL', $redisKey) | Where-Object { $_ -ne '' })
    } catch {
        Print-Info "cannot read redis latest hash: $($_.Exception.Message)"
    }
    if ($hashLines.Count -gt 0) {
        $hash = @{}
        for ($i = 0; $i -lt $hashLines.Count - 1; $i = $i + 2) {
            $hash[[string]$hashLines[$i]] = [string]$hashLines[$i + 1]
        }
        $missingFields = @('counting', 'countingDelta', 'receivedAt', 'traceId') | Where-Object { -not $hash.ContainsKey($_) }
        if ($missingFields.Count -eq 0) {
            Print-Pass "redis latest hash ($redisKey): counting=$($hash['counting']) countingDelta=$($hash['countingDelta']) receivedAt=$($hash['receivedAt']) traceId=$($hash['traceId'])"
        } else {
            Print-Fail "redis latest hash ($redisKey) missing expected field(s): $($missingFields -join ', '); present: $(($hash.Keys | Sort-Object) -join ', ')"
        }
    } else {
        Print-Info "redis latest hash absent ($redisKey) - TTL is 5 minutes, an absent hash does not mean"
        Print-Info 'missing telemetry; machine_counter_states is the durable authority.'
    }
}

$quarantineCount = -1
try {
    $quarantineCount = [int]((@(Invoke-PilotSql 'SELECT count(*) FROM telemetry_quarantine') -join '').Trim())
} catch {
    Print-Fail "cannot read telemetry_quarantine: $($_.Exception.Message)"
}
if ($quarantineCount -eq 0) {
    Print-Pass 'telemetry_quarantine empty (no rejected/quarantined telemetry)'
} elseif ($quarantineCount -gt 0) {
    $latestQuarantine = ''
    try {
        $latestQuarantine = (@(Invoke-PilotSql 'SELECT rejection_reason || '' | '' || coalesce(rejection_field, ''-'') || '' | '' || topic FROM telemetry_quarantine ORDER BY received_at DESC LIMIT 1') -join '').Trim()
    } catch {
        $latestQuarantine = '(latest row could not be read)'
    }
    Print-Fail "telemetry_quarantine has $quarantineCount row(s) - latest: $latestQuarantine"
    Print-Info  'a quarantined publish is the fastest root-cause path: inspect raw_payload /'
    Print-Info  'rejection_field in pgAdmin (local/dev evidence tool only).'
}

# ---------------------------------------------------------------------------
# 4. ALERT (state-keyed by the observed counter value)
# ---------------------------------------------------------------------------
Write-Section '4. ALERT (state-keyed expectation)'
$activeAlertRows = @()
if ($machineId) {
    try {
        $activeAlertRows = @(Invoke-PilotSql "SELECT id::text || '|' || status || '|' || consumed_percentage_snapshot::text || '|' || coalesce(trace_id, '') FROM sparepart_alerts WHERE machine_id = '$machineId'::uuid AND status <> 'RESOLVED'" | Where-Object { $_ })
    } catch {
        Print-Fail "cannot read sparepart_alerts: $($_.Exception.Message)"
    }
}
$alertId = ''
$alertStatus = ''
$alertSnapshot = ''
$alertTraceId = ''
if ($activeAlertRows.Count -gt 0) {
    $alertParts = ($activeAlertRows | Select-Object -First 1) -split '\|', 4
    $alertId = $alertParts[0]
    $alertStatus = $alertParts[1]
    $alertSnapshot = $alertParts[2]
    $alertTraceId = $alertParts[3]
}

if ($null -eq $countingValue) {
    if ($activeAlertRows.Count -eq 0) {
        Print-Pass 'no counter state and no non-RESOLVED alert - consistent pre-publish baseline'
    } else {
        Print-Fail "non-RESOLVED alert(s) exist ($($activeAlertRows.Count)) without counter state - inconsistent state"
    }
} elseif ($countingValue -eq '890') {
    if ($activeAlertRows.Count -eq 0) {
        Print-Pass 'no alert - correct before-threshold state (counting=890 -> 89.00% < 90%)'
    } else {
        Print-Fail "counting=890 but $($activeAlertRows.Count) non-RESOLVED alert(s) exist - before-threshold must not alert"
    }
} elseif ($countingValue -eq '900') {
    if ($activeAlertRows.Count -eq 0) {
        Print-Fail 'counting=900 reached the threshold but no non-RESOLVED alert exists - was the backend'
        Print-Fail 'running when the threshold publish happened? (a stopped backend never receives the'
        Print-Fail 'message: cleanSession(true); republish with the backend running)'
    } elseif ($activeAlertRows.Count -eq 1) {
        Print-Pass "exactly one non-RESOLVED alert: status=$alertStatus consumed_percentage_snapshot=$alertSnapshot trace_id=$alertTraceId"
        if ($alertSnapshot -ne '90.00') {
            Print-Fail "consumed_percentage_snapshot expected 90.00 for counting=900, got $alertSnapshot"
        }
    } else {
        Print-Fail "$($activeAlertRows.Count) non-RESOLVED alerts exist - expected exactly one (the V19 partial unique index should prevent this)"
    }
} else {
    Print-Info "counter counting=$countingValue is not a canonical pilot boundary (890/900) - alert state reported as-is"
    if ($activeAlertRows.Count -eq 0) {
        Print-Info 'no non-RESOLVED alert'
    } else {
        Print-Info "non-RESOLVED alert(s): $($activeAlertRows.Count) (status=$alertStatus snapshot=$alertSnapshot trace_id=$alertTraceId)"
    }
}

# ---------------------------------------------------------------------------
# 5. NOTIFICATION
# ---------------------------------------------------------------------------
Write-Section '5. NOTIFICATION (notification_jobs evidence)'
$jobRows = @()
if ($alertId) {
    try {
        $jobRows = @(Invoke-PilotSql "SELECT escalation_level || '|' || status || '|' || coalesce(recipient_phone, '') || '|' || coalesce(trace_id, '') || '|' || coalesce(error_detail, '') FROM notification_jobs WHERE alert_id = '$alertId'::uuid ORDER BY CASE escalation_level WHEN 'TECHNICIAN' THEN 1 WHEN 'STAFF' THEN 2 WHEN 'LEADER' THEN 3 ELSE 4 END, created_at" | Where-Object { $_ })
    } catch {
        Print-Fail "cannot read notification_jobs: $($_.Exception.Message)"
    }
    if ($jobRows.Count -eq 0) {
        Print-Info 'no notification jobs yet for this alert (async dispatch may not have run; story 7-5 owns the proof)'
    } else {
        foreach ($jobRow in $jobRows) {
            $jobParts = $jobRow -split '\|', 5
            Print-Pass "job: escalation_level=$($jobParts[0]) status=$($jobParts[1]) recipient_phone=$($jobParts[2]) trace_id=$($jobParts[3]) error_detail=$($jobParts[4])"
            if ($jobParts[2].StartsWith('628123456780')) {
                Print-Info  '  recipient is a PLACEHOLDER pilot number (6281234567801/02/03): PENDING/ROUTING_FAILED with'
                Print-Info  '  attempt evidence is the expected live-WAHA outcome until real numbers are set (seed caveat).'
            }
        }
        $technicianJob = $jobRows | Where-Object { $_ -like 'TECHNICIAN|*' }
        if (-not $technicianJob) {
            Print-Info 'no TECHNICIAN job yet - initial notification is level 1 (TECHNICIAN); dispatch is async (7-5 proof)'
        }
    }
} else {
    $totalJobs = -1
    try {
        $totalJobs = [int]((@(Invoke-PilotSql 'SELECT count(*) FROM notification_jobs') -join '').Trim())
    } catch {
        Print-Info "cannot read notification_jobs: $($_.Exception.Message)"
    }
    if ($totalJobs -eq 0) {
        Print-Pass 'no active alert and no notification jobs - consistent pre-alert baseline'
    } elseif ($totalJobs -gt 0) {
        Print-Info "$totalJobs notification job(s) exist without a non-RESOLVED alert (RESOLVED alert history); reported as-is"
    }
}

# ---------------------------------------------------------------------------
# 6. ACKNOWLEDGEMENT RESULT
# ---------------------------------------------------------------------------
Write-Section '6. ACKNOWLEDGEMENT RESULT'
if (-not $alertId) {
    Print-Info 'no non-RESOLVED alert to acknowledge - nothing to report (acknowledgement is story 7-6 scope)'
} else {
    Print-Info "alert status: $alertStatus (acknowledge/resolve actions are owned by story 7-6; this section reports state)"
    if ($jobRows.Count -eq 0) {
        Print-Info 'no notification jobs for this alert yet'
    } else {
        foreach ($jobRow in $jobRows) {
            $jobParts = $jobRow -split '\|', 5
            $level = $jobParts[0]
            $status = $jobParts[1]
            Print-Info "job timeline: level=$level status=$status"
            if (($alertStatus -eq 'ACKNOWLEDGED' -or $alertStatus -eq 'RESOLVED') -and ($level -eq 'STAFF' -or $level -eq 'LEADER') -and $status -eq 'SENT') {
                Print-Info "  observation: $level job SENT while alert is $alertStatus - acknowledgement must stop escalation (7-6 proof)"
            }
        }
    }
}

# ---------------------------------------------------------------------------
# Evidence pointers
# ---------------------------------------------------------------------------
Write-Section 'EVIDENCE POINTERS'
Print-Info "Machine Hub:         $WebUrl/dashboard/master-data/machines/BF-08410"
Print-Info "Telemetry dashboard: $WebUrl/dashboard/telemetry"
Print-Info "Alerts:              $WebUrl/dashboard/alerts"
Print-Info "System health:       $WebUrl/dashboard/system-health"
Print-Info "Backend health:      $BackendHealthUrl"
Print-Info '(web dev server assumed at the URL above: npm --prefix syncro/apps/web run dev)'
Print-Info 'pgAdmin:             local/dev evidence tool only, not a product feature (see syncro/docs/local-development.md)'
$influxQuery = 'docker compose --env-file ' + "`"$EnvFile`"" + ' -f ' + "`"$ComposeFile`"" + ' exec -T influxdb influxdb3 query --token "$INFLUXDB3_ADMIN_TOKEN" --database syncro "SELECT * FROM telemetry ORDER BY time DESC LIMIT 5"'
Print-Info "InfluxDB (optional): $influxQuery"
Print-Info '(token expands in-container; measurement is telemetry per InfluxTelemetryWriter.toPoint)'

Write-Host ''
if ($script:FailCount -gt 0) {
    Write-Host "RESULT: FAIL ($($script:FailCount) failing check(s))"
    exit 1
}
Write-Host 'RESULT: PASS'
exit 0
