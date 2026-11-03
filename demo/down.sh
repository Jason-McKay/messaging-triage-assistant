#!/usr/bin/env bash
# Stops and removes both demo stacks.
#   notification-platform: volumes kept. Its MySQL (np_mysql_data) is the one its own
#     compose uses, so its notifications, and the next triage's NP counts, carry over.
#   message-inspector: removed with its volumes (-v, the mi-demo project only), so the next
#     demo broker starts empty.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
NP_DIR="${NP_DIR:-$root/../notification-platform}"
MI_DIR="${MI_DIR:-$root/../message-inspector}"

docker compose -p mi-demo -f "$MI_DIR/compose.yaml" -f "$here/message-inspector.ports.yaml" down -v
docker compose -f "$NP_DIR/compose.yaml" down
