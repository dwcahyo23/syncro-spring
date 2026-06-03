$ErrorActionPreference = 'Stop'

$syncroRoot = Split-Path -Parent $PSScriptRoot
$webRoot = Join-Path $syncroRoot 'apps/web'

$env:NEXT_PUBLIC_API_URL = 'http://localhost:8080/api/v1'
Write-Host "Building Next.js app..." -ForegroundColor Cyan
npm --prefix $webRoot run build

Write-Host "Starting Next.js production server..." -ForegroundColor Green
npm --prefix $webRoot run start -- -p 3001