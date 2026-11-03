# One triage against the running app (after demo/seed), printed short: each source's status,
# the model outcome and one line per diagnosis. The full answer is in the JSON; this is the glance.
$ErrorActionPreference = 'Stop'

$app = if ($env:APP) { $env:APP } else { 'http://localhost:8085' }
$result = Invoke-RestMethod -Method Post -Uri "$app/api/triage" -TimeoutSec 600

foreach ($section in $result.report.sources) {
  Write-Host ('{0} ({1}): {2}, {3} groups' -f $section.source, $section.id, $section.status, @($section.groups).Count)
}
$model = $result.model
Write-Host ('model {0}: usable={1}, attempts={2}, {3} ms {4}' -f $model.model, $model.usable, $model.attempts,
  $model.durationMs, $model.problem)
foreach ($diagnosis in @($result.answer.diagnoses)) {
  if ($diagnosis) {
    Write-Host ('{0} [{1}] {2}' -f $diagnosis.groupId, $diagnosis.confidence, $diagnosis.probableCause)
  }
}
