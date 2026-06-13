# 第 26 阶段计划：知识图谱探索器 UI/UX 重设计

## Summary

第 26 阶段在第 25 阶段已落地的知识图谱事实层之上，重做前端探索体验：思维导图从缩进列表升级为文档结构图，关系图从固定圆环升级为可筛选、可聚焦、可回证据的图谱探索器，并提供全屏弹窗承载完整画布。

本阶段不改变 `knowledge_graph_*` SQLite 事实表，不新增 API endpoint，不扩大 `GRAPH_NODE_LIMIT=100`。后端只对已有 `MINDMAP` / `GRAPH` 视图 payload 做向后兼容扩展；旧缓存仍可显示，重新生成图谱后获得增强 payload。

核心目标是解决第 25 阶段展示层的三个真实问题：

- 思维导图只是 Markdown 标题缩进列表，缺少图形化结构。
- 关系图使用固定圆环布局，关系方向、关系类型和证据强弱不清楚。
- 普通工作台空间有限，没有能完整探索图谱的大画布、筛选和 Inspector。

## Goals

- `MINDMAP` 保留 `markdown` 字段，同时新增 `documents -> headings -> entities` 结构化数据，用于渲染范围、文档、标题、实体四层结构图。
- `GRAPH` 保留 `nodes` / `edges`，新增节点类型统计、关系类型统计、隐藏节点数和边两端展示名，减少前端重复推导。
- 前端显式依赖 `cytoscape` 和 `cytoscape-fcose`，不依赖 mermaid 的传递依赖。
- 普通视图展示可读预览，全屏弹窗提供筛选栏、大画布和右侧 Inspector。
- 列表视图继续作为关系图的可访问性替代，并增强搜索、筛选和排序能力。
- 所有节点和边交互继续复用现有证据抽屉，保证“看到关系 -> 查看证据 -> 回到 chunk”闭环。

## Non-goals

- 不引入 Neo4j、NebulaGraph 等图数据库。
- 不新增图谱查询 endpoint，不改变图谱生成和 SSE run 流程。
- 不做社区发现、PageRank、LLM 实体消歧或 GraphRAG 问答。
- 不把图谱渲染做成营销式页面；本阶段仍是桌面知识管理工具界面。

## Payload 兼容策略

`MINDMAP` 旧 payload：

```json
{
  "viewType": "MINDMAP",
  "markdown": "# 项目资料\n\n## README.md\n\n### 架构\n#### CogniNote [PRODUCT] x2\n"
}
```

第 26 阶段新增结构化字段：

```json
{
  "viewType": "MINDMAP",
  "markdown": "# 项目资料\n\n## README.md\n\n### 架构\n#### CogniNote [PRODUCT] x2\n",
  "root": {"id": "scope", "label": "项目资料", "type": "SCOPE"},
  "documents": [
    {
      "id": "doc-xxx",
      "label": "README.md",
      "fileName": "README.md",
      "headings": [
        {
          "id": "doc-xxx::heading::架构",
          "label": "架构",
          "entities": [
            {
              "id": "node-xxx",
              "label": "CogniNote",
              "type": "PRODUCT",
              "count": 2
            }
          ]
        }
      ]
    }
  ]
}
```

旧缓存没有 `documents` 时，前端继续用 `markdown` 解析生成兼容结构，避免用户必须立刻重建图谱。

`GRAPH` 旧 payload 保持可用，第 26 阶段新增：

- `nodeTypeCounts`：节点类型计数，用于图例和筛选。
- `relationTypeCounts`：关系类型计数，用于图例和筛选。
- `hiddenNodeCount`：`totalNodeCount - nodes.length`，提醒用户当前视图是 Top N 裁剪结果。
- `sourceLabel` / `targetLabel`：边两端展示名，表格和 Inspector 不再反复查 node map。

## Frontend UX

知识图谱面板保持工作台式布局：

```text
顶部：范围选择 / 文档选择 / 视图切换 / 全屏 / 生成 / 取消
主区：当前视图预览
全屏弹窗：左侧筛选栏 / 中间 Cytoscape 画布 / 右侧 Inspector
证据抽屉：复用 GraphEvidenceDrawer
```

思维导图视图使用 Cytoscape `breadthfirst` 布局：

- 范围节点为根。
- 文档节点作为一级分支。
- heading 节点作为二级分支。
- 实体节点作为叶子，展示类型和提及次数。
- 点击实体节点打开节点证据；点击文档或 heading 只更新 Inspector。

关系图视图使用 Cytoscape `fcose` 布局：

- 节点大小映射 `mentionCount` 和 `degree`。
- 节点颜色映射 `type`。
- 边宽映射 `weight`。
- 边展示箭头和关系标签。
- 选中节点时高亮一跳邻居和关联边，降低非相关元素透明度。

全屏探索器提供：

- 搜索实体。
- 节点类型筛选。
- 关系类型筛选。
- 最小证据数筛选。
- 重排布局。
- 适配视图。
- 重置筛选。

## UI Rules

- 使用现有青绿色主色、surface、border、focus token，保持桌面工具气质。
- 不使用装饰渐变、悬浮大卡片或营销式布局。
- 图例必须解释节点颜色、边宽、箭头方向和证据数含义。
- 图标按钮使用 lucide，并提供 `aria-label` 和 `title`。
- 图谱不是唯一信息来源，列表视图必须始终可用。

## Test Plan

- `mvn test`
- `npm --prefix cogniNote-agent-front run build`
- 验证旧 `MINDMAP` 只有 `markdown` 时仍可显示。
- 验证新 `MINDMAP` 结构化字段能生成四层 Cytoscape 导图。
- 验证 `GRAPH` 统计字段、边展示名和隐藏节点数正确。
- 验证普通视图、全屏弹窗、搜索、筛选、重排、适配视图、Inspector 和证据抽屉可用。
- 验证空图谱、单文档少节点、接近 100 节点和搜索无结果状态。

## 资料依据

- yFiles 知识图谱可视化指南：有效知识图谱可视化应回答具体问题，根据数据结构选择层级、organic、radial 等布局，并用颜色、大小、边宽、标签和图例编码语义。
- Cambridge Intelligence graph UX 资料：糟糕图谱常见问题是 hairball、snowstorm 和 starburst；应通过筛选、分组、渐进披露、可访问颜色、可读标签、键盘导航和详情面板降低理解成本。
- UI/UX 设计规则：图谱类数据可视化不能只依赖颜色传达含义，必须提供图例、tooltip/Inspector 和列表 fallback。
