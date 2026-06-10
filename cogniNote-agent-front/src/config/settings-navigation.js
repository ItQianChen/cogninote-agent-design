export const SETTINGS_ITEM_ALIASES = {
  'system-theme': 'appearance',
  'knowledge-query-contextualizer': 'chat-retrieval'
}

export const SETTINGS_NAV_GROUPS = [
  {
    id: 'system',
    label: '系统',
    items: [
      { id: 'appearance', label: '外观' },
      { id: 'system-info', label: '系统信息' }
    ]
  },
  {
    id: 'model',
    label: '模型',
    items: [
      { id: 'model-chat', label: '对话模型' },
      { id: 'model-embedding', label: '向量模型' }
    ]
  },
  {
    id: 'policy',
    label: '策略',
    items: [
      { id: 'chat-retrieval', label: '聊天与检索' }
    ]
  }
]

export const DEFAULT_SETTINGS_ITEM = 'appearance'

export const SETTINGS_ITEM_IDS = SETTINGS_NAV_GROUPS.flatMap((group) =>
  group.items.map((item) => item.id)
)

export function normalizeSettingsItem(itemId) {
  const normalized = SETTINGS_ITEM_ALIASES[itemId] || itemId
  return SETTINGS_ITEM_IDS.includes(normalized) ? normalized : DEFAULT_SETTINGS_ITEM
}
