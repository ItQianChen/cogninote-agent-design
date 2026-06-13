#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import assert from 'node:assert/strict'

const tempDir = mkdtempSync(join(tmpdir(), 'cogninote-updater-manifest-'))

try {
  const manifestPath = join(tempDir, 'latest.json')
  const windowsSigPath = join(tempDir, 'windows.sig')
  const macosSigPath = join(tempDir, 'macos.sig')
  const nextSigPath = join(tempDir, 'next.sig')

  writeFileSync(windowsSigPath, 'windows-signature\n', 'utf8')
  writeFileSync(macosSigPath, 'macos-signature\n', 'utf8')
  writeFileSync(nextSigPath, 'next-signature\n', 'utf8')

  runBuilder(manifestPath, '0.1.34', 'windows-x86_64', 'https://example.com/windows.exe', windowsSigPath)
  runBuilder(manifestPath, '0.1.34', 'darwin-aarch64', 'https://example.com/macos.app.tar.gz', macosSigPath)

  const merged = readManifest(manifestPath)
  assert.equal(merged.version, '0.1.34')
  assert.equal(merged.platforms['windows-x86_64'].signature, 'windows-signature')
  assert.equal(merged.platforms['darwin-aarch64'].signature, 'macos-signature')
  assert.equal(merged.platforms['darwin-aarch64'].url, 'https://example.com/macos.app.tar.gz')

  runBuilder(manifestPath, '0.1.35', 'windows-x86_64', 'https://example.com/next.exe', nextSigPath)

  const nextVersion = readManifest(manifestPath)
  assert.equal(nextVersion.version, '0.1.35')
  assert.deepEqual(Object.keys(nextVersion.platforms), ['windows-x86_64'])
  assert.equal(nextVersion.platforms['windows-x86_64'].signature, 'next-signature')
} finally {
  rmSync(tempDir, { recursive: true, force: true })
}

function runBuilder(manifestPath, version, platform, url, signatureFile) {
  execFileSync(
    process.execPath,
    [
      'scripts/build-updater-manifest.mjs',
      '--manifest',
      manifestPath,
      '--version',
      version,
      '--platform',
      platform,
      '--url',
      url,
      '--signatureFile',
      signatureFile,
      '--notes',
      `CogniNote ${version}`
    ],
    { stdio: 'inherit' }
  )
}

function readManifest(manifestPath) {
  return JSON.parse(readFileSync(manifestPath, 'utf8'))
}
