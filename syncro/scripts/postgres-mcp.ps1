# Read-only PostgreSQL MCP server launcher.
# Loads credentials from syncro/.env so secrets never appear in opencode.json.
param()

$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "syncro/.env not found at $envFile"
}

$vars = @{}
Get-Content -LiteralPath $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line -match "^([A-Za-z_][A-Za-z0-9_]*)=(.*)$") {
        $vars[$matches[1]] = $matches[2].Trim('"').Trim("'")
    }
}

foreach ($key in @("POSTGRES_USER", "POSTGRES_PASSWORD", "POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB")) {
    if (-not $vars.ContainsKey($key)) {
        throw "Missing $key in syncro/.env"
    }
}

$conn = "postgresql://{0}:{1}@{2}:{3}/{4}" -f $vars.POSTGRES_USER, $vars.POSTGRES_PASSWORD, $vars.POSTGRES_HOST, $vars.POSTGRES_PORT, $vars.POSTGRES_DB
& npx -y @modelcontextprotocol/server-postgres $conn