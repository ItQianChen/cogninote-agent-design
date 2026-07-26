import { createServer } from 'node:http'
import { createWriteStream, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { once } from 'node:events'
import { spawn } from 'node:child_process'

const backendPort = requiredNumber('COGNINOTE_E2E_BACKEND_PORT')
const controlPort = requiredNumber('COGNINOTE_E2E_CONTROL_PORT')
const controlToken = required('COGNINOTE_E2E_CONTROL_TOKEN')
const storageRoot = required('COGNINOTE_E2E_STORAGE_ROOT')
const backendJar = required('COGNINOTE_E2E_BACKEND_JAR')
const artifactRoot = required('COGNINOTE_E2E_ARTIFACT_ROOT')
const backendUrl = `http://127.0.0.1:${backendPort}`

let backendProcess

export default async function globalSetup() {
  mkdirSync(artifactRoot, { recursive: true })
  let controlServer
  try {
    await startBackend()
    controlServer = createControlServer()
    controlServer.listen(controlPort, '127.0.0.1')
    await once(controlServer, 'listening')
  } catch (error) {
    controlServer?.close()
    await stopBackend()
    throw error
  }

  return async () => {
    await closeServer(controlServer)
    await stopBackend()
  }
}

function createControlServer() {
  return createServer(async (request, response) => {
    if (request.method !== 'POST'
        || request.url !== '/restart'
        || request.headers.authorization !== `Bearer ${controlToken}`) {
      response.writeHead(404).end()
      return
    }
    try {
      await stopBackend()
      await startBackend()
      response.writeHead(204).end()
    } catch (error) {
      response.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' })
      response.end(error.stack || error.message)
    }
  })
}

async function closeServer(server) {
  if (!server.listening) {
    return
  }
  await new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve())
  })
}

async function startBackend() {
  const javaExecutable = process.platform === 'win32' ? 'java.exe' : 'java'
  const javaPath = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin', javaExecutable)
    : javaExecutable
  const stdout = createWriteStream(join(artifactRoot, 'backend.stdout.log'), { flags: 'a' })
  const stderr = createWriteStream(join(artifactRoot, 'backend.stderr.log'), { flags: 'a' })
  backendProcess = spawn(javaPath, [
    '-Dfile.encoding=UTF-8',
    '-jar',
    backendJar,
    `--server.port=${backendPort}`,
    '--server.address=127.0.0.1',
    `--app.storage.base-dir=${storageRoot}`,
    `--app.storage.database-path=${join(storageRoot, 'data', 'cogninote.db')}`,
    '--app.desktop.enabled=false',
    '--spring.main.banner-mode=off'
  ], {
    cwd: dirname(dirname(backendJar)),
    env: process.env,
    windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe']
  })
  backendProcess.stdout.pipe(stdout)
  backendProcess.stderr.pipe(stderr)
  backendProcess.once('exit', () => {
    stdout.end()
    stderr.end()
  })
  await waitForBackend()
}

async function stopBackend() {
  const processToStop = backendProcess
  backendProcess = null
  if (!processToStop || processToStop.exitCode !== null) {
    return
  }
  const exited = once(processToStop, 'exit')
  processToStop.kill('SIGTERM')
  const stopped = await Promise.race([
    exited.then(() => true),
    new Promise((resolve) => setTimeout(() => resolve(false), 10_000))
  ])
  if (!stopped && processToStop.exitCode === null) {
    processToStop.kill('SIGKILL')
    await once(processToStop, 'exit')
  }
}

async function waitForBackend() {
  const deadline = Date.now() + 90_000
  while (Date.now() < deadline) {
    if (backendProcess?.exitCode !== null) {
      throw new Error(`E2E backend exited before becoming ready with code ${backendProcess?.exitCode}`)
    }
    try {
      const response = await fetch(`${backendUrl}/api/system/status`)
      if (response.ok) {
        return
      }
    } catch {
      // Startup races are expected until Spring binds the random test port.
    }
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  throw new Error(`E2E backend did not become ready at ${backendUrl}`)
}

function required(name) {
  const value = process.env[name]
  if (!value) {
    throw new Error(`Missing required E2E environment variable: ${name}`)
  }
  return value
}

function requiredNumber(name) {
  const value = Number(required(name))
  if (!Number.isInteger(value) || value <= 0) {
    throw new Error(`Invalid E2E port in ${name}`)
  }
  return value
}
