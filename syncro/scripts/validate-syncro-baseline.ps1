$requiredDirs = @(
  'syncro/apps/backend',
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
  'syncro/docs/local-development.md'
)

$requiredEnv = @(
  'POSTGRES_HOST', 'POSTGRES_PORT', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
  'PGADMIN_EMAIL', 'PGADMIN_PASSWORD',
  'REDIS_HOST', 'REDIS_PORT',
  'INFLUXDB_HOST', 'INFLUXDB_PORT', 'INFLUXDB_TOKEN', 'INFLUXDB_ORG', 'INFLUXDB_BUCKET',
  'SYNCRO_MQTT_HOST', 'SYNCRO_MQTT_PORT', 'SYNCRO_MQTT_USERNAME', 'SYNCRO_MQTT_PASSWORD', 'SYNCRO_MQTT_CLIENT_ID', 'SYNCRO_MQTT_TOPIC_FILTER',
  'WAHA_HOST', 'WAHA_PORT', 'WAHA_API_KEY',
  'SPRING_PROFILES_ACTIVE', 'SERVER_PORT',
  'NEXT_PUBLIC_API_URL'
)

$missing = @()
foreach ($dir in $requiredDirs) {
  if (-not (Test-Path $dir -PathType Container)) { $missing += "missing directory: $dir" }
}
foreach ($file in $requiredFiles) {
  if (-not (Test-Path $file -PathType Leaf)) { $missing += "missing file: $file" }
}
if (Test-Path 'syncro/.env.example' -PathType Leaf) {
  $envContent = Get-Content 'syncro/.env.example' -Raw
  foreach ($key in $requiredEnv) {
    if ($envContent -notmatch "(?m)^$key=") { $missing += "missing env key: $key" }
  }
}
if ($missing.Count -gt 0) {
  $missing | ForEach-Object { Write-Error $_ }
  exit 1
}
Write-Output 'Syncro baseline structure validation passed.'
