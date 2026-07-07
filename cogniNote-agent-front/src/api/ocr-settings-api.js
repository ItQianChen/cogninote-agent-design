import { jsonOptions, requestJson } from './http-client'

export function getOcrSettings() {
  return requestJson('/api/ocr/settings')
}

export function updateOcrSettings(payload) {
  return requestJson('/api/ocr/settings', jsonOptions('PUT', payload))
}

export function testOcrSettings() {
  return requestJson('/api/ocr/test', jsonOptions('POST', {}))
}
