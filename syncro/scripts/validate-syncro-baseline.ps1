$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')

$requiredDirs = @(
  'syncro/apps/backend',
  'syncro/apps/backend/src/main/java/com/syncro',
  'syncro/apps/backend/src/main/resources/db/migration',
  'syncro/apps/backend/src/test/java/com/syncro',
  'syncro/apps/backend/src/test/resources/fixtures',
  'syncro/apps/web',
  'syncro/apps/web/src/app',
  'syncro/apps/web/src/components/ui',
  'syncro/apps/web/src/components/syncro',
  'syncro/apps/web/src/features',
  'syncro/apps/web/src/features/operations',
  'syncro/apps/web/src/features/machines',
  'syncro/apps/web/src/features/telemetry',
  'syncro/apps/web/src/features/alerts',
  'syncro/apps/web/src/features/master-data',
  'syncro/apps/web/src/features/waha-templates',
  'syncro/apps/web/src/features/audit-log',
  'syncro/apps/web/src/features/system-health',
  'syncro/apps/web/src/features/settings',
  'syncro/apps/web/src/lib/api',
  'syncro/apps/web/src/lib/auth',
  'syncro/apps/web/src/lib/formatting',
  'syncro/apps/web/src/stores',
  'syncro/apps/web/src/types',
  'syncro/infra/postgres/init',
  'syncro/infra/pgadmin',
  'syncro/infra/redis',
  'syncro/infra/influxdb',
  'syncro/infra/emqx/etc',
  'syncro/infra/waha',
  'syncro/scripts',
  'syncro/docs',
  'syncro/tests/e2e',
  'syncro/tests/fixtures'
)

$requiredFiles = @(
  'syncro/.env.example',
  'syncro/.gitignore',
  'syncro/README.md',
  'syncro/docs/local-development.md',
  'syncro/infra/docker-compose.yml',
  'syncro/apps/backend/pom.xml',
  'syncro/apps/backend/src/main/java/com/syncro/SyncroBackendApplication.java',
  'syncro/apps/backend/src/main/java/com/syncro/api/HealthController.java',
  'syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java',
  'syncro/apps/backend/src/main/java/com/syncro/config/MqttProperties.java',
  'syncro/apps/backend/src/main/resources/application.yml',
  'syncro/apps/backend/src/main/resources/application-local.yml',
  'syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java',
  'syncro/apps/backend/src/test/java/com/syncro/api/HealthControllerTest.java',
  'syncro/apps/web/package.json',
  'syncro/apps/web/src/app/globals.css',
  'syncro/apps/web/src/app/(main)/dashboard/layout.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/operations-overview/page.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/telemetry/page.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/alerts/page.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/master-data/plants/page.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/waha-templates/page.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/audit-log/page.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/system-health/page.tsx',
  'syncro/apps/web/src/app/(main)/dashboard/settings/page.tsx',
  'syncro/apps/web/src/navigation/sidebar/sidebar-items.ts',
  'syncro/apps/web/src/components/syncro/module-placeholder.tsx'
)

$requiredEnv = @(
  'POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
  'PGADMIN_EMAIL', 'PGADMIN_PASSWORD', 'PGADMIN_PORT',
  'REDIS_HOST', 'REDIS_PORT',
  'INFLUXDB_HOST', 'INFLUXDB_PORT', 'INFLUXDB_USERNAME', 'INFLUXDB_PASSWORD', 'INFLUXDB_TOKEN', 'INFLUXDB_ORG', 'INFLUXDB_BUCKET',
  'SYNCRO_MQTT_HOST', 'SYNCRO_MQTT_PORT', 'SYNCRO_MQTT_DASHBOARD_PORT', 'SYNCRO_MQTT_USERNAME', 'SYNCRO_MQTT_PASSWORD', 'SYNCRO_MQTT_CLIENT_ID', 'SYNCRO_MQTT_TOPIC_FILTER',
  'WAHA_HOST', 'WAHA_PORT', 'WAHA_API_KEY',
  'SPRING_PROFILES_ACTIVE', 'SERVER_PORT',
  'NEXT_PUBLIC_API_URL'
)

$missing = @()
foreach ($dir in $requiredDirs) {
  $path = Join-Path $repoRoot $dir
  if (-not (Test-Path $path -PathType Container)) { $missing += "missing directory: $dir" }
}
foreach ($file in $requiredFiles) {
  $path = Join-Path $repoRoot $file
  if (-not (Test-Path $path -PathType Leaf)) { $missing += "missing file: $file" }
}
$envExamplePath = Join-Path $repoRoot 'syncro/.env.example'
if (Test-Path $envExamplePath -PathType Leaf) {
  $envContent = Get-Content $envExamplePath -Raw
  foreach ($key in $requiredEnv) {
    if ($envContent -notmatch "(?m)^$key=") { $missing += "missing env key: $key" }
  }
}

