$ErrorActionPreference = 'Stop'

$syncroRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $syncroRoot '.env'
$backendRoot = Join-Path $syncroRoot 'apps/backend'
$mvnw = Join-Path $backendRoot 'mvnw.cmd'
$pom = Join-Path $backendRoot 'pom.xml'

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^([^#=][^=]*)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
    }
}

$env:SERVER_PORT = '8081'
& $mvnw -f $pom spring-boot:run
