import { describe, expect, it } from 'vitest'

import {
  DEFAULT_SETTINGS_ITEM,
  SETTINGS_ITEM_IDS,
  normalizeSettingsItem
} from './settings-navigation'

describe('settings navigation normalization', () => {
  it('keeps current ids and resolves legacy aliases', () => {
    expect(normalizeSettingsItem('model-chat')).toBe('model-chat')
    expect(normalizeSettingsItem('data-protection')).toBe('data-protection')
    expect(normalizeSettingsItem('system-theme')).toBe('appearance')
    expect(normalizeSettingsItem('knowledge-query-contextualizer')).toBe('chat-retrieval')
  })

  it('falls back for empty and unknown ids', () => {
    expect(normalizeSettingsItem('unknown')).toBe(DEFAULT_SETTINGS_ITEM)
    expect(normalizeSettingsItem()).toBe(DEFAULT_SETTINGS_ITEM)
    expect(new Set(SETTINGS_ITEM_IDS).size).toBe(SETTINGS_ITEM_IDS.length)
  })
})
