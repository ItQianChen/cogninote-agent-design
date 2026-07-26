import { appendFileSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const budgetPath = join(repositoryRoot, 'artifacts', 'budgets', 'desktop-package-budget.json')
const budget = JSON.parse(readFileSync(budgetPath, 'utf8'))
const metrics = parseMetrics(process.argv.slice(2))
const results = []
let failed = false

for (const [name, path] of metrics) {
  const currentBytes = pathSize(resolve(repositoryRoot, path))
  const baselineBytes = budget.metrics[name]
  if (!Number.isFinite(baselineBytes) || baselineBytes <= 0) {
    results.push({ name, baselineBytes: null, currentBytes, changePercent: null, status: 'BASELINE_REQUIRED' })
    console.warn(`Desktop budget baseline required for ${name}: ${currentBytes} bytes`)
    continue
  }
  const changePercent = ((currentBytes - baselineBytes) / baselineBytes) * 100
  const absoluteGrowthBytes = currentBytes - baselineBytes
  const exceedsFailure = changePercent > budget.failureGrowthPercent
    && absoluteGrowthBytes > budget.failureAbsoluteGrowthBytes
  const status = exceedsFailure ? 'FAIL' : changePercent > budget.warningGrowthPercent ? 'WARN' : 'PASS'
  failed ||= exceedsFailure
  results.push({ name, baselineBytes, currentBytes, changePercent, status })
}

const table = renderTable(results)
console.log(table)
if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Desktop package budget\n\n${table}\n\n`, 'utf8')
}
if (failed) {
  process.exitCode = 1
}

function parseMetrics(args) {
  const entries = []
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] !== '--metric' || !args[index + 1]?.includes('=')) {
      throw new Error('Use --metric name=path for every desktop artifact')
    }
    const separator = args[index + 1].indexOf('=')
    entries.push([args[index + 1].slice(0, separator), args[index + 1].slice(separator + 1)])
    index += 1
  }
  if (!entries.length) {
    throw new Error('At least one desktop metric is required')
  }
  return entries
}

function pathSize(path) {
  const stats = statSync(path)
  if (stats.isFile()) {
    return stats.size
  }
  return readdirSync(path).reduce((total, name) => total + pathSize(join(path, name)), 0)
}

function renderTable(items) {
  const rows = items.map((item) => [
    item.name,
    item.baselineBytes == null ? 'pending' : formatMiB(item.baselineBytes),
    formatMiB(item.currentBytes),
    item.changePercent == null ? '-' : `${item.changePercent.toFixed(2)}%`,
    item.status
  ])
  return [
    '| Artifact | Baseline | Current | Change | Status |',
    '| --- | ---: | ---: | ---: | --- |',
    ...rows.map((row) => `| ${row.join(' | ')} |`)
  ].join('\n')
}

function formatMiB(bytes) {
  return `${(bytes / 1024 / 1024).toFixed(2)} MiB`
}
