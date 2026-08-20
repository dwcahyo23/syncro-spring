$ErrorActionPreference = "Stop"

$chromium = Get-ChildItem (Join-Path $env:LOCALAPPDATA "ms-playwright") -Recurse -Filter "chrome.exe" -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -match "chromium-[^\\]+\\chrome-win" } |
  Sort-Object FullName -Descending |
  Select-Object -First 1

if ($chromium) {
  $exe = $chromium.FullName
} else {
  $edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
  if (Test-Path $edge) { $exe = $edge } else { throw "No Chromium/Chrome executable found for chrome-devtools-mcp." }
}

& npx.cmd -y chrome-devtools-mcp@latest --executablePath "$exe" --isolated
