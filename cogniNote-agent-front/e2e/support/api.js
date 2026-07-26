export async function apiData(response) {
  if (!response.ok()) {
    throw new Error(`API request failed: ${response.status()} ${await response.text()}`)
  }
  const body = await response.json()
  if (body?.success === false) {
    throw new Error(body.message || body.code || 'API request failed')
  }
  return body?.data
}

export async function waitForMaintenanceRun(request, runId, timeoutMs = 45_000) {
  const terminalStatuses = new Set(['CANCELLED', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED'])
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const run = await apiData(await request.get(`/api/knowledge-maintenance/runs/${runId}`))
    if (terminalStatuses.has(run.status)) {
      return run
    }
    await new Promise((resolve) => setTimeout(resolve, 200))
  }
  throw new Error(`Maintenance run ${runId} did not reach a terminal state`)
}
