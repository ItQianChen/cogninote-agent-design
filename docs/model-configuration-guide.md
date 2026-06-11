# 模型配置指南

CogniNote 第八阶段开始使用多模型配置中心。对话模型和 Embedding 模型独立维护、独立激活：RAG 回答使用 active `CHAT` 配置，向量索引和向量检索使用 active `EMBEDDING` 配置。

配置保存在本机 SQLite 的 `model_configs` 表中，应用启动后无需重启即可读取最新 active 配置。旧的单行 `model_config` 会在启动时自动拆成 Chat 和 Embedding 两条配置。

第十一阶段已把这些 active 配置统一交给 AI Runtime 消费：DashScope Runtime 封装 Spring AI Alibaba，OpenAI-compatible Runtime 使用 Spring AI OpenAI 官方模型实现，并保留用户自定义 Base URL。原自研 OpenAI-compatible HTTP client 已删除，后续不再维护两套模型调用路径。

第 21 阶段后，active `CHAT` 配置还会被知识库模式下的 `QueryContextualizerAgent` 复用。这个内部 Agent 只用独立 JSON Prompt 判断省略式追问是否需要补全检索 query，不回答用户问题，也不新增前端模型角色。也就是说，用户仍只需要维护“对话模型”和“Embedding 模型”两类配置；但对话模型的指令遵循能力会影响追问补全质量。

第 22 阶段后，active `CHAT` 配置还会提供聊天上下文窗口 `contextWindowTokens`。默认值为 `128000`，前端显示为 `128K`；该字段只用于本地会话历史裁剪、压缩和聊天页上下文占用展示，不会作为通用参数发送给模型 API。Embedding 配置不使用上下文窗口，字段保持 `null`。

第 23 阶段后，全局“知识库追问补全策略”移动到“设置 -> 知识库 -> 知识库追问补全策略”。该设置保存在 SQLite `app_settings`，不是单个模型配置的一部分；它仍复用 active `CHAT` 模型执行内部补全 Agent，因此对话模型的稳定性会影响知识库追问检索质量。

第 25 阶段后，知识图谱抽取也复用 active `CHAT` 配置。图谱重建会按 chunk 调用该模型抽取实体、关系和证据；抽取 Prompt 统一维护在 `src/main/resources/cogninote-prompts.yaml`，不新增单独的 `GRAPH_EXTRACTION` 模型角色。因此，active Chat 模型的 JSON 遵循能力会影响图谱质量和缓存命中后的复用价值。

## Provider 类型

### DashScope

`DASHSCOPE` 用于阿里百炼默认通道。

- 配置页展示的 Base URL：`https://dashscope.aliyuncs.com/api/v1`
- Chat / Embedding 调用：使用 Spring AI Alibaba 原生 DashScope 客户端
- 模型列表：使用百炼兼容 `/models` 端点
- Embedding 语义：索引文档时传递 `textType=document`，搜索查询时传递 `textType=query`

DashScope 不允许用户自定义 host。需要自定义 URL 时，应选择 `OPENAI_COMPATIBLE`。

实现约束：DashScope SDK 示例中的 HTTP API Root 是 `https://dashscope.aliyuncs.com/api/v1`。Spring AI Alibaba 的 `DashScopeApi` 内部 path 已包含 `/api/v1/services/...`，因此后端构造客户端时会转换为裸域名 `https://dashscope.aliyuncs.com`，避免拼出重复 `/api/v1`。

第 20 阶段后，DashScope Embedding 不再和通用 Spring AI runtime 混在一起处理：`DashScopeEmbeddingRuntime` 通过 `DashScopeModelFactory.embeddingModel(config, textType)` 分别创建并缓存 `document` / `query` 两类模型实例。这个差异只存在于 DashScope 具体实现里，搜索索引层只调用 `embedDocuments` 和 `embedQuery`。

### OpenAI-compatible

`OPENAI_COMPATIBLE` 用于通用 OpenAI-compatible 服务。

用户需要填写：

- Base URL
- API Key
- 模型 ID

后端会调用：

```text
Base URL + /models
Base URL + /chat/completions
Base URL + /embeddings
```

如果用户粘贴了完整的 `/chat/completions`、`/embeddings` 或 `/models` 地址，后端会尽量规整回 Base URL。

OpenAI-compatible 的 Embedding 继续使用 Spring AI OpenAI runtime 的标准 `/embeddings` 请求。Spring AI OpenAI 的 Embedding options 只有 `model`、`encodingFormat`、`dimensions`、`user` 等标准字段，没有 DashScope 的 `text_type`；CogniNote 不会向 OpenAI-compatible 服务发送非标准 `text_type` 参数。内部仍保留 `embedDocuments` / `embedQuery` 两个入口，方便后续 provider 自己实现查询/文档语义区分。

## 配置类型

| 类型 | 用途 | 主要字段 |
| --- | --- | --- |
| `CHAT` | RAG 流式回答、连接测试、聊天上下文预算 | 模型 ID、Temperature、默认 Top K、上下文窗口 |
| `EMBEDDING` | 文档向量化、向量检索、混合检索 | 模型 ID、Embedding 维度 |

每个类型可以保存多条配置，但同一时间只有一条 active 配置。激活 Chat 配置不会覆盖 Embedding 配置，反之亦然。

## 默认值

