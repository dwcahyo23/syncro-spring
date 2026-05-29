$ErrorActionPreference = 'Stop'

$syncroRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $syncroRoot 'apps/web'

$env:NEXT_PUBLIC_API_URL = 'http://localhost:8080/api/v1'
npm --prefix $webRoot run dev -- -p 3001