$composePath = Join-Path $repoRoot 'syncro/infra/docker-compose.yml'
if (Test-Path $composePath -PathType Leaf) {
  $composeContent = Get-Content $composePath -Raw
  foreach ($service in @('postgres', 'pgadmin', 'redis', 'influxdb', 'emqx', 'waha')) {
    if ($composeContent -notmatch "(?m)^  ${service}:") { $missing += "missing compose service: $service" }
  }
  if (Get-Command docker -ErrorAction SilentlyContinue) {
    $envPath = Join-Path $repoRoot 'syncro/.env.example'
    $composeResult = docker compose --env-file $envPath -f $composePath config --quiet 2>&1
    if ($LASTEXITCODE -ne 0) { $missing += "invalid compose config: $composeResult" }
  }
}

$localDevelopmentPath = Join-Path $repoRoot 'syncro/docs/local-development.md'
if (Test-Path $localDevelopmentPath -PathType Leaf) {
  $localDevelopmentContent = Get-Content $localDevelopmentPath -Raw
  if ($localDevelopmentContent -notmatch 'pgAdmin is local/dev only') { $missing += 'missing pgAdmin local/dev-only documentation' }
  if ($localDevelopmentContent -notmatch 'mvn -f syncro/apps/backend/pom.xml test') { $missing += 'missing backend test command documentation' }
  if ($localDevelopmentContent -notmatch 'http://localhost:8080/api/v1/health') { $missing += 'missing backend smoke endpoint documentation' }
  if ($localDevelopmentContent -notmatch 'npm --prefix syncro/apps/web run dev') { $missing += 'missing frontend dev command documentation' }
  if ($localDevelopmentContent -notmatch 'npm --prefix syncro/apps/web run build') { $missing += 'missing frontend build command documentation' }
}

$webPackagePath = Join-Path $repoRoot 'syncro/apps/web/package.json'
if (Test-Path $webPackagePath -PathType Leaf) {
  $webPackageContent = Get-Content $webPackagePath -Raw
  foreach ($dependency in @('next', 'react', 'tailwindcss', 'react-hook-form', 'zod', '@tanstack/react-table', 'zustand', '@biomejs/biome')) {
    if ($webPackageContent -notmatch [regex]::Escape($dependency)) { $missing += "missing frontend dependency: $dependency" }
  }
  foreach ($script in @('"dev"', '"build"', '"lint"', '"check"')) {
    if ($webPackageContent -notmatch [regex]::Escape($script)) { $missing += "missing frontend script: $script" }
  }
}

$webSidebarPath = Join-Path $repoRoot 'syncro/apps/web/src/navigation/sidebar/sidebar-items.ts'
if (Test-Path $webSidebarPath -PathType Leaf) {
  $webSidebarContent = Get-Content $webSidebarPath -Raw
  foreach ($label in @('Operations Overview', 'Telemetry', 'Alerts', 'Master Data', 'WAHA Templates', 'Audit Log', 'System Health', 'Settings')) {
    if ($webSidebarContent -notmatch [regex]::Escape($label)) { $missing += "missing frontend navigation item: $label" }
  }
}

$webGlobalsPath = Join-Path $repoRoot 'syncro/apps/web/src/app/globals.css'
if (Test-Path $webGlobalsPath -PathType Leaf) {
  $webGlobalsContent = Get-Content $webGlobalsPath -Raw
  foreach ($token in @('--syncro-status-healthy', '--syncro-status-warning', '--syncro-status-critical', '--syncro-status-info', '--syncro-status-neutral', '.font-tabular', '.font-mono-tight')) {
    if ($webGlobalsContent -notmatch [regex]::Escape($token)) { $missing += "missing frontend style token: $token" }
  }
}

$backendPomPath = Join-Path $repoRoot 'syncro/apps/backend/pom.xml'
if (Test-Path $backendPomPath -PathType Leaf) {
  $backendPomContent = Get-Content $backendPomPath -Raw
  foreach ($dependency in @('spring-boot-starter-webmvc', 'spring-boot-starter-validation', 'spring-boot-starter-security', 'spring-boot-starter-data-jpa', 'postgresql', 'spring-boot-starter-flyway', 'spring-boot-starter-data-redis', 'spring-boot-starter-actuator', 'spring-integration-mqtt', 'testcontainers')) {
    if ($backendPomContent -notmatch [regex]::Escape($dependency)) { $missing += "missing backend dependency: $dependency" }
  }
  if ($backendPomContent -notmatch '<java.version>25</java.version>') { $missing += 'backend Java version is not 25' }
}

$backendConfigPath = Join-Path $repoRoot 'syncro/apps/backend/src/main/resources/application.yml'
if (Test-Path $backendConfigPath -PathType Leaf) {
  $backendConfigContent = Get-Content $backendConfigPath -Raw
  foreach ($key in @('POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'SYNCRO_MQTT_HOST', 'SYNCRO_MQTT_PORT', 'SYNCRO_MQTT_USERNAME', 'SYNCRO_MQTT_PASSWORD', 'SYNCRO_MQTT_CLIENT_ID', 'SYNCRO_MQTT_TOPIC_FILTER', 'SERVER_PORT')) {
    if ($backendConfigContent -notmatch [regex]::Escape($key)) { $missing += "missing backend config env key: $key" }
  }
}
if ($missing.Count -gt 0) {
  $missing | ForEach-Object { Write-Error $_ }
  exit 1
}
Write-Output 'Syncro baseline structure validation passed.'