| 类型 | 字段 | 默认值 |
| --- | --- | --- |
| Chat | Provider | `DASHSCOPE` |
| Chat | 模型 | `qwen-plus` |
| Chat | Temperature | `0.7` |
| Chat | Top K | `8` |
| Chat | 上下文窗口 | `128000`（前端显示 `128K`） |
| 知识库设置 | 追问补全策略 | `AUTO`（前端显示“自动”） |
| Embedding | Provider | `DASHSCOPE` |
| Embedding | 模型 | `text-embedding-v4` |
| Embedding | 维度 | `1024` |
| Embedding | 上下文窗口 | `null`（不适用） |

## 配置流程

1. 打开“设置”页，切换到“模型”区域。
2. 在“对话模型”或“Embedding 模型”之间切换。
3. 点击“新建配置”，填写 Provider、Base URL、API Key 和模型 ID。
4. 点击“获取模型”拉取候选模型；如果服务商模型列表不完整，可直接手动输入模型 ID。
5. 点击“测试连接”验证当前配置草稿。
6. 保存配置。
7. 在配置列表中点击“激活”让该配置成为当前类型的 active 配置。

对话模型表单中的“上下文长度”只在 `CHAT` 类型显示，可以使用预设值，也可以手动输入。保存时后端校验范围为 `1024` 到 `2000000`；超出范围会返回参数校验错误。保存成功后，聊天页会按 active Chat 配置重新计算当前会话的上下文占用。

“设置 -> 知识库 -> 知识库追问补全策略”用于控制知识库模式下是否调用内部补全 Agent：

| 选项 | 后端值 | 说明 |
| --- | --- | --- |
| 自动 | `AUTO` | 推荐。只有像省略、指代、动作型追问、英文领域切换或检索较弱时才补全检索问题。 |
| 始终 | `ALWAYS` | 每轮知识库问答都先判断是否需要补全，准确性更稳但更慢。 |
| 关闭 | `OFF` | 不补全追问，成本最低，但“继续/给个例子”等追问可能检索不准。 |

该策略是全局知识库设置，不跟随单个 Chat 配置保存。它只影响知识库检索 query，不会修改聊天记录中的用户原文，也不会影响纯模型对话。保存策略时前端调用 `PUT /api/chat/settings` 写入 SQLite，刷新页面后通过 `GET /api/chat/settings` 回显。

保存或激活 Embedding 配置后，如果模型或维度发生变化，需要在知识库中手动重建索引。系统不会自动重建旧向量，避免用户不知情地产生大量外部模型调用。第 20 阶段调整了中文 Analyzer、代码块索引文本和混合检索融合方式，升级后也需要重建 Lucene 索引；如果旧 chunks 已经丢失代码缩进，需要重新导入原始文件才能恢复代码格式。

## 前端回显规则

模型设置页使用后端 settings 快照作为页面事实来源：

- 进入“设置 -> 模型”时加载 `GET /api/model-configs/settings?role=CHAT`。
- 点击“Embedding 模型”时加载 `GET /api/model-configs/settings?role=EMBEDDING`。
- 顶部 Active 卡片来自快照里的 `active.chat` 和 `active.embedding`。
- 左侧配置列表来自快照里的 `configs`。
- 右侧表单来自快照里的 `selectedConfig`，并直接绑定前端 store 的单一 `form`。

如果请求失败，页面不会清空已有表单，只显示错误并允许用户重新读取。模型页不显示整块加载遮罩，避免切换设置页时出现闪烁。

## API Key 处理

当前开发阶段 API Key 明文保存到：

```text
%APPDATA%\CogniNote\data\cogninote.db
```

这是为了先打通本地闭环的临时取舍。公开发布前应改为 Windows 本地加密或凭据管理。

保存配置时，如果 API Key 留空，后端会复用该配置已保存的 key。这样用户只改模型名、Base URL、temperature、Top K 或维度时，不需要重新输入密钥。

保存 Chat 配置时，如果上下文窗口留空，后端会回退默认 `128000`。保存 Embedding 配置时，后端会忽略上下文窗口并保存为 `null`。

追问补全策略的环境变量兜底为 `COGNINOTE_QUERY_CONTEXTUALIZER_MODE`，合法值为 `AUTO`、`ALWAYS`、`OFF`。旧变量 `COGNINOTE_QUERY_CONTEXTUALIZER_ENABLED=false` 仍兼容为 `OFF`，但只在没有 SQLite 用户设置且没有 mode 配置时生效。

## 环境变量 fallback

没有 SQLite Embedding 配置或 active Embedding 配置没有 API Key 时，Embedding 仍保留 Phase 3 的环境变量 fallback：

```powershell
$env:COGNINOTE_AI_EMBEDDING_PROVIDER="dashscope"
$env:COGNINOTE_DASHSCOPE_API_KEY="your-api-key"
$env:COGNINOTE_EMBEDDING_MODEL="text-embedding-v4"
```

如果 SQLite 中存在 active Embedding 配置并填写了 API Key，优先使用 SQLite。

## 常见问题

### DashScope 连接测试返回 url error

通常是把自定义 OpenAI-compatible URL 配到了 DashScope Provider，或者把 DashScope 的 `/api/v1` 地址直接传给了不该接收它的客户端。

处理方式：

- 阿里百炼选择 `DASHSCOPE`，使用默认地址。
- 自定义网关、OpenAI-compatible 服务选择 `OPENAI_COMPATIBLE`。

### 获取模型失败

模型列表接口并不是所有服务都实现完整。如果获取失败，可以手动输入模型 ID 后保存。Chat 调用和 Embedding 调用只依赖最终保存的模型名。

### Embedding 不可用

Embedding 不可用会影响向量索引、向量检索和混合检索。RAG 对话在 `HYBRID` 或 `VECTOR` 失败时会尝试降级到 `KEYWORD`，并在 SSE `meta.retrievalMode` 中返回实际检索模式。
