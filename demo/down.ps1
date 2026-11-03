# Stops and removes both demo stacks.
#   notification-platform: volumes kept. Its MySQL (np_mysql_data) is the one its own
#     compose uses, so its notifications, and the next triage's NP counts, carry over.
#   message-inspector: removed with its volumes (-v, the mi-demo project only), so the next
#     demo broker starts empty.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$npDir = if ($env:NP_DIR) { $env:NP_DIR } else { Join-Path $root '..\notification-platform' }
$miDir = if ($env:MI_DIR) { $env:MI_DIR } else { Join-Path $root '..\message-inspector' }
$ports = Join-Path $PSScriptRoot 'message-inspector.ports.yaml'

# docker compose reports progress on stderr; see demo\up.ps1 for why it runs under 'Continue'.
function Invoke-Compose([string[]] $arguments, [string] $failure) {
  $ErrorActionPreference = 'Continue'
  & docker compose @arguments
  if ($LASTEXITCODE -ne 0) { throw $failure }
}
Invoke-Compose @('-p', 'mi-demo', '-f', (Join-Path $miDir 'compose.yaml'), '-f', $ports, 'down', '-v') `
  'message-inspector did not stop'
Invoke-Compose @('-f', (Join-Path $npDir 'compose.yaml'), 'down') 'notification-platform did not stop'
