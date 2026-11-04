#!/usr/bin/env bash
# One triage against the running app (after demo/seed), printed short: each source's status,
# the model outcome and the group ids. The full answer is in the JSON; this is the glance.
set -euo pipefail

APP="${APP:-http://localhost:8085}"
out=$(curl -sS -f -X POST "$APP/api/triage")

printf '%s' "$out" | grep -o '"source":"[^"]*","id":"[A-Z]*","status":"[A-Z]*"' \
  | sed -E 's/"source":"([^"]*)","id":"([A-Z]*)","status":"([A-Z]*)"/\1 (\2): \3/'
echo "model: $(printf '%s' "$out" | grep -o '"model":{[^}]*}' | head -1)"
echo "groups: $(printf '%s' "$out" | grep -o '"groupId":"[A-Z]*-[0-9]*"' | sed -E 's/.*:"(.*)"/\1/' | sort -u | tr '\n' ' ')"
