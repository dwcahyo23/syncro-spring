#!/bin/sh
set -e

# Start EMQX in background
/opt/emqx/bin/emqx foreground &
EMQX_PID=$!

# Wait for EMQX to be ready (timeout: 60s)
echo "Waiting for EMQX to start..."
WAIT_COUNT=0
until /opt/emqx/bin/emqx ctl status > /dev/null 2>&1; do
  WAIT_COUNT=$((WAIT_COUNT + 1))
  if [ "$WAIT_COUNT" -ge 30 ]; then
    echo "ERROR: EMQX did not become ready after 60s. Aborting."
    kill "$EMQX_PID" 2>/dev/null || true
    exit 1
  fi
  sleep 2
done
echo "EMQX is ready."

# Set dashboard password from environment variable
if [ -n "$EMQX_DASHBOARD__DEFAULT_PASSWORD" ] && [ -n "$EMQX_DASHBOARD__DEFAULT_USERNAME" ]; then
  echo "Setting dashboard password for user: $EMQX_DASHBOARD__DEFAULT_USERNAME"
  /opt/emqx/bin/emqx ctl admins passwd "$EMQX_DASHBOARD__DEFAULT_USERNAME" "$EMQX_DASHBOARD__DEFAULT_PASSWORD" 2>&1 || true
fi

# =============================================================================
# Seed ACL (authorization) rules into the built-in database.
#
# These rules enforce deny-by-default topic access control (see emqx.conf).
# Uses EMQX Management API (port 18083). Rules are upserted — idempotent.
#
# Convention for device usernames: device_{plantCode}_{machineCode}
#   e.g. device_GM1_BF-08410 for machine BF-08410 in plant GM1
#
# To add a new device:
#   1. Add the device credentials to auth-bootstrap.csv (or via EMQX dashboard)
#   2. Add a curl POST block below for that device (see README.md for the exact command)
#   3. Restart the EMQX container
# =============================================================================

SEED_KEY_NAME="syncro-acl-seed"
SEED_SECRET="SyncroAclSeedKeyForInfraBootstrap2026!"
SEED_KEY_FILE="/opt/emqx/data/syncro-acl-seed.key"

echo "Seeding ACL rules..."

# Obtain or create the seeding API key.
# The api_key ID is stored in a file on the data volume for persistence across restarts.
if [ -f "${SEED_KEY_FILE}" ]; then
  SEED_API_KEY=$(cat "${SEED_KEY_FILE}")
else
  CREATE_OUTPUT=$(/opt/emqx/bin/emqx ctl api_keys add \
    --name "${SEED_KEY_NAME}" \
    --api-secret "${SEED_SECRET}" \
    --role administrator 2>&1)
  SEED_API_KEY=$(echo "${CREATE_OUTPUT}" | grep '"api_key"' | sed 's/.*"api_key" : "\([^"]*\)".*/\1/')
  if [ -n "${SEED_API_KEY}" ]; then
    echo "${SEED_API_KEY}" > "${SEED_KEY_FILE}"
  fi
fi

if [ -z "${SEED_API_KEY}" ]; then
  echo "ERROR: Could not obtain API key for ACL seeding. Skipping."
else
  # Wait for management API to be available (timeout: 30s)
  API_WAIT=0
  until curl -sf http://localhost:18083/api/v5/status > /dev/null 2>&1; do
    API_WAIT=$((API_WAIT + 1))
    if [ "$API_WAIT" -ge 30 ]; then
      echo "ERROR: Management API did not become ready after 30s. Skipping ACL seeding."
      SEED_API_KEY=""
      break
    fi
    sleep 1
  done
fi

if [ -n "${SEED_API_KEY}" ]; then
  # Verify the API key is still valid (detects stale key file after volume wipe).
  # If the key returns 401, delete the stale file and skip seeding with a clear error.
  HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -u "${SEED_API_KEY}:${SEED_SECRET}" \
    "http://localhost:18083/api/v5/api_key")
  if [ "$HTTP_STATUS" = "401" ]; then
    echo "ERROR: API key in ${SEED_KEY_FILE} is invalid (HTTP 401). Deleting stale key file."
    rm -f "${SEED_KEY_FILE}"
    echo "Re-run the container to re-create the API key and re-seed ACL rules."
    SEED_API_KEY=""
  fi
fi

if [ -n "${SEED_API_KEY}" ]; then
  # syncro_backend: may subscribe to telemetry topics (exactly two dynamic segments)
  # This is the Spring Boot MQTT consumer credential.
  curl -sf -X POST "http://localhost:18083/api/v5/authorization/sources/built_in_database/rules/users" \
    -H "Content-Type: application/json" \
    -u "${SEED_API_KEY}:${SEED_SECRET}" \
    -d '[{"username":"syncro_backend","rules":[{"topic":"factory/+/+/telemetry","action":"subscribe","permission":"allow"}]}]' \
    > /dev/null && echo "ACL rule seeded: syncro_backend" || echo "WARNING: Failed to seed ACL rule for syncro_backend"

  # device_GM1_BF-08410: may publish ONLY to its own topic
  # (canonical pilot device: machine BF-08410 in plant GM1)
  # Add one block like this for each registered device.
  curl -sf -X POST "http://localhost:18083/api/v5/authorization/sources/built_in_database/rules/users" \
    -H "Content-Type: application/json" \
    -u "${SEED_API_KEY}:${SEED_SECRET}" \
    -d '[{"username":"device_GM1_BF-08410","rules":[{"topic":"factory/GM1/BF-08410/telemetry","action":"publish","permission":"allow"}]}]' \
    > /dev/null && echo "ACL rule seeded: device_GM1_BF-08410" || echo "WARNING: Failed to seed ACL rule for device_GM1_BF-08410"

  echo "ACL seeding complete."
fi

# Wait for EMQX process to exit
wait $EMQX_PID
