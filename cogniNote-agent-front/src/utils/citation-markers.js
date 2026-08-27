export const CITATION_PREVIEW_LENGTH = 120

export function compactCitationPreview(content, maxLength = CITATION_PREVIEW_LENGTH) {
  const text = String(content || '').replace(/\s+/g, ' ').trim()
  if (!text) {
    return ''
  }
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}...`
}

/**
 * 将回答正文中的 [n] 转为可点击 Markdown 链接。
 * 只处理当前消息实际拥有的来源，并跳过 fenced code，避免改写代码示例中的普通文本。
 */
export function decorateCitationMarkers(content, sources) {
  const sourceByIndex = new Map(
    (Array.isArray(sources) ? sources : [])
      .map((source, index) => [String(source?.index ?? index + 1), source])
  )
  if (!sourceByIndex.size) {
    return String(content || '')
  }

  return String(content || '')
    .split(/(```[\s\S]*?```)/g)
    .map((part, index) => {
      if (index % 2 === 1) {
        return part
      }
      return part.replace(/(?<!\\)\[(\d+)\](?!\()/g, (match, sourceIndex) => {
        const source = sourceByIndex.get(sourceIndex)
        if (!source) {
          return match
        }
        const preview = compactCitationPreview(source.preview || source.content)
          .replaceAll('\\', '\\\\')
          .replaceAll('"', '&quot;')
        const title = preview ? ` "${preview}"` : ''
        return `[${sourceIndex}](#citation-${sourceIndex}${title})`
      })
    })
    .join('')
}

export function citationIndexFromHref(href) {
  const match = String(href || '').match(/^#citation-(\d+)$/)
  return match ? match[1] : ''
}
