import { jsonOptions, requestJson } from './http-client'

export function getActiveModelConfigs() {
  return requestJson('/api/model-configs/active')
}

export function listModelConfigs(role) {
  return requestJson(`/api/model-configs?role=${encodeURIComponent(role)}`)
}

export function createModelConfig(payload) {
  return requestJson('/api/model-configs', jsonOptions('POST', payload))
}

export function updateModelConfig(id, payload) {
  return requestJson(`/api/model-configs/${encodeURIComponent(id)}`, jsonOptions('PUT', payload))
}

export function deleteModelConfig(id) {
  return requestJson(`/api/model-configs/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function activateModelConfig(id) {
  return requestJson(`/api/model-configs/${encodeURIComponent(id)}/activate`, jsonOptions('POST', {}))
}

export function testModelConfig(payload) {
  return requestJson('/api/model-configs/test', jsonOptions('POST', payload))
}

export function fetchModelOptions(payload) {
  return requestJson('/api/model-configs/models', jsonOptions('POST', payload))
}
