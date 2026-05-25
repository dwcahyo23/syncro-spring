$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')

$requiredDirs = @(
  'syncro/apps/backend',
  'syncro/apps/backend/src/main/java/com/syncro',
  'syncro/apps/backend/src/main/resources/db/migration',
  'syncro/apps/backend/src/test/java/com/syncro',
  'syncro/apps/backend/src/test/resources/fixtures',
  'syncro/apps/web',
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
  'syncro/apps/backend/src/test/java/com/syncro/api/HealthControllerTest.java'
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



