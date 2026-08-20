#!/bin/sh
set -e

# Write admin token to internal file with secure permissions
TOKEN_FILE="/tmp/influxdb3-admin-token"
printf '{"token":"%s","name":"_admin"}' "${INFLUXDB3_ADMIN_TOKEN}" > "${TOKEN_FILE}"
chmod 600 "${TOKEN_FILE}"

# Start influxdb3 with the token file
exec influxdb3 serve \
  --node-id syncro-node-1 \
  --object-store file \
  --data-dir /var/lib/influxdb3 \
  --http-bind 0.0.0.0:8181 \
  --admin-token-file "${TOKEN_FILE}" \
  --disable-authz health,ping
