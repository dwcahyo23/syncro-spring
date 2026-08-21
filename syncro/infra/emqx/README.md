# EMQX Infrastructure

EMQX 6 MQTT broker for Syncro. Handles device telemetry ingestion and backend subscription.

## Files

| File | Purpose |
|------|---------|
| `etc/emqx.conf` | Node, cluster, authentication, authorization config |
| `etc/auth-bootstrap.csv` | Initial user credentials (seeded on first start) |
| `etc/docker-entrypoint.sh` | Startup script: sets dashboard password, seeds ACL rules |

## Authentication

Users are seeded from `auth-bootstrap.csv` on first container start via `bootstrap_file`. The file uses plaintext passwords which EMQX hashes with bcrypt (`salt_rounds = 10`) on import.

To add a new user after first boot, use the EMQX dashboard (port 18083) or the management API. The bootstrap file is only processed once on initial data directory creation.

## Authorization (ACL)

Policy: **deny-by-default**. Any publish or subscribe not explicitly allowed is denied and the client is disconnected (`deny_action = disconnect`).

ACL rules are seeded at container startup via `docker-entrypoint.sh` using `emqx ctl authz`. Rules are stored in the EMQX built-in database and persist across restarts via the `emqx_data` Docker volume.

### Current Rules

| User | Action | Topic | Purpose |
|------|--------|-------|---------|
| `syncro_backend` | subscribe | `factory/+/+/telemetry` | Spring Boot MQTT consumer — reads all plant/machine telemetry (two dynamic segments, publish denied) |
| `device_GM1_BF-08410` | publish | `factory/GM1/BF-08410/telemetry` | Canonical pilot device — machine BF-08410 in plant GM1 |

All other actions are denied.

Note: the `auth-bootstrap.csv` credential for a device only loads on first EMQX data-volume creation (bootstrap is first-start-only), while the ACL rules in `docker-entrypoint.sh` are upserted on every container start — after editing the CSV on an existing stack, add the credential via the EMQX dashboard/API and restart the container to pick up the ACL rule (see "Adding a New Device").

- Legacy note: on stacks bootstrapped BEFORE the username/topic convention fix, the old swapped rule (`device_BF-08410_GM1` -> `factory/BF-08410/GM1/telemetry`) lingers in the EMQX built-in DB (upserts never delete) and can be removed via the EMQX Dashboard (Access Control -> ACL) or the Management API; fresh stacks only get the corrected rule.

### Device Credential Convention

Each registered machine gets its own MQTT credential:

```
username: device_{plantCode}_{machineCode}
topic:    factory/{plantCode}/{machineCode}/telemetry
```

Example — machine `CNC-01` in plant `JKT-001`:
- username: `device_JKT-001_CNC-01`
- allowed publish topic: `factory/JKT-001/CNC-01/telemetry`

### Adding a New Device

1. Add credentials to `etc/auth-bootstrap.csv` (only works before first start) **or** add via EMQX dashboard after first boot:
   ```
   device_JKT-001_CNC-01,<strong-password>,false
   ```

2. Add a publish ACL rule in `docker-entrypoint.sh` using the EMQX Management API:
   ```sh
   curl -sf -X POST "http://localhost:18083/api/v5/authorization/sources/built_in_database/rules/users" \
     -H "Content-Type: application/json" \
     -u "${SEED_API_KEY}:${SEED_SECRET}" \
     -d '[{"username":"device_JKT-001_CNC-01","rules":[{"topic":"factory/JKT-001/CNC-01/telemetry","action":"publish","permission":"allow"}]}]'
   ```
   Then add the same `curl` block to `docker-entrypoint.sh` so it runs on every container start.

3. Restart the EMQX container:
   ```sh
   docker compose restart emqx
   ```

## TLS Configuration (Production)

Development uses plaintext TCP on port 1883. Production requires TLS on port 8883.

### Steps to Enable TLS

1. Generate or obtain TLS certificates:
   ```sh
   # Self-signed (for testing only)
   openssl req -x509 -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -nodes
   ```

2. Place certificates in `infra/emqx/certs/`:
   ```
   infra/emqx/certs/
     ca.crt       # CA certificate
     server.crt   # Server certificate
     server.key   # Server private key
   ```

3. Mount the certs directory in `docker-compose.yml`:
   ```yaml
   volumes:
     - ./emqx/certs:/opt/emqx/etc/certs:ro
   ```

4. Expose port 8883 in `docker-compose.yml`:
   ```yaml
   ports:
     - "${SYNCRO_MQTT_TLS_PORT:-8883}:8883"
   ```

5. Uncomment the TLS listener block at the bottom of `etc/emqx.conf`.

6. For production: disable the plaintext listener (port 1883) by adding to `emqx.conf`:
   ```hocon
   listeners.tcp.default {
     enable = false
   }
   ```

## Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 1883 | MQTT TCP (plaintext) | Device and backend connections (dev) |
| 8883 | MQTT TLS | Device and backend connections (production, requires TLS setup) |
| 18083 | HTTP | EMQX dashboard |

## Smoke Testing ACL

Requires `mosquitto-clients` installed locally.

(replace <device-password> with the device row's password from the local auth-bootstrap.csv)

```sh
# Should SUCCEED: backend subscribes to all telemetry
mosquitto_sub -h localhost -p 1883 \
  -u syncro_backend -P "Syncro@Mqtt#2026!Dev" \
  -t "factory/+/+/telemetry" -C 1 -W 3

# Should FAIL: unauthenticated client is rejected
mosquitto_pub -h localhost -p 1883 \
  -t "factory/GM1/BF-08410/telemetry" -m '{"running":true}'

# Should SUCCEED: device publishes to its own topic
mosquitto_pub -h localhost -p 1883 \
  -u device_GM1_BF-08410 -P "<device-password>" \
  -t "factory/GM1/BF-08410/telemetry" -m '{"running":true}'

# Should FAIL: device publishes to a different machine topic
mosquitto_pub -h localhost -p 1883 \
  -u device_GM1_BF-08410 -P "<device-password>" \
  -t "factory/GM1/CNC-01/telemetry" -m '{"running":true}'

# Should FAIL: device tries to subscribe (not permitted)
mosquitto_sub -h localhost -p 1883 \
  -u device_GM1_BF-08410 -P "<device-password>" \
  -t "factory/#" -C 1 -W 3
```
