# Syncro Authorization Policy (OPA/Rego)

The `syncro.authz` Rego bundle is the machine-readable mirror of the in-service
authorization gates (story 9-5). The backend sidecar (`opa` in `syncro/infra/docker-compose.yml`)
serves `allow` and `actions` from this directory.

## Layout

- `policy/authz.rego` — the policy: SUPER_ADMIN bypass, MANAGER_MAINTENANCE mutations,
  authenticated reads, SUPER_ADMIN-only telemetry/worker endpoints, allowed-actions set.
- `policy/authz_test.rego` — the parity matrix enumerating every (role, method, path)
  pairing that a real authenticated user could attempt.
- `run-opa-test.ps1` — local/CI test runner (uses the OPA Docker image).

## Running the tests locally

```powershell
.\syncro\authz\run-opa-test.ps1
```

This mounts `syncro/authz/policy` read-only into the `openpolicyagent/opa:1.19.1` image
and runs `opa test /policy`. Expected: `PASS: 38/38`.

## CI wiring

Run the same script in CI (a PowerShell-capable job) as the policy gate:

- `syncro/authz/run-opa-test.ps1` (Windows) — the primary wrapper.
- Equivalent one-liner on Linux/macOS:

  ```sh
  docker run --rm -v "$(pwd)/syncro/authz/policy:/policy" openpolicyagent/opa:1.19.1 test /policy
  ```

A failed policy test must fail the pipeline: the bundle shipped to the sidecar is the
enforcement contract, and `docker-compose.yml` mounts it as the OPA data directory.

## Loading the policy into the sidecar

`docker compose -f syncro/infra/docker-compose.yml up -d opa` mounts `../authz/policy`
(relative to `syncro/infra/`) into the container as `/policies` and starts OPA with that
directory, so the running server evaluates exactly the committed bundle.

Verify a rule round-trip against the running sidecar:

```sh
curl -X POST "http://localhost:${OPA_PORT:-18181}/v1/data/syncro/authz/allow" \
  -H 'content-type: application/json' \
  -d '{"input":{"subject":{"roles":["TECHNICIAN"],"userId":"u1"},"action":"GET /api/v1/machines"}}'
```

Expected: `{"result": true, ...}` (authenticated reads are allowed; see the parity matrix
in `policy/authz_test.rego` for the full behavior).
