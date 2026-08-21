<#
.SYNOPSIS
    Applies the canonical Syncro pilot seed (plant GM1, machine BF-08410 / JBF19) to the local
    PostgreSQL stack and prints a canonical-row summary.

.DESCRIPTION
    Wraps EXACTLY the hardened apply flow documented in the pilot seed header (story 7-1):

        Get-Content <seed> | docker compose --env-file <env> -f <compose> exec -T postgres `
            sh -c 'PGCLIENTENCODING=UTF8 psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'

    - $POSTGRES_USER / $POSTGRES_DB expand INSIDE the container (never on the host), so the
      PostgreSQL path needs no credential at all (container-local trust).
    - ON_ERROR_STOP=1 plus the seed's own BEGIN/COMMIT wrapper make the apply atomic: the
      first failed statement rolls the whole seed back instead of leaving it half-applied.
    - PGCLIENTENCODING=UTF8 protects the seed's UTF-8 label ("Electric · PLC · Wecon · LX5").
    - The Windows PowerShell 5.1 UTF-8 pipe guard ($OutputEncoding, set below) is
      load-bearing: without it Windows PowerShell 5.1 pipes ASCII and corrupts the seed's
      "·" characters (live-verified md5 mismatch). It is a no-op on PowerShell 7, whose
      default pipe encoding is already UTF-8.
    - The post-apply summary doubles as a visible tripwire: the sparepart row must print
      the correct "Electric · PLC · Wecon · LX5" label.

    Prerequisites:
    - The docker compose stack must be up (at least the postgres service).
    - Flyway migrations must already be applied; this script refuses to seed an unmigrated
      database. Boot the backend once against the local stack, or run:
      mvn -f syncro/apps/backend/pom.xml flyway:migrate

    Idempotency: every seed statement is INSERT ... SELECT ... WHERE NOT EXISTS guarded on
    natural keys, so re-running inserts zero rows (psql prints INSERT 0 0) and the summary
    stays identical. Safe to run any number of times.

.EXAMPLE
    powershell -NoProfile -File syncro/scripts/seed-pilot.ps1

    Applies the seed using the default paths (syncro/.env, syncro/infra/docker-compose.yml,
    syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql), resolved relative to the
    script location - works from any working directory.

.EXAMPLE
    powershell -NoProfile -File syncro/scripts/seed-pilot.ps1 -EnvFile D:\syncro\.env

    Uses a custom environment file (same key contract as syncro/.env.example).

.NOTES
    Windows PowerShell 5.1+ compatible (also runs on PowerShell 7). No MQTT credentials and
    no database passwords are needed or hardcoded; the psql connection is in-container only.
#>
param(
    [string]$EnvFile,
    [string]$ComposeFile,
    [string]$SeedFile
)

$ErrorActionPreference = 'Stop'

$SyncroRoot = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) { $EnvFile = Join-Path $SyncroRoot '.env' }
if (-not $ComposeFile) { $ComposeFile = Join-Path $SyncroRoot 'infra\docker-compose.yml' }
if (-not $SeedFile) { $SeedFile = Join-Path $SyncroRoot 'apps\backend\src\main\resources\db\seed\pilot-seed.sql' }

foreach ($requiredFile in @($EnvFile, $ComposeFile, $SeedFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        Write-Host "ERROR: required file not found: $requiredFile"
        exit 1
    }
}
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$ComposeFile = (Resolve-Path -LiteralPath $ComposeFile).Path
$SeedFile = (Resolve-Path -LiteralPath $SeedFile).Path

# UTF-8 pipe guard (mandatory): Windows PowerShell 5.1 pipes ASCII by default, which would
# corrupt the seed's UTF-8 "·" label bytes on the way into the container.
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
# Decode native (docker/psql) UTF-8 output correctly so the summary label prints as-is.
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { Write-Verbose "console encoding left unchanged: $($_.Exception.Message)" }

# Windows PowerShell 5.1 (and PowerShell 7 in Legacy native-argument-passing mode) strips
# unescaped embedded double quotes when building the native command line - sh would never
# see them (live-verified: psql then receives a mangled -c argument). PowerShell 7.3+
# passes embedded quotes correctly by itself. Pre-escape only where needed so the sh -c
# payload below is byte-identical on every PowerShell version.
$script:ModernNativePassing = $false
if (Get-Variable -Name PSNativeCommandArgumentPassing -ErrorAction SilentlyContinue) {
    if ($PSNativeCommandArgumentPassing -ne 'Legacy') { $script:ModernNativePassing = $true }
}

# Load env file into the process environment (same idiom as the other pilot scripts), with
# compose-style value handling: one pair of surrounding single/double quotes is stripped
# (KEY="value" / KEY='value'), and comment lines (trimmed start '#') are skipped.
Get-Content $EnvFile | ForEach-Object {
    $envLine = $_.Trim()
    if ($envLine.StartsWith('#')) { return }
    if ($envLine -match '^([^#=][^=]*)=(.*)$') {
        $envValue = $matches[2].Trim()
        if ($envValue.Length -ge 2) {
            if ($envValue.StartsWith('"') -and $envValue.EndsWith('"')) { $envValue = $envValue.Substring(1, $envValue.Length - 2) }
            elseif ($envValue.StartsWith("'") -and $envValue.EndsWith("'")) { $envValue = $envValue.Substring(1, $envValue.Length - 2) }
        }
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $envValue, 'Process')
    }
}

# Whitespace guard: the query-path psql invocation expands $POSTGRES_USER / $POSTGRES_DB
# UNQUOTED inside the container's sh, so whitespace in either value would word-split the
# psql command line into mangled arguments.
foreach ($pgVar in @('POSTGRES_USER', 'POSTGRES_DB')) {
    $pgValue = [Environment]::GetEnvironmentVariable($pgVar, 'Process')
    if ($null -ne $pgValue -and $pgValue -match '\s') {
        Write-Host "ERROR: $pgVar from the env file contains whitespace ('$pgValue') - the in-container psql query expands it unquoted and would break; fix the env file and re-run."
        exit 1
    }
}

function Invoke-PilotSql {
    param([Parameter(Mandatory = $true)][string]$Sql)
    # $POSTGRES_USER / $POSTGRES_DB expand INSIDE the container (single-quoted PS string, no
    # host expansion; the compose env values are space-free so sh needs no quotes around
    # them). The SQL itself travels over stdin, so no quoting of SQL is needed on any
    # PowerShell version.
    $shCommand = 'psql -v ON_ERROR_STOP=1 -U $POSTGRES_USER -d $POSTGRES_DB -t -A'
    $output = $Sql + ';' | & docker compose --env-file $script:EnvFile -f $script:ComposeFile exec -T postgres sh -c $shCommand
    if ($LASTEXITCODE -ne 0) {
        throw "psql query failed with exit code $($LASTEXITCODE): $Sql"
    }
    return $output
}

Write-Host '=== Syncro pilot seed ==='
Write-Host "env file:     $EnvFile"
Write-Host "compose file: $ComposeFile"
Write-Host "seed file:    $SeedFile"
Write-Host ''

Write-Host '--- Preflight: Flyway migrations ---'
$migrationCount = $null
try {
    $migrationCount = (Invoke-PilotSql 'SELECT count(*) FROM flyway_schema_history WHERE success' | Select-Object -First 1)
} catch {
    Write-Host "ERROR: cannot read flyway_schema_history (schema not created yet?). $($_.Exception.Message)"
    Write-Host 'Guidance: boot the backend once against the local stack, or run'
    Write-Host '          mvn -f syncro/apps/backend/pom.xml flyway:migrate'
    Write-Host "          Is the stack up? Start it with: docker compose --env-file `"$EnvFile`" -f `"$ComposeFile`" up -d"
    exit 1
}
$parsedCount = 0
if (-not [int]::TryParse([string]$migrationCount, [ref]$parsedCount)) {
    Write-Host "ERROR: unexpected migration count response: '$migrationCount'"
    exit 1
}
if ($parsedCount -le 0) {
    Write-Host "ERROR: flyway_schema_history reports $parsedCount successful migrations - seeding refused."
    Write-Host 'Guidance: boot the backend once against the local stack, or run'
    Write-Host '          mvn -f syncro/apps/backend/pom.xml flyway:migrate'
    exit 1
}
Write-Host "PASS: Flyway migrations applied: $parsedCount"

