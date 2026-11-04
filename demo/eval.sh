#!/usr/bin/env bash
# Runs the eval set (src/test/resources/eval) against the live model and prints the table.
# Needs only Ollama with the model pulled: no demo stack, no app. Every answer is in
# target/eval/live-results.md. OLLAMA_BASE_URL and TRIAGE_MODEL as for the app; extra
# arguments go to Maven, e.g. -Deval.parallel=1.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"

cd "$root"
./mvnw -q test -Dtest=LiveEvalTest -Deval.live=true "$@"
sed '/^### /,$d' target/eval/live-results.md
