# Runs the eval set (src/test/resources/eval) against the live model and prints the table.
# Needs only Ollama with the model pulled: no demo stack, no app. Every answer is in
# target/eval/live-results.md. OLLAMA_BASE_URL and TRIAGE_MODEL as for the app; extra
# arguments go to Maven, e.g. '-Deval.parallel=1' (quoted: PowerShell splits -Da.b=c at the dot).
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
  & .\mvnw.cmd -q test '-Dtest=LiveEvalTest' '-Deval.live=true' @args
  if ($LASTEXITCODE -ne 0) { throw "mvnw exited with $LASTEXITCODE" }
  foreach ($line in Get-Content (Join-Path $root 'target\eval\live-results.md')) {
    if ($line.StartsWith('### ')) { break }
    Write-Host $line
  }
} finally {
  Pop-Location
}
