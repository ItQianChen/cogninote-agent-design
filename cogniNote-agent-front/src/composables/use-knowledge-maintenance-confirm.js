import { h } from 'vue'
import { ElMessageBox } from 'element-plus'

/**
 * 维护类操作统一二次确认，避免不同入口对同一任务给出不一致风险提示。
 */
async function confirmMaintenanceAction({
  title,
  summary,
  impacts = [],
  path = '',
  confirmButtonText = '确认',
  type = 'warning',
  confirmButtonClass = ''
}) {
  try {
    await ElMessageBox.confirm(confirmMessage({ summary, impacts, path }), title, {
      confirmButtonText,
      cancelButtonText: '取消',
      type,
      confirmButtonClass,
      customClass: 'knowledge-maintenance-confirm-box'
    })
    return true
  } catch (err) {
    if (err === 'cancel' || err === 'close') {
      return false
    }
    throw err
  }
}

function confirmMessage({ summary, impacts, path }) {
  return h('div', { class: 'knowledge-maintenance-confirm' }, [
    h('p', { class: 'knowledge-maintenance-confirm__summary' }, summary),
    impacts.length
      ? h('section', { class: 'knowledge-maintenance-confirm__section' }, [
          h('span', { class: 'knowledge-maintenance-confirm__label' }, '影响'),
          h('ul', impacts.map((impact) => h('li', impact)))
        ])
      : null,
    path
      ? h('section', { class: 'knowledge-maintenance-confirm__section' }, [
          h('span', { class: 'knowledge-maintenance-confirm__label' }, '目录路径'),
          h('code', { class: 'knowledge-maintenance-confirm__path' }, path)
        ])
      : null
  ])
}

function folderName(folder) {
  return folder?.displayName || folder?.folderPath || folder?.id || '该目录'
}

export function confirmRebuildAllIndex() {
  return confirmMaintenanceAction({
    title: '重建全部索引',
    summary: '将基于当前已解析文档重新写入全部 Lucene 索引。',
    impacts: [
      '文件较多时可能运行较久。',
      '任务会加入维护队列。',
      '运行中任务不能取消。'
    ],
    confirmButtonText: '确认重建'
  })
}

export function confirmSyncFolder(folder) {
  return confirmMaintenanceAction({
    title: '同步目录',
    summary: `将扫描“${folderName(folder)}”的本地文件变化。`,
    impacts: [
      '会收敛新增、修改、删除和缺失索引状态。',
      '不会删除本地原始文件。',
      '任务会加入维护队列。'
    ],
    path: folder?.folderPath || '',
    confirmButtonText: '确认同步'
  })
}

export function confirmRebuildFolderIndex(folder) {
  return confirmMaintenanceAction({
    title: '重建目录索引',
    summary: `将重新扫描“${folderName(folder)}”，并重建该目录 Lucene 索引。`,
    impacts: [
      '会基于该目录当前解析结果重写索引。',
      '文件较多时可能运行较久。',
      '任务会加入维护队列。'
    ],
    path: folder?.folderPath || '',
    confirmButtonText: '确认重建'
  })
}

export function confirmReparseFolder(folder) {
  return confirmMaintenanceAction({
    title: '重新解析目录',
    summary: `将重新读取并解析“${folderName(folder)}”中的所有支持文件。`,
    impacts: [
      '会忽略未变化文件的跳过逻辑。',
      '启用公共 OCR 后，无文本层 PDF 可能按页上传到 OCR Provider。',
      '任务会加入维护队列。'
    ],
    path: folder?.folderPath || '',
    confirmButtonText: '确认重新解析'
  })
}

export function confirmDisableFolder(folder) {
  return confirmMaintenanceAction({
    title: '停用目录',
    summary: `停用“${folderName(folder)}”后，该目录不会再参与搜索和 RAG 引用。`,
    impacts: [
      '会清理该目录的检索索引命中。',
      '应用内文档记录会保留。',
      '本地原始文件会保留。'
    ],
    path: folder?.folderPath || '',
    confirmButtonText: '确认停用',
    confirmButtonClass: 'el-button--danger'
  })
}

export function confirmDeleteKnowledgeFolder(folder) {
  return confirmMaintenanceAction({
    title: '删除知识库目录',
    summary: `将删除“${folderName(folder)}”这个知识库目录记录。`,
    impacts: [
      '会删除应用内目录、文档和索引数据。',
      '会删除该目录的图谱派生数据和维护记录。',
      '不会删除本地原始文件。'
    ],
    path: folder?.folderPath || '',
    confirmButtonText: '删除',
    confirmButtonClass: 'el-button--danger'
  })
}
