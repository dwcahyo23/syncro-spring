# Run OPA policy tests for the Syncro authorization bundle.
# Requires Docker (the OPA image is used since the opa binary is not in PATH).
# Returns exit code 0 on pass, 1 on failure.

$ErrorActionPreference = "Stop"

$policyDir = Join-Path -Path $PSScriptRoot -ChildPath "policy"
$policyDir = Resolve-Path -LiteralPath $policyDir

Write-Host "[opa-test] Running policy tests from: $policyDir"

docker run --rm -v "${policyDir}:/policy" openpolicyagent/opa:1.19.1-debug test /policy -v

if ($LASTEXITCODE -eq 0) {
    Write-Host "[opa-test] All tests passed."
    exit 0
} else {
    Write-Host "[opa-test] FAILED."
    exit 1
}