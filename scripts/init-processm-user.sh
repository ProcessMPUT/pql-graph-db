#!/bin/bash
# Auto-create ProcessM user account after container startup.
# Called by docker-compose healthcheck or manually.
#
# Reads credentials from application.yml defaults:
#   login: admin@example.com
#   password: Admin1234

PROCESSM_URL="${PROCESSM_URL:-http://localhost:80/api}"
USER_EMAIL="${PROCESSM_LOGIN:-admin@example.com}"
USER_PASSWORD="${PROCESSM_PASSWORD:-Admin1234}"
ORG_NAME="${PROCESSM_ORG:-TestOrg}"

MAX_RETRIES=30
RETRY_INTERVAL=5

echo "Waiting for ProcessM API at $PROCESSM_URL ..."

for i in $(seq 1 $MAX_RETRIES); do
  # Check if API is reachable
  status=$(curl -s -o /dev/null -w "%{http_code}" "$PROCESSM_URL/users/session" 2>/dev/null)
  if [ "$status" != "000" ]; then
    echo "ProcessM API is reachable (HTTP $status)"
    break
  fi
  echo "  Attempt $i/$MAX_RETRIES - not ready, waiting ${RETRY_INTERVAL}s..."
  sleep $RETRY_INTERVAL
done

# Try to login first — if it works, user already exists
login_resp=$(curl -s -X POST "$PROCESSM_URL/users/session" \
  -H 'Content-Type: application/json' \
  -d "{\"login\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}" 2>/dev/null)

if echo "$login_resp" | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if 'authorizationToken' in d else 1)" 2>/dev/null; then
  echo "User '$USER_EMAIL' already exists and can log in. Done."
  exit 0
fi

# User doesn't exist — create it
echo "Creating user '$USER_EMAIL' ..."
create_resp=$(curl -s -X POST "$PROCESSM_URL/users" \
  -H 'Content-Type: application/json' \
  -d "{\"userEmail\":\"$USER_EMAIL\",\"userPassword\":\"$USER_PASSWORD\",\"newOrganization\":true,\"organizationName\":\"$ORG_NAME\"}" 2>/dev/null)

# Check for success (201) or "already exists"
if echo "$create_resp" | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if 'error' not in d else 1)" 2>/dev/null; then
  echo "User created successfully."
elif echo "$create_resp" | grep -q "already exists"; then
  echo "User already exists."
else
  echo "User creation response: $create_resp"
fi

# Verify login works
login_resp=$(curl -s -X POST "$PROCESSM_URL/users/session" \
  -H 'Content-Type: application/json' \
  -d "{\"login\":\"$USER_EMAIL\",\"password\":\"$USER_PASSWORD\"}" 2>/dev/null)

if echo "$login_resp" | python3 -c "import sys,json; d=json.load(sys.stdin); exit(0 if 'authorizationToken' in d else 1)" 2>/dev/null; then
  echo "Login verified OK."
else
  echo "WARNING: Login failed after creation: $login_resp"
  exit 1
fi
