# Starts the two demo stacks from their own repos, unmodified:
#   notification-platform: api :8080, worker :8081 (plus its Kafka, Redis, MySQL, Mailpit)
#   message-inspector:     app :8082, Artemis :61617, console and Jolokia :8162
# The sibling repos are found next to this one; set NP_DIR or MI_DIR to look elsewhere.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$npDir = if ($env:NP_DIR) { $env:NP_DIR } else { Join-Path $root '..\notification-platform' }
$miDir = if ($env:MI_DIR) { $env:MI_DIR } else { Join-Path $root '..\message-inspector' }
$ports = Join-Path $PSScriptRoot 'message-inspector.ports.yaml'

foreach ($dir in $npDir, $miDir) {
  if (-not (Test-Path (Join-Path $dir 'compose.yaml'))) {
    throw "no compose.yaml in $dir (set NP_DIR / MI_DIR)"
  }
}

# docker compose reports progress on stderr. Under 'Stop', Windows PowerShell 5.1 turns each
# such line into a terminating error whenever stderr is redirected, so it runs under
# 'Continue' (local to this function) and is judged by its exit code.
function Invoke-Compose([string[]] $arguments, [string] $failure) {
  $ErrorActionPreference = 'Continue'
  & docker compose @arguments
  if ($LASTEXITCODE -ne 0) { throw $failure }
}
Invoke-Compose @('-f', (Join-Path $npDir 'compose.yaml'), 'up', '-d', '--build', 'api', 'worker') `
  'notification-platform did not start'
Invoke-Compose @('-p', 'mi-demo', '-f', (Join-Path $miDir 'compose.yaml'), '-f', $ports, 'up', '-d', '--build') `
  'message-inspector did not start'

# Spring Boot opens its port only once the context is up, so any HTTP answer means ready.
function Wait-For([string] $name, [string] $url) {
  for ($i = 0; $i -lt 90; $i++) {
    try {
      Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5 | Out-Null
      Write-Host "$name is up on $url"
      return
    } catch {
      if ($_.Exception.Response) {
        Write-Host "$name is up on $url"
        return
      }
    }
    Start-Sleep -Seconds 2
  }
  throw "$name did not answer on $url within 3 minutes"
}
Wait-For 'notification api' 'http://localhost:8080/actuator/health'
Wait-For 'notification worker' 'http://localhost:8081/actuator/health'
Wait-For 'message-inspector' 'http://localhost:8082/'
Wait-For 'Artemis Jolokia' 'http://localhost:8162/console/jolokia'

$hasModel = $false
try {
  $tags = Invoke-RestMethod -Uri 'http://localhost:11434/api/tags' -TimeoutSec 5
  $hasModel = @($tags.models | ForEach-Object { $_.name }) -contains 'qwen3:8b'
} catch {
  $hasModel = $false
}
if (-not $hasModel) {
  Write-Warning 'Ollama is not answering on :11434 or has no qwen3:8b; triage will report the model unavailable'
}
Write-Host 'next: demo\seed.ps1'