Write-Host ''
Write-Host '--- Applying pilot seed (in-container psql, atomic BEGIN/COMMIT) ---'
# The in-container sh -c payload is byte-identical to the seed header command:
#   PGCLIENTENCODING=UTF8 psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"
# ($shQuote is a raw double quote on modern native passing, \" on 5.1/Legacy - see above.)
$shQuote = '"'
if (-not $script:ModernNativePassing) { $shQuote = '\"' }
$applyShCommand = 'PGCLIENTENCODING=UTF8 psql -v ON_ERROR_STOP=1 -U ' + $shQuote + '$POSTGRES_USER' + $shQuote + ' -d ' + $shQuote + '$POSTGRES_DB' + $shQuote
Get-Content -Raw -Encoding UTF8 $SeedFile | & docker compose --env-file $EnvFile -f $ComposeFile exec -T postgres sh -c $applyShCommand
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: seed apply failed with exit code $LASTEXITCODE (ON_ERROR_STOP aborted; transaction rolled back)."
    Write-Host "       Is the stack up? Start it with:"
    Write-Host "       docker compose --env-file `"$EnvFile`" -f `"$ComposeFile`" up -d"
    exit 1
}

Write-Host ''
Write-Host '--- Canonical pilot rows (identical on every idempotent re-run) ---'
$plantCount = (Invoke-PilotSql "SELECT count(*) FROM plants WHERE code = 'GM1'") -join ''
$groupCount = (Invoke-PilotSql "SELECT count(*) FROM machine_groups g JOIN plants p ON p.id = g.plant_id WHERE p.code = 'GM1' AND lower(g.name) = 'forming'") -join ''
$machineInfo = (Invoke-PilotSql "SELECT m.code || ' / ' || m.name || ' [' || m.status || ']' FROM machines m JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410'") -join ''
$sparepartInfo = (Invoke-PilotSql "SELECT sp.code || ' | ' || sp.name FROM spareparts sp WHERE lower(sp.code) = 'bf-08410gm1eleplcwec000'") -join ''
$installationInfo = (Invoke-PilotSql "SELECT i.expected_production_count::text || '/' || i.baseline_counter::text || '/' || i.threshold_percentage::text FROM machine_sparepart_installations i JOIN machines m ON m.id = i.machine_id JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410'") -join ''
$userCount = (Invoke-PilotSql "SELECT count(*) FROM auth_users WHERE login_identifier IN ('technician.gm1@syncro.dev', 'staff.gm1@syncro.dev', 'leader.gm1@syncro.dev')") -join ''
$levelCounts = (Invoke-PilotSql "SELECT r.level || ': ' || count(*) FROM machine_responsibilities r JOIN machines m ON m.id = r.machine_id JOIN plants p ON p.id = m.plant_id WHERE p.code = 'GM1' AND lower(m.code) = 'bf-08410' GROUP BY r.level ORDER BY r.level") -join '; '

