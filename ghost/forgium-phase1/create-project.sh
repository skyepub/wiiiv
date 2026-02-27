#!/bin/bash
# Forgium Phase 1 - Project Creator Helper
# Usage: ./create-project.sh <project-name> <db-name> <port> <package> <message>

set -e
WIIIV_HOST="http://localhost:8235"

# Get token
TOKEN=$(curl -s "$WIIIV_HOST/api/v2/auth/auto-login" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

# Create session
SESSION_ID=$(curl -s -X POST "$WIIIV_HOST/api/v2/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\": \"$1\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['sessionId'])")

echo "Session: $SESSION_ID"
echo "Sending message..."

# Send message and capture response
python3 -c "
import json, sys
msg = sys.stdin.read()
print(json.dumps({'message': msg, 'autoContinue': True, 'maxContinue': 10}))
" <<< "$5" | curl -s -N -X POST "$WIIIV_HOST/api/v2/sessions/$SESSION_ID/chat" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d @- \
  --max-time 300

echo ""
echo "Session ID: $SESSION_ID"
