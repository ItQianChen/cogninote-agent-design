import { appendFileSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const frontendRoot = join(repositoryRoot, 'cogniNote-agent-front')
const distRoot = join(frontendRoot, 'dist')
const baselinePath = join(frontendRoot, 'bundle-budget.json')
const writeBaseline = process.argv.includes('--write-baseline')
const packageJson = JSON.parse(readFileSync(join(frontendRoot, 'package.json'), 'utf8'))

const thresholds = {
  warningGrowthPercent: 2,
  failureGrowthPercent: 5,
  entryGzipHardLimitBytes: 490 * 1024,
  maxRawChunkHardLimitBytes: Math.floor(1.55 * 1024 * 1024)
}
const metrics = collectMetrics()

if (writeBaseline) {
  const baseline = {
    schemaVersion: 1,
    projectVersion: packageJson.version,
    generatedAt: new Date().toISOString(),
    nodeVersion: process.version,
    thresholds,
    metrics
  }
  writeFileSync(baselinePath, `${JSON.stringify(baseline, null, 2)}\n`, 'utf8')
  console.log(`Wrote frontend bundle baseline: ${baselinePath}`)
  process.exit(0)
}

const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'))
const effectiveThresholds = { ...thresholds, ...baseline.thresholds }
const comparisons = compareMetrics(baseline.metrics, metrics)
const warnings = comparisons.filter((item) => item.growthPercent > effectiveThresholds.warningGrowthPercent)
const failures = comparisons.filter((item) => item.growthPercent > effectiveThresholds.failureGrowthPercent)

if (metrics.entryJs.gzipBytes > effectiveThresholds.entryGzipHardLimitBytes) {
  failures.push({
    label: 'Entry JS gzip hard limit',
    currentBytes: metrics.entryJs.gzipBytes,
    baselineBytes: effectiveThresholds.entryGzipHardLimitBytes,
    growthPercent: percentChange(effectiveThresholds.entryGzipHardLimitBytes, metrics.entryJs.gzipBytes)
  })
}
if (metrics.maxJsChunk.rawBytes > effectiveThresholds.maxRawChunkHardLimitBytes) {
  failures.push({
    label: 'Max JS raw hard limit',
    currentBytes: metrics.maxJsChunk.rawBytes,
    baselineBytes: effectiveThresholds.maxRawChunkHardLimitBytes,
    growthPercent: percentChange(effectiveThresholds.maxRawChunkHardLimitBytes, metrics.maxJsChunk.rawBytes)
  })
}

const table = renderTable(comparisons)
console.log(table)
if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Frontend bundle budget\n\n${table}\n\n`, 'utf8')
}
for (const warning of warnings) {
  console.warn(`Bundle warning: ${warning.label} grew ${warning.growthPercent.toFixed(2)}%`)
}
if (failures.length) {
  for (const failure of failures) {
    console.error(`Bundle budget exceeded: ${failure.label} (${formatBytes(failure.currentBytes)})`)
  }
  process.exitCode = 1
}

function collectMetrics() {
  const manifest = JSON.parse(readFileSync(join(distRoot, '.vite', 'manifest.json'), 'utf8'))
  const entry = Object.values(manifest).find((item) => item.isEntry)
  if (!entry?.file) {
    throw new Error('Vite manifest does not contain an entry JavaScript asset')
  }
  const assets = listFiles(join(distRoot, 'assets'))
  const jsAssets = assets.filter((file) => file.endsWith('.js')).map(measureFile)
  const cssAssets = assets.filter((file) => file.endsWith('.css')).map(measureFile)
  const entryJs = measureFile(join(distRoot, entry.file))
  const maxJsChunk = [...jsAssets].sort((left, right) => right.rawBytes - left.rawBytes)[0]
  return {
    entryJs,
    maxJsChunk,
    totalJsGzipBytes: jsAssets.reduce((total, asset) => total + asset.gzipBytes, 0),
    totalCssGzipBytes: cssAssets.reduce((total, asset) => total + asset.gzipBytes, 0),
    jsChunkCount: jsAssets.length,
    cssChunkCount: cssAssets.length
  }
}

function measureFile(file) {
  const content = readFileSync(file)
  return {
    file: relative(distRoot, file).replaceAll('\\', '/'),
    rawBytes: content.length,
    gzipBytes: gzipSync(content, { level: 9, mtime: 0 }).length
  }
}

function listFiles(directory) {
  return readdirSync(directory).flatMap((name) => {
    const file = join(directory, name)
    return statSync(file).isDirectory() ? listFiles(file) : [file]
  })
}

function compareMetrics(baseline, current) {
  return [
    comparison('Entry JS raw', baseline.entryJs.rawBytes, current.entryJs.rawBytes),
    comparison('Entry JS gzip', baseline.entryJs.gzipBytes, current.entryJs.gzipBytes),
    comparison('Max JS chunk raw', baseline.maxJsChunk.rawBytes, current.maxJsChunk.rawBytes),
    comparison('Max JS chunk gzip', baseline.maxJsChunk.gzipBytes, current.maxJsChunk.gzipBytes),
    comparison('Total JS gzip', baseline.totalJsGzipBytes, current.totalJsGzipBytes),
    comparison('Total CSS gzip', baseline.totalCssGzipBytes, current.totalCssGzipBytes)
  ]
}

function comparison(label, baselineBytes, currentBytes) {
  return { label, baselineBytes, currentBytes, growthPercent: percentChange(baselineBytes, currentBytes) }
}

function percentChange(baselineBytes, currentBytes) {
  return baselineBytes === 0 ? (currentBytes === 0 ? 0 : 100) : ((currentBytes - baselineBytes) / baselineBytes) * 100
}

function renderTable(items) {
  const rows = items.map((item) =>
    `| ${item.label} | ${formatBytes(item.baselineBytes)} | ${formatBytes(item.currentBytes)} | ${item.growthPercent.toFixed(2)}% |`)
  return ['| Metric | Baseline | Current | Change |', '| --- | ---: | ---: | ---: |', ...rows].join('\n')
}

function formatBytes(bytes) {
  return `${(bytes / 1024).toFixed(2)} KiB`
}
