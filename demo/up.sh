#!/usr/bin/env bash
# Starts the two demo stacks from their own repos, unmodified:
#   notification-platform: api :8080, worker :8081 (plus its Kafka, Redis, MySQL, Mailpit)
#   message-inspector:     app :8082, Artemis :61617, console and Jolokia :8162
# The sibling repos are found next to this one; set NP_DIR or MI_DIR to look elsewhere.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
NP_DIR="${NP_DIR:-$root/../notification-platform}"
MI_DIR="${MI_DIR:-$root/../message-inspector}"

for dir in "$NP_DIR" "$MI_DIR"; do
  [ -f "$dir/compose.yaml" ] || { echo "no compose.yaml in $dir (set NP_DIR / MI_DIR)" >&2; exit 1; }
done

docker compose -f "$NP_DIR/compose.yaml" up -d --build api worker
docker compose -p mi-demo -f "$MI_DIR/compose.yaml" -f "$here/message-inspector.ports.yaml" up -d --build

# Spring Boot opens its port only once the context is up, so any HTTP answer means ready.
wait_for() {
  for _ in $(seq 1 90); do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' "$2" || true)" != "000" ]; then
      echo "$1 is up on $2"
      return 0
    fi
    sleep 2
  done
  echo "$1 did not answer on $2 within 3 minutes" >&2
  return 1
}
wait_for "notification api" http://localhost:8080/actuator/health
wait_for "notification worker" http://localhost:8081/actuator/health
wait_for "message-inspector" http://localhost:8082/
wait_for "Artemis Jolokia" http://localhost:8162/console/jolokia

if ! curl -s http://localhost:11434/api/tags | grep -q '"qwen3:8b"'; then
  echo "note: Ollama is not answering on :11434 or has no qwen3:8b; triage will report the model unavailable" >&2
fi
echo "next: demo/seed.sh"
