$ErrorActionPreference = 'Stop'

$syncroRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $syncroRoot '.env.example'
$backendRoot = Join-Path $syncroRoot 'apps/backend'
$mvnw = Join-Path $backendRoot 'mvnw.cmd'
$pom = Join-Path $backendRoot 'pom.xml'

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^([^#=][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}

& $mvnw -f $pom spring-boot:run