$summary = @(
    [pscustomobject]@{ 'Canonical row' = 'Plant GM1'; 'Expected' = '1'; 'Actual' = $plantCount }
    [pscustomobject]@{ 'Canonical row' = 'Machine group Forming (under GM1)'; 'Expected' = '1'; 'Actual' = $groupCount }
    [pscustomobject]@{ 'Canonical row' = 'Machine (code / name / status)'; 'Expected' = 'BF-08410 / JBF19 [ACTIVE]'; 'Actual' = $machineInfo }
    [pscustomobject]@{ 'Canonical row' = 'Sparepart (UTF-8 label tripwire)'; 'Expected' = 'BF-08410GM1ELEPLCWEC000 | Electric · PLC · Wecon · LX5'; 'Actual' = $sparepartInfo }
    [pscustomobject]@{ 'Canonical row' = 'Installation expected/baseline/threshold'; 'Expected' = '1000/0/90'; 'Actual' = $installationInfo }
    [pscustomobject]@{ 'Canonical row' = 'Pilot recipient users'; 'Expected' = '3'; 'Actual' = $userCount }
    [pscustomobject]@{ 'Canonical row' = 'Machine responsibilities by level'; 'Expected' = 'LEADER: 1; STAFF: 1; TECHNICIAN: 1'; 'Actual' = $levelCounts }
)
$summary | Format-Table -AutoSize | Out-String -Width 220 | ForEach-Object { $_.TrimEnd() } | Write-Host

$mismatchFound = $false
foreach ($row in $summary) {
    if ([string]$row.Actual -ne [string]$row.Expected) {
        $mismatchFound = $true
        Write-Warning "row '$($row.'Canonical row')': expected '$($row.Expected)', got '$($row.Actual)' - see the seed header's pre-existing-data notes"
    }
}

Write-Host ''
if ($mismatchFound) {
    Write-Host 'Seed apply finished WITH MISMATCHES'
    exit 1
}
Write-Host 'Seed apply complete. Re-run safe: guarded INSERTs, so a second run prints INSERT 0 0 and the identical summary.'
exit 